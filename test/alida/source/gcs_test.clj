(ns alida.source.gcs-test
  (:require [alida.source :as source]
            [alida.source.gcs]
            [clojure.data.json :as json]
            [clojure.test :refer [deftest is]]))

(defn- fake-gcs
  [list-pages objects requests]
  {:alida/gcs-list-page
   (fn [_client request]
     (swap! requests conj (assoc request :op :list))
     (or (get list-pages request)
         {:cognitect.anomalies/category :cognitect.anomalies/fault
          :message (str "missing fake list response for " request)}))
   :alida/gcs-fetch-object
   (fn [_client request]
     (swap! requests conj (assoc request :op :fetch))
     (or (get objects request)
         {:cognitect.anomalies/category :cognitect.anomalies/fault
          :message (str "missing fake fetch response for " request)}))})

(def source-cfg
  {:id "objects"
   :type "gcs"
   :bucket "alida-gcs-fixtures"
   :prefix "fixtures/docs/"})

(deftest discovers-gcs-objects
  (let [requests (atom [])
        sys (fake-gcs {{:bucket "alida-gcs-fixtures"
                        :prefix "fixtures/docs/"
                        :max_results 1000}
                       {:items [{:name "fixtures/docs/guide.json"
                                 :size 10
                                 :etag "etag-1"}
                                {:name "fixtures/docs/manual.json" :size 20}
                                {:name "fixtures/docs/folder/" :size 0}]
                        :next_page_token nil}}
                      {}
                      requests)]
    (is (= [{:source_id "objects"
             :source_type "gcs"
             :canonical_url "gs://alida-gcs-fixtures/fixtures/docs/guide.json"
             :bucket "alida-gcs-fixtures"
             :key "fixtures/docs/guide.json"
             :content_type "application/json"
             :size 10
             :etag "etag-1"}
            {:source_id "objects"
             :source_type "gcs"
             :canonical_url "gs://alida-gcs-fixtures/fixtures/docs/manual.json"
             :bucket "alida-gcs-fixtures"
             :key "fixtures/docs/manual.json"
             :content_type "application/json"
             :size 20
             :etag nil}]
           (source/discover sys source-cfg)))
    (is (= [{:op :list
             :bucket "alida-gcs-fixtures"
             :prefix "fixtures/docs/"
             :max_results 1000}]
           @requests))))

(deftest discover-paginates-and-honors-max-pages
  (let [requests (atom [])
        sys (fake-gcs {{:bucket "alida-gcs-fixtures"
                        :prefix "fixtures/docs/"
                        :max_results 2}
                       {:items [{:name "fixtures/docs/a.json"}]
                        :next_page_token "page-2"}

                       {:bucket "alida-gcs-fixtures"
                        :prefix "fixtures/docs/"
                        :max_results 1
                        :page_token "page-2"}
                       {:items [{:name "fixtures/docs/b.json"}]
                        :next_page_token "page-3"}}
                      {}
                      requests)]
    (is (= ["fixtures/docs/a.json" "fixtures/docs/b.json"]
           (mapv :key (source/discover sys (assoc source-cfg :max_pages 2)))))
    (is (= [nil "page-2"]
           (mapv :page_token @requests)))))

(deftest discover-applies-include-and-exclude-globs
  (let [requests (atom [])
        sys (fake-gcs {{:bucket "alida-gcs-fixtures"
                        :prefix "fixtures/docs/"
                        :max_results 1000}
                       {:items [{:name "fixtures/docs/a.json"}
                                {:name "fixtures/docs/private/b.json"}
                                {:name "fixtures/docs/c.txt"}]}}
                      {}
                      requests)]
    (is (= ["fixtures/docs/a.json"]
           (mapv :key (source/discover sys (assoc source-cfg
                                                  :include_globs ["fixtures/docs/**/*.json"]
                                                  :exclude_globs ["fixtures/docs/private/**"])))))))

(deftest recursive-globs-include-direct-and-nested-children
  (let [requests (atom [])
        sys (fake-gcs {{:bucket "alida-gcs-fixtures"
                        :prefix "fixtures/docs/"
                        :max_results 1000}
                       {:items [{:name "fixtures/docs/a.json"}
                                {:name "fixtures/docs/nested/b.json"}
                                {:name "fixtures/docs/c.txt"}]}}
                      {}
                      requests)]
    (is (= ["fixtures/docs/a.json" "fixtures/docs/nested/b.json"]
           (mapv :key (source/discover sys (assoc source-cfg
                                                  :include_globs ["fixtures/docs/**/*.json"])))))))

(deftest fetches-gcs-object-content
  (let [requests (atom [])
        sys (fake-gcs {}
                      {{:bucket "alida-gcs-fixtures"
                        :key "fixtures/docs/guide.json"}
                       {:body "{\"title\":\"Guide\"}"
                        :content_type "application/json; charset=utf-8"}}
                      requests)
        item {:source_id "objects"
              :source_type "gcs"
              :canonical_url "gs://alida-gcs-fixtures/fixtures/docs/guide.json"
              :bucket "alida-gcs-fixtures"
              :key "fixtures/docs/guide.json"
              :content_type "application/json"}]
    (is (= (assoc item
                  :body "{\"title\":\"Guide\"}"
                  :content_type "application/json; charset=utf-8"
                  :title "fixtures/docs/guide.json")
           (source/fetch sys source-cfg item)))))

(deftest generic-gcs-content-type-falls-back-to-extension
  (let [requests (atom [])
        sys (fake-gcs {}
                      {{:bucket "alida-gcs-fixtures"
                        :key "fixtures/docs/guide.json"}
                       {:body "{\"title\":\"Home\"}"
                        :content_type "application/octet-stream"}

                       {:bucket "alida-gcs-fixtures"
                        :key "fixtures/docs/page.html"}
                       {:body "<h1>Page</h1>"
                        :content_type "binary/octet-stream; charset=binary"}}
                      requests)
        json-item {:canonical_url "gs://alida-gcs-fixtures/fixtures/docs/guide.json"
                   :bucket "alida-gcs-fixtures"
                   :key "fixtures/docs/guide.json"
                   :content_type "application/json"}
        html-item {:canonical_url "gs://alida-gcs-fixtures/fixtures/docs/page.html"
                   :bucket "alida-gcs-fixtures"
                   :key "fixtures/docs/page.html"
                   :content_type "text/html"}]
    (is (= "application/json"
           (:content_type (source/fetch sys source-cfg json-item))))
    (is (= "text/html"
           (:content_type (source/fetch sys source-cfg html-item))))))

(deftest failed-fetch-is-recoverable
  (let [requests (atom [])
        sys (fake-gcs {}
                      {{:bucket "alida-gcs-fixtures"
                        :key "fixtures/docs/missing.json"}
                       {:cognitect.anomalies/category :cognitect.anomalies/not-found
                        :message "not found"
                        :status 404}}
                      requests)
        item {:canonical_url "gs://alida-gcs-fixtures/fixtures/docs/missing.json"
              :bucket "alida-gcs-fixtures"
              :key "fixtures/docs/missing.json"}
        result (source/fetch sys source-cfg item)]
    (is (source/anomaly? result))
    (is (= :cognitect.anomalies/not-found
           (get-in result [:alida/error :cognitect.anomalies/category])))
    (is (= :alida.source.gcs/fetch-failed
           (get-in result [:alida/error :type])))))

(deftest failed-fetch-sanitizes-gcs-anomaly-details
  (let [requests (atom [])
        throwable (Exception. "connection reset")
        sys (fake-gcs {}
                      {{:bucket "alida-gcs-fixtures"
                        :key "fixtures/docs/missing.json"}
                       {:cognitect.anomalies/category :cognitect.anomalies/fault
                        :message "transport failed"
                        :exception throwable
                        :retryable? #{:yes}}}
                      requests)
        item {:canonical_url "gs://alida-gcs-fixtures/fixtures/docs/missing.json"
              :bucket "alida-gcs-fixtures"
              :key "fixtures/docs/missing.json"}
        result (source/fetch sys source-cfg item)
        error (:alida/error result)]
    (is (source/anomaly? result))
    (is (= {:class "java.lang.Exception"
            :message "connection reset"}
           (:exception error)))
    (is (= [:yes] (:retryable? error)))
    (is (string? (json/write-str error)))))

(deftest failed-discover-sanitizes-gcs-anomaly-details
  (let [requests (atom [])
        sys (fake-gcs {{:bucket "alida-gcs-fixtures"
                        :prefix "fixtures/docs/"
                        :max_results 1000}
                       {:cognitect.anomalies/category :cognitect.anomalies/fault
                        :message "list failed"
                        :exception (Exception. "timeout")}}
                      {}
                      requests)]
    (try
      (source/discover sys source-cfg)
      (is false "expected discover to throw")
      (catch clojure.lang.ExceptionInfo e
        (let [data (ex-data e)]
          (is (= {:class "java.lang.Exception"
                  :message "timeout"}
                 (:exception data)))
          (is (string? (json/write-str data))))))))
