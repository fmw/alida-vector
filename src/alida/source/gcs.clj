(ns alida.source.gcs
  (:require [alida.source :as source]
            [alida.source.object-storage :as object-storage]
            [clojure.string :as str])
  (:import [com.google.cloud.storage Blob BlobId Storage Storage$BlobListOption StorageException StorageOptions]))

(defn- gcs-client
  [sys source-cfg]
  (or (:alida/gcs-client sys)
      (when (or (:alida/gcs-list-page sys)
                (:alida/gcs-fetch-object sys))
        nil)
      (let [builder (StorageOptions/newBuilder)]
        (when-let [project-id (:project_id source-cfg)]
          (.setProjectId builder project-id))
        (.getService (.build builder)))))

(defn- gcs-anomaly?
  [value]
  (and (map? value)
       (contains? value :cognitect.anomalies/category)))

(defn- sanitize-gcs-anomaly
  [result]
  (object-storage/json-safe-value result))

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

(defn- throw-gcs-anomaly!
  [source-cfg op result]
  (when (gcs-anomaly? result)
    (throw (ex-info (str "GCS " (name op) " failed")
                    (assoc (sanitize-gcs-anomaly result)
                           :type :alida.source.gcs/request-failed
                           :source-id (:id source-cfg)
                           :operation op)))))

(defn- fetch-anomaly
  [source-cfg op item result]
  (source/anomaly (or (:cognitect.anomalies/category result)
                      :cognitect.anomalies/fault)
                  (assoc (sanitize-gcs-anomaly result)
                         :type :alida.source.gcs/fetch-failed
                         :source-id (:id source-cfg)
                         :operation op
                         :canonical-url (:canonical_url item)
                         :bucket (:bucket item)
                         :key (:key item))))

(defn- canonical-url
  [bucket key]
  (object-storage/canonical-url "gs" bucket key))

(defn- object-item
  [source-cfg object]
  (let [bucket (:bucket source-cfg)
        key (:name object)]
    {:source_id (:id source-cfg)
     :source_type (:type source-cfg)
     :canonical_url (canonical-url bucket key)
     :bucket bucket
     :key key
     :content_type (object-storage/content-type key (:content_type object))
     :size (:size object)
     :etag (:etag object)}))

(defn- page-objects
  [source-cfg response]
  (->> (:items response)
       (keep (fn [object]
               (let [key (:name object)]
                 (when (and (seq key)
                            (not (str/ends-with? key "/"))
                            (object-storage/object-included? source-cfg key))
                   (object-item source-cfg object)))))))

(defn- max-pages
  [source-cfg]
  (or (:max_pages source-cfg) object-storage/default-max-pages))

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
  (loop [items []
         page-token nil]
    (let [remaining (- (max-pages source-cfg) (count items))]
      (if (not (pos? remaining))
        items
        (let [response (list-page! sys client source-cfg page-token remaining)
              _ (throw-gcs-anomaly! source-cfg :list response)
              page-items (vec (take remaining (page-objects source-cfg response)))
              items (into items page-items)
              next-token (:next_page_token response)]
          (if (and (seq next-token)
                   (< (count items) (max-pages source-cfg)))
            (recur items next-token)
            items))))))

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
      {:body (.getContent blob)
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
      (if (gcs-anomaly? response)
        (fetch-anomaly source-cfg :fetch discovered-item response)
        (assoc discovered-item
               :body (object-storage/body-string (:body response))
               :content_type (object-storage/content-type (:key discovered-item) (:content_type response))
               :title (or (:title discovered-item) (:key discovered-item)))))))
