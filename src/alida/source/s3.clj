(ns alida.source.s3
  (:require [alida.source :as source]
            [alida.source.object-storage :as object-storage]
            [clojure.string :as str]
            [cognitect.aws.client.api :as aws]))

(defn- s3-client
  [sys source-cfg]
  (or (:alida/s3-client sys)
      (when (:alida/s3-invoke sys)
        nil)
      (aws/client (cond-> {:api :s3}
                    (:region source-cfg) (assoc :region (:region source-cfg))))))

(defn- invoke!
  [sys client request]
  (if-let [f (:alida/s3-invoke sys)]
    (f client request)
    (aws/invoke client request)))

(defn- aws-anomaly?
  [value]
  (and (map? value)
       (contains? value :cognitect.anomalies/category)))

(defn- sanitize-aws-anomaly
  [result]
  (object-storage/json-safe-value result))

(defn- throw-aws-anomaly!
  [source-cfg op result]
  (when (aws-anomaly? result)
    (throw (ex-info (str "S3 " (name op) " failed")
                    (assoc (sanitize-aws-anomaly result)
                           :type :alida.source.s3/request-failed
                           :source-id (:id source-cfg)
                           :operation op)))))

(defn- fetch-anomaly
  [source-cfg op item result]
  (source/anomaly (or (:cognitect.anomalies/category result)
                      :cognitect.anomalies/fault)
                  (assoc (sanitize-aws-anomaly result)
                         :type :alida.source.s3/fetch-failed
                         :source-id (:id source-cfg)
                         :operation op
                         :canonical-url (:canonical_url item)
                         :bucket (:bucket item)
                         :key (:key item))))

(defn- canonical-url
  [bucket key]
  (object-storage/canonical-url "s3" bucket key))

(defn- object-item
  [source-cfg object]
  (let [bucket (:bucket source-cfg)
        key (:Key object)]
    {:source_id (:id source-cfg)
     :source_type (:type source-cfg)
     :canonical_url (canonical-url bucket key)
     :bucket bucket
     :key key
     :content_type (object-storage/content-type key nil)
     :size (:Size object)
     :etag (:ETag object)
     :last_modified (:LastModified object)}))

(defn- page-objects
  [source-cfg response]
  (->> (:Contents response)
       (keep (fn [object]
               (let [key (:Key object)]
                 (when (and (seq key)
                            (not (str/ends-with? key "/"))
                            (object-storage/object-included? source-cfg key))
                   (object-item source-cfg object)))))))

(defn- max-pages
  [source-cfg]
  (or (:max_pages source-cfg) object-storage/default-max-pages))

(defn- list-request
  [source-cfg continuation-token remaining]
  {:op :ListObjectsV2
   :request (cond-> {:Bucket (:bucket source-cfg)
                     :MaxKeys (min 1000 remaining)}
              (:prefix source-cfg) (assoc :Prefix (:prefix source-cfg))
              continuation-token (assoc :ContinuationToken continuation-token))})

(defn- discover*
  [sys source-cfg client]
  (loop [items []
         continuation-token nil]
    (let [remaining (- (max-pages source-cfg) (count items))]
      (if (not (pos? remaining))
        items
        (let [request (list-request source-cfg continuation-token remaining)
              response (invoke! sys client request)
              _ (throw-aws-anomaly! source-cfg (:op request) response)
              page-items (vec (take remaining (page-objects source-cfg response)))
              items (into items page-items)
              next-token (:NextContinuationToken response)]
          (if (and (:IsTruncated response)
                   (seq next-token)
                   (< (count items) (max-pages source-cfg)))
            (recur items next-token)
            items))))))

(defmethod source/discover :s3
  [sys source-cfg]
  (when-not (:bucket source-cfg)
    (throw (ex-info "S3 source requires bucket"
                    {:type :alida.source.s3/missing-bucket
                     :source-id (:id source-cfg)})))
  (discover* sys source-cfg (s3-client sys source-cfg)))

(defmethod source/fetch :s3
  [sys source-cfg discovered-item]
  (if (source/anomaly? discovered-item)
    discovered-item
    (let [client (s3-client sys source-cfg)
          request {:op :GetObject
                   :request {:Bucket (:bucket discovered-item)
                             :Key (:key discovered-item)}}
          response (invoke! sys client request)]
      (if (aws-anomaly? response)
        (fetch-anomaly source-cfg (:op request) discovered-item response)
        (assoc discovered-item
               :body (object-storage/body-string (:Body response))
               :content_type (object-storage/content-type (:key discovered-item) (:ContentType response))
               :title (or (:title discovered-item) (:key discovered-item)))))))
