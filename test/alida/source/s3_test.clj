(ns alida.source.s3-test
  (:require [alida.source :as source]
            [alida.source.s3]
            [clojure.data.json :as json]
            [clojure.test :refer [deftest is]]))

(defn- fake-s3
  [responses requests]
  {:alida/s3-invoke
   (fn [_client request]
     (swap! requests conj request)
     (let [k [(:op request) (:request request)]]
       (or (get responses k)
           {:cognitect.anomalies/category :cognitect.anomalies/fault
            :message (str "missing fake response for " k)})))})

(def source-cfg
  {:id "kb"
   :type "s3"
   :bucket "alida-fixtures"
   :prefix "docs/"})

(deftest discovers-s3-objects
  (let [requests (atom [])
        sys (fake-s3 {[:ListObjectsV2 {:Bucket "alida-fixtures"
                                       :Prefix "docs/"
                                       :MaxKeys 1000}]
                      {:Contents [{:Key "docs/index.html"
                                   :Size 10
                                   :ETag "\"etag-1\""
                                   :LastModified #inst "2026-06-15T10:00:00Z"}
                                  {:Key "docs/guide.md" :Size 20}
                                  {:Key "docs/folder/" :Size 0}]
                       :IsTruncated false}}
                     requests)]
    (is (= [{:source_id "kb"
             :source_type "s3"
             :canonical_url "s3://alida-fixtures/docs/index.html"
             :bucket "alida-fixtures"
             :key "docs/index.html"
             :content_type "text/html"
             :size 10
             :etag "\"etag-1\""
             :last_modified #inst "2026-06-15T10:00:00Z"}
            {:source_id "kb"
             :source_type "s3"
             :canonical_url "s3://alida-fixtures/docs/guide.md"
             :bucket "alida-fixtures"
             :key "docs/guide.md"
             :content_type "text/markdown"
             :size 20
             :etag nil
             :last_modified nil}]
           (source/discover sys source-cfg)))
    (is (= [[:ListObjectsV2 {:Bucket "alida-fixtures"
                             :Prefix "docs/"
                             :MaxKeys 1000}]]
           (mapv (juxt :op :request) @requests)))))

(deftest discover-paginates-and-honors-max-pages
  (let [requests (atom [])
        sys (fake-s3 {[:ListObjectsV2 {:Bucket "alida-fixtures"
                                       :Prefix "docs/"
                                       :MaxKeys 2}]
                      {:Contents [{:Key "docs/a.txt"}]
                       :IsTruncated true
                       :NextContinuationToken "page-2"}

                      [:ListObjectsV2 {:Bucket "alida-fixtures"
                                       :Prefix "docs/"
                                       :MaxKeys 1
                                       :ContinuationToken "page-2"}]
                      {:Contents [{:Key "docs/b.txt"}]
                       :IsTruncated true
                       :NextContinuationToken "page-3"}}
                     requests)]
    (is (= ["docs/a.txt" "docs/b.txt"]
           (mapv :key (source/discover sys (assoc source-cfg :max_pages 2)))))
    (is (= [nil "page-2"]
           (mapv #(get-in % [:request :ContinuationToken]) @requests)))))

(deftest discover-applies-include-and-exclude-globs
  (let [requests (atom [])
        sys (fake-s3 {[:ListObjectsV2 {:Bucket "alida-fixtures"
                                       :Prefix "docs/"
                                       :MaxKeys 1000}]
                      {:Contents [{:Key "docs/a.md"}
                                  {:Key "docs/private/b.md"}
                                  {:Key "docs/c.txt"}]
                       :IsTruncated false}}
                     requests)]
    (is (= ["docs/a.md"]
           (mapv :key (source/discover sys (assoc source-cfg
                                                  :include_globs ["docs/**/*.md"]
                                                  :exclude_globs ["docs/private/**"])))))))

(deftest recursive-globs-include-direct-and-nested-children
  (let [requests (atom [])
        sys (fake-s3 {[:ListObjectsV2 {:Bucket "alida-fixtures"
                                       :Prefix "docs/"
                                       :MaxKeys 1000}]
                      {:Contents [{:Key "docs/a.md"}
                                  {:Key "docs/nested/b.md"}
                                  {:Key "docs/c.txt"}]
                       :IsTruncated false}}
                     requests)]
    (is (= ["docs/a.md" "docs/nested/b.md"]
           (mapv :key (source/discover sys (assoc source-cfg
                                                  :include_globs ["docs/**/*.md"])))))))

(deftest recursive-globs-handle-multiple-recursive-segments
  (let [requests (atom [])
        sys (fake-s3 {[:ListObjectsV2 {:Bucket "alida-fixtures"
                                       :Prefix "content/"
                                       :MaxKeys 1000}]
                      {:Contents [{:Key "content/exports/en/a.json"}
                                  {:Key "content/site/exports/a.json"}
                                  {:Key "content/site/exports/private/secret.json"}
                                  {:Key "content/other/a.json"}]
                       :IsTruncated false}}
                     requests)]
    (is (= ["content/exports/en/a.json" "content/site/exports/a.json"]
           (mapv :key (source/discover sys (assoc source-cfg
                                                  :prefix "content/"
                                                  :include_globs ["content/**/exports/**/*.json"]
                                                  :exclude_globs ["content/**/exports/private/**/*.json"])))))))

(deftest fetches-s3-object-content
  (let [requests (atom [])
        sys (fake-s3 {[:GetObject {:Bucket "alida-fixtures"
                                   :Key "docs/guide.md"}]
                      {:Body "# Guide\n\nHello"
                       :ContentType "text/markdown; charset=utf-8"}}
                     requests)
        item {:source_id "kb"
              :source_type "s3"
              :canonical_url "s3://alida-fixtures/docs/guide.md"
              :bucket "alida-fixtures"
              :key "docs/guide.md"
              :content_type "text/markdown"}]
    (is (= (assoc item
                  :body "# Guide\n\nHello"
                  :content_type "text/markdown; charset=utf-8"
                  :title "docs/guide.md")
           (source/fetch sys source-cfg item)))))

(deftest generic-s3-content-type-falls-back-to-extension
  (let [requests (atom [])
        sys (fake-s3 {[:GetObject {:Bucket "alida-fixtures"
                                   :Key "docs/guide.md"}]
                      {:Body "# Guide"
                       :ContentType "application/octet-stream"}

                      [:GetObject {:Bucket "alida-fixtures"
                                   :Key "docs/page.html"}]
                      {:Body "<h1>Page</h1>"
                       :ContentType "binary/octet-stream; charset=binary"}}
                     requests)
        md-item {:canonical_url "s3://alida-fixtures/docs/guide.md"
                 :bucket "alida-fixtures"
                 :key "docs/guide.md"
                 :content_type "text/markdown"}
        html-item {:canonical_url "s3://alida-fixtures/docs/page.html"
                   :bucket "alida-fixtures"
                   :key "docs/page.html"
                   :content_type "text/html"}]
    (is (= "text/markdown"
           (:content_type (source/fetch sys source-cfg md-item))))
    (is (= "text/html"
           (:content_type (source/fetch sys source-cfg html-item))))))

(deftest failed-fetch-is-recoverable
  (let [requests (atom [])
        sys (fake-s3 {[:GetObject {:Bucket "alida-fixtures"
                                   :Key "missing.txt"}]
                      {:cognitect.anomalies/category :cognitect.anomalies/not-found
                       :message "not found"}}
                     requests)
        item {:canonical_url "s3://alida-fixtures/missing.txt"
              :bucket "alida-fixtures"
              :key "missing.txt"}
        result (source/fetch sys source-cfg item)]
    (is (source/anomaly? result))
    (is (= :cognitect.anomalies/not-found
           (get-in result [:alida/error :cognitect.anomalies/category])))
    (is (= :alida.source.s3/fetch-failed
           (get-in result [:alida/error :type])))))

(deftest failed-fetch-sanitizes-aws-anomaly-details
  (let [requests (atom [])
        throwable (Exception. "socket closed")
        sys (fake-s3 {[:GetObject {:Bucket "alida-fixtures"
                                   :Key "missing.txt"}]
                      {:cognitect.anomalies/category :cognitect.anomalies/fault
                       :message "transport failed"
                       :cognitect.aws/throwable throwable
                       :retryable? #{:yes}}}
                     requests)
        item {:canonical_url "s3://alida-fixtures/missing.txt"
              :bucket "alida-fixtures"
              :key "missing.txt"}
        result (source/fetch sys source-cfg item)
        error (:alida/error result)]
    (is (source/anomaly? result))
    (is (= {:class "java.lang.Exception"
            :message "socket closed"}
           (:cognitect.aws/throwable error)))
    (is (= [:yes] (:retryable? error)))
    (is (string? (json/write-str error)))))

(deftest failed-discover-sanitizes-aws-anomaly-details
  (let [requests (atom [])
        sys (fake-s3 {[:ListObjectsV2 {:Bucket "alida-fixtures"
                                       :Prefix "docs/"
                                       :MaxKeys 1000}]
                      {:cognitect.anomalies/category :cognitect.anomalies/fault
                       :message "list failed"
                       :cognitect.aws/throwable (Exception. "timeout")}}
                     requests)]
    (try
      (source/discover sys source-cfg)
      (is false "expected discover to throw")
      (catch clojure.lang.ExceptionInfo e
        (let [data (ex-data e)]
          (is (= {:class "java.lang.Exception"
                  :message "timeout"}
                 (:cognitect.aws/throwable data)))
          (is (string? (json/write-str data))))))))
