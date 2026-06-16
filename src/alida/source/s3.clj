(ns alida.source.s3
  (:require [alida.source :as source]
            [alida.source.object-storage :as object-storage]
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

(defn- page-objects
  [source-cfg response]
  (object-storage/page-objects source-cfg
                               "s3"
                               (:Contents response)
                               :Key
                               (fn [object key]
                                 {:content_type (object-storage/content-type key nil)
                                  :size (:Size object)
                                  :etag (:ETag object)
                                  :last_modified (:LastModified object)})))

(defn- list-request
  [source-cfg continuation-token remaining]
  {:op :ListObjectsV2
   :request (cond-> {:Bucket (:bucket source-cfg)
                     :MaxKeys (min 1000 remaining)}
              (:prefix source-cfg) (assoc :Prefix (:prefix source-cfg))
              continuation-token (assoc :ContinuationToken continuation-token))})

(defn- discover*
  [sys source-cfg client]
  (object-storage/discover-paged
   source-cfg
   {:op :ListObjectsV2
    :service-label "S3"
    :request-error-type :alida.source.s3/request-failed
    :list-page (fn [continuation-token remaining]
                 (invoke! sys client (list-request source-cfg continuation-token remaining)))
    :page-objects #(page-objects source-cfg %)
    :next-token :NextContinuationToken
    :continue? (fn [response next-token]
                 (and (:IsTruncated response) (seq next-token)))}))

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
      (object-storage/fetched-document
       source-cfg
       discovered-item
       response
       {:op (:op request)
        :fetch-error-type :alida.source.s3/fetch-failed
        :body-fn :Body
        :content-type-fn :ContentType}))))
