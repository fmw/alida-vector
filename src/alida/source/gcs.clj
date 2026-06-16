(ns alida.source.gcs
  (:require [alida.source :as source]
            [alida.source.object-storage :as object-storage]
            [clojure.java.io :as io])
  (:import [com.google.auth.oauth2 AccessToken GoogleCredentials]
           [com.google.cloud.storage Blob Blob$BlobSourceOption BlobId Storage Storage$BlobListOption StorageException StorageOptions]
           [java.util Date]))

(defn- access-token-credentials
  [token]
  (GoogleCredentials/create
   (AccessToken. token (Date. (+ (System/currentTimeMillis) 3600000)))))

(defn- file-credentials
  [path]
  (with-open [in (io/input-stream path)]
    (GoogleCredentials/fromStream in)))

(defn- configured-credentials
  [source-cfg]
  (cond
    (:access_token source-cfg)
    (access-token-credentials (:access_token source-cfg))

    (:credentials_path source-cfg)
    (file-credentials (:credentials_path source-cfg))))

(defn- gcs-client
  [sys source-cfg]
  (or (:alida/gcs-client sys)
      (when (or (:alida/gcs-list-page sys)
                (:alida/gcs-fetch-object sys))
        nil)
      (let [builder (StorageOptions/newBuilder)]
        (when-let [project-id (:project_id source-cfg)]
          (.setProjectId builder project-id))
        (when-let [credentials (configured-credentials source-cfg)]
          (.setCredentials builder credentials))
        (.getService (.build builder)))))

(defn- exception-anomaly
  [category e details]
  (merge {:cognitect.anomalies/category category
          :message (ex-message e)
          :exception e}
         details))

(defn- storage-exception-anomaly
  [^StorageException e details]
  (exception-anomaly (if (= 404 (.getCode e))
                       :cognitect.anomalies/not-found
                       :cognitect.anomalies/fault)
                     e
                     (assoc details :status (.getCode e))))

(defn- page-objects
  [source-cfg response]
  (object-storage/page-objects source-cfg
                               "gs"
                               (:items response)
                               :name
                               (fn [object key]
                                 {:content_type (object-storage/content-type key (:content_type object))
                                  :size (:size object)
                                  :etag (:etag object)})))

(defn- iterable->vec
  [values]
  (vec (iterator-seq (.iterator values))))

(defn- blob-summary
  [^Blob blob]
  {:name (.getName blob)
   :content_type (.getContentType blob)
   :size (.getSize blob)
   :etag (.getEtag blob)})

(defn- list-options
  [source-cfg page-token remaining]
  (into-array Storage$BlobListOption
              (cond-> [(Storage$BlobListOption/pageSize (long (min 1000 remaining)))]
                (:prefix source-cfg) (conj (Storage$BlobListOption/prefix (:prefix source-cfg)))
                page-token (conj (Storage$BlobListOption/pageToken page-token)))))

(defn- real-list-page!
  [^Storage client source-cfg page-token remaining]
  (try
    (let [page (.list client (:bucket source-cfg) (list-options source-cfg page-token remaining))]
      {:items (mapv blob-summary (iterable->vec (.getValues page)))
       :next_page_token (.getNextPageToken page)})
    (catch StorageException e
      (storage-exception-anomaly e {:bucket (:bucket source-cfg)
                                    :prefix (:prefix source-cfg)}))
    (catch Exception e
      (exception-anomaly :cognitect.anomalies/fault e {:bucket (:bucket source-cfg)
                                                       :prefix (:prefix source-cfg)}))))

(defn- list-page!
  [sys client source-cfg page-token remaining]
  (if-let [f (:alida/gcs-list-page sys)]
    (f client (cond-> {:bucket (:bucket source-cfg)
                       :max_results (min 1000 remaining)}
                (:prefix source-cfg) (assoc :prefix (:prefix source-cfg))
                page-token (assoc :page_token page-token)))
    (real-list-page! client source-cfg page-token remaining)))

(defn- discover*
  [sys source-cfg client]
  (object-storage/discover-paged
   source-cfg
   {:op :list
    :service-label "GCS"
    :request-error-type :alida.source.gcs/request-failed
    :list-page (fn [page-token remaining]
                 (list-page! sys client source-cfg page-token remaining))
    :page-objects #(page-objects source-cfg %)
    :next-token :next_page_token
    :continue? (fn [_response next-token]
                 (seq next-token))}))

(defmethod source/discover :gcs
  [sys source-cfg]
  (when-not (:bucket source-cfg)
    (throw (ex-info "GCS source requires bucket"
                    {:type :alida.source.gcs/missing-bucket
                     :source-id (:id source-cfg)})))
  (discover* sys source-cfg (gcs-client sys source-cfg)))

(defn- real-fetch-object!
  [^Storage client bucket key]
  (try
    (if-let [blob (.get client (BlobId/of bucket key))]
      {:body (.getContent blob (make-array Blob$BlobSourceOption 0))
       :content_type (.getContentType blob)}
      {:cognitect.anomalies/category :cognitect.anomalies/not-found
       :message (str "GCS object not found: gs://" bucket "/" key)
       :status 404})
    (catch StorageException e
      (storage-exception-anomaly e {:bucket bucket
                                    :key key}))
    (catch Exception e
      (exception-anomaly :cognitect.anomalies/fault e {:bucket bucket
                                                       :key key}))))

(defn- fetch-object!
  [sys client item]
  (if-let [f (:alida/gcs-fetch-object sys)]
    (f client {:bucket (:bucket item)
               :key (:key item)})
    (real-fetch-object! client (:bucket item) (:key item))))

(defmethod source/fetch :gcs
  [sys source-cfg discovered-item]
  (if (source/anomaly? discovered-item)
    discovered-item
    (let [client (gcs-client sys source-cfg)
          response (fetch-object! sys client discovered-item)]
      (object-storage/fetched-document
       source-cfg
       discovered-item
       response
       {:op :fetch
        :fetch-error-type :alida.source.gcs/fetch-failed
        :body-fn :body
        :content-type-fn :content_type}))))
