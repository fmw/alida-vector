(ns alida.source.s3
  (:require [alida.source :as source]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [cognitect.aws.client.api :as aws])
  (:import [java.io InputStream]
           [java.nio.file FileSystems Paths]))

(def default-max-pages 1000)

(def extension-content-types
  {"html" "text/html"
   "htm" "text/html"
   "txt" "text/plain"
   "md" "text/markdown"
   "markdown" "text/markdown"
   "json" "application/json"})

(def generic-s3-content-types
  #{"application/octet-stream"
    "binary/octet-stream"})

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

(defn- json-safe-value
  [value]
  (cond
    (or (nil? value)
        (string? value)
        (number? value)
        (boolean? value)
        (keyword? value)
        (inst? value))
    value

    (instance? Throwable value)
    {:class (.getName (class value))
     :message (ex-message value)}

    (map? value)
    (into {} (map (fn [[k v]] [k (json-safe-value v)]) value))

    (sequential? value)
    (mapv json-safe-value value)

    (set? value)
    (mapv json-safe-value value)

    :else
    (str value)))

(defn- sanitize-aws-anomaly
  [result]
  (json-safe-value result))

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

(defn- extension
  [path]
  (some-> (re-find #"\.([^.]+)$" (str path))
          second
          str/lower-case))

(defn- content-type
  [key s3-content-type]
  (or (when-let [value (not-empty (str/trim (str s3-content-type)))]
        (let [base-type (str/trim (first (str/split (str/lower-case value) #";" 2)))]
          (when-not (contains? generic-s3-content-types base-type)
            value)))
      (get extension-content-types (extension key))
      "application/octet-stream"))

(defn- canonical-url
  [bucket key]
  (str "s3://" bucket "/" key))

(defn- path-matcher
  [glob]
  (.getPathMatcher (FileSystems/getDefault) (str "glob:" glob)))

(defn- glob-matches?
  [glob key]
  (.matches (path-matcher glob) (Paths/get key (make-array String 0))))

(defn- object-included?
  [source-cfg key]
  (let [include-globs (:include_globs source-cfg)
        exclude-globs (:exclude_globs source-cfg)]
    (and (or (empty? include-globs)
             (some #(glob-matches? % key) include-globs))
         (not-any? #(glob-matches? % key) exclude-globs))))

(defn- object-item
  [source-cfg object]
  (let [bucket (:bucket source-cfg)
        key (:Key object)]
    {:source_id (:id source-cfg)
     :source_type (:type source-cfg)
     :canonical_url (canonical-url bucket key)
     :bucket bucket
     :key key
     :content_type (content-type key nil)
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
                            (object-included? source-cfg key))
                   (object-item source-cfg object)))))))

(defn- max-pages
  [source-cfg]
  (or (:max_pages source-cfg) default-max-pages))

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

(defn- body-string
  [body]
  (cond
    (string? body) body
    (bytes? body) (String. ^bytes body "UTF-8")
    (instance? InputStream body) (with-open [reader (io/reader body :encoding "UTF-8")]
                                   (slurp reader))
    (nil? body) ""
    :else (str body)))

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
               :body (body-string (:Body response))
               :content_type (content-type (:key discovered-item) (:ContentType response))
               :title (or (:title discovered-item) (:key discovered-item)))))))
