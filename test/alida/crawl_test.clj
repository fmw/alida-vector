(ns alida.crawl-test
  (:require [alida.crawl :as crawl]
            [alida.db.postgres :as db]
            [alida.source :as source]
            [alida.source.local]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]])
  (:import [java.util.concurrent TimeUnit]))

(defn- temp-file
  [suffix body]
  (let [file (java.io.File/createTempFile "alida-crawl" suffix)]
    (.deleteOnExit file)
    (spit file body)
    file))

(def index-cfg
  {:name "docs"
   :languages {:allowed ["en" "nl"]
               :fallback "en"}
   :embedding {:provider "openai"
               :model "text-embedding-3-small"
               :embedding_dimensions 1536}
   :chunking {:max_tokens 24}
   :sources []})

(deftest process-source-extracts-language-and-chunks-html
  (let [file (temp-file ".html"
                        "<html lang=\"nl\"><head><title>Welkom</title></head>
                         <body><h1>Welkom</h1><p>Dit is een Nederlandse hulppagina met nuttige informatie.</p></body></html>")
        result (crawl/process-source
                {}
                index-cfg
                {:id "fixtures"
                 :type "local"
                 :path (.getPath file)})
        document-result (-> result :documents first)
        document (:document document-result)]
    (is (= 1 (:discovered_count result)))
    (is (= 1 (:document_count result)))
    (is (pos-int? (:chunk_count result)))
    (is (= 0 (:error_count result)))
    (is (str/ends-with? (:title document) ".html"))
    (is (= "nl" (:locale document)))
    (is (= :html (:language_source document)))
    (is (= 64 (count (:normalized_content_hash document))))
    (is (pos-int? (-> document-result :chunks first :estimated_tokens)))
    (is (nat-int? (get-in result [:crawl_stats :discover_duration_ms])))
    (is (nat-int? (get-in result [:crawl_stats :fetch_duration_ms])))
    (is (nat-int? (get-in result [:crawl_stats :extract_duration_ms])))
    (is (nat-int? (get-in result [:crawl_stats :language_duration_ms])))
    (is (nat-int? (get-in result [:crawl_stats :chunk_duration_ms])))))

(deftest process-source-keeps-item-level-errors
  (let [html-file (temp-file ".html" "<h1>Hello</h1><p>This document can be processed.</p>")
        bin-file (temp-file ".bin" "binary-ish content is not a supported document type")
        result (crawl/process-source
                {}
                index-cfg
                {:id "fixtures"
                 :type "local"
                 :paths [(.getPath html-file)
                         (.getPath bin-file)
                         "/tmp/alida-crawl-missing.html"]})]
    (is (= 3 (:discovered_count result)))
    (is (= 1 (:document_count result)))
    (is (= 1 (:chunk_count result)))
    (is (= 2 (:error_count result)))
    (is (= #{:alida.crawl/unsupported-content-type
             :alida.source.local/file-not-found}
           (set (map :type (:errors result)))))))

(deftest process-source-extracts-text-markdown-and-json
  (let [text-file (temp-file ".txt" "Plain document\n\nwith two paragraphs.")
        markdown-file (temp-file ".md" "# Guide\n\nInstall the app.")
        json-file (temp-file ".json" "{\"title\":\"API\",\"body\":\"Use stable endpoints.\"}")
        result (crawl/process-source
                {}
                index-cfg
                {:id "fixtures"
                 :type "local"
                 :paths [(.getPath text-file)
                         (.getPath markdown-file)
                         (.getPath json-file)]})]
    (is (= 3 (:discovered_count result)))
    (is (= 3 (:document_count result)))
    (is (= 0 (:error_count result)))
    (is (= #{"text/plain" "text/markdown" "application/json"}
           (set (map (comp :content_type :document) (:documents result)))))
    (is (every? #(= 64 (count (get-in % [:document :normalized_content_hash])))
                (:documents result)))))

(deftest process-source-records-empty-extracted-pages-as-item-errors
  (let [file (temp-file ".html"
                        "<html lang=\"en\"><head><title>Empty</title></head>
                         <body><div></div></body></html>")
        result (crawl/process-source
                {}
                index-cfg
                {:id "fixtures"
                 :type "local"
                 :path (.getPath file)})]
    (is (= 1 (:discovered_count result)))
    (is (= 0 (:document_count result)))
    (is (= 0 (:chunk_count result)))
    (is (= 1 (:error_count result)))
    (is (= 1 (:empty_or_short_document_count result)))
    (is (= :alida.crawl/empty-document (-> result :errors first :type)))
    (is (= "en" (-> result :errors first :locale)))))

(deftest process-source-deduplicates-discovered-canonical-urls
  (let [file (temp-file ".html" "<h1>Hello</h1><p>This document can be processed.</p>")
        item {:source_id "fixtures"
              :source_type "local"
              :canonical_url (.toString (.toURI file))
              :path (.getPath file)
              :content_type "text/html"}
        result (with-redefs [source/discover (fn [_ _] [item item])]
                 (crawl/process-source
                  {}
                  index-cfg
                  {:id "fixtures"
                   :type "local"}))]
    (is (= 2 (:discovered_count result)))
    (is (= 1 (:unique_discovered_count result)))
    (is (= 1 (:document_count result)))
    (is (= 0 (:error_count result)))))

(deftest process-source-can-deduplicate-documents-by-content-hash
  (let [items [{:source_id "fixtures"
                :source_type "local"
                :canonical_url "https://example.test/article/1"
                :content_type "text/html"}
               {:source_id "fixtures"
                :source_type "local"
                :canonical_url "https://example.test/topic/a/article/1"
                :content_type "text/html"}]
        result (with-redefs [source/discover (fn [_ _] items)
                             source/fetch (fn [_ _ item]
                                            (assoc item
                                                   :body "<html lang=\"en\"><body><h1>Same</h1><p>This document can be processed.</p></body></html>"))]
                 (crawl/process-source
                  {}
                  index-cfg
                  {:id "fixtures"
                   :type "local"
                   :dedupe_content true
                   :dedupe_prefer_url_substrings ["/topic/"]}))]
    (is (= 2 (:processed_document_count result)))
    (is (= 1 (:deduped_document_count result)))
    (is (= 1 (:document_count result)))
    (is (= "https://example.test/topic/a/article/1"
           (-> result :documents first :document :canonical_url)))))

(deftest process-source-deduplicates-documents-by-external-id
  (let [items [{:source_id "fixtures"
                :source_type "local"
                :external_id "article-1"
                :canonical_url "https://example.test/topic/a/article/1"
                :content_type "text/html"}
               {:source_id "fixtures"
                :source_type "local"
                :external_id "article-1"
                :canonical_url "https://example.test/article/1"
                :content_type "text/html"}]
        result (with-redefs [source/discover (fn [_ _] items)
                             source/fetch (fn [_ _ item]
                                            (assoc item
                                                   :body (str "<html lang=\"en\"><body><h1>"
                                                              (:canonical_url item)
                                                              "</h1><p>This document can be processed.</p></body></html>")))]
                 (crawl/process-source
                  {}
                  index-cfg
                  {:id "fixtures"
                   :type "local"
                   :dedupe_prefer_url_substrings ["/topic/"]}))]
    (is (= 2 (:processed_document_count result)))
    (is (= 1 (:deduped_document_count result)))
    (is (= 1 (:document_count result)))
    (is (= "article-1"
           (-> result :documents first :document :external_id)))
    (is (= "https://example.test/topic/a/article/1"
           (-> result :documents first :document :canonical_url)))))

(deftest process-source-records-thrown-fetch-exceptions-as-item-errors
  (let [item {:source_id "fixtures"
              :source_type "local"
              :canonical_url "file:///tmp/alida-crawl-throws.html"
              :path "/tmp/alida-crawl-throws.html"
              :content_type "text/html"}
        result (with-redefs [source/discover (fn [_ _] [item])
                             source/fetch (fn [_ _ _]
                                            (throw (ex-info "fetch exploded"
                                                            {:type :test/fetch-exploded})))]
                 (crawl/process-source
                  {}
                  index-cfg
                  {:id "fixtures"
                   :type "local"}))]
    (is (= 1 (:discovered_count result)))
    (is (= 0 (:document_count result)))
    (is (= 1 (:error_count result)))
    (is (= :alida.crawl/exception (-> result :errors first :type)))
    (is (= "fetch exploded" (-> result :errors first :message)))
    (is (= :test/fetch-exploded (-> result :errors first :data :type)))))

(deftest process-source-uses-bounded-parallelism
  (let [items (mapv (fn [i]
                      {:source_id "fixtures"
                       :source_type "local"
                       :canonical_url (str "https://example.test/" i)
                       :content_type "text/html"})
                    (range 6))
        active (atom 0)
        max-active (atom 0)
        latch (java.util.concurrent.CountDownLatch. 2)]
    (with-redefs [source/discover (fn [_ _] items)
                  source/fetch (fn [_ _ item]
                                 (let [current (swap! active inc)]
                                   (swap! max-active max current)
                                   (.countDown latch)
                                   (.await latch 1 TimeUnit/SECONDS)
                                   (swap! active dec))
                                 (assoc item
                                        :body "<html lang=\"en\"><body><h1>Hello</h1><p>This document can be processed.</p></body></html>"))]
      (let [result (crawl/process-source
                    {}
                    index-cfg
                    {:id "fixtures"
                     :type "local"
                     :max_concurrency 2})]
        (is (= 6 (:document_count result)))
        (is (= 2 @max-active))
        (is (= 2 (get-in result [:crawl_stats :max_concurrency])))
        (is (nat-int? (get-in result [:crawl_stats :fetch_duration_ms])))))))

(deftest request-gate-waits-until-delay-after-previous-start
  (let [current-ms (atom 1000)
        waits (atom [])
        gate (#'crawl/request-gate
              {:alida/clock-ms #(deref current-ms)
               :alida/wait-on-lock (fn [_ millis]
                                     (swap! waits conj millis)
                                     (swap! current-ms + millis))}
              100)]
    (gate)
    (swap! current-ms + 25)
    (gate)
    (swap! current-ms + 10)
    (gate)
    (is (= [75 90] @waits))))

(deftest process-source-gates-fetches-with-inter-request-delay
  (let [items (mapv (fn [i]
                      {:source_id "fixtures"
                       :source_type "local"
                       :canonical_url (str "https://example.test/" i)
                       :content_type "text/html"})
                    (range 3))
        current-ms (atom 1000)
        waits (atom [])
        events (atom [])]
    (with-redefs [source/discover (fn [_ _] items)
                  source/fetch (fn [_ _ item]
                                 (swap! events conj [:fetch (:canonical_url item) @current-ms])
                                 (assoc item
                                        :body "<html lang=\"en\"><body><h1>Hello</h1><p>This document can be processed.</p></body></html>"))]
      (let [result (crawl/process-source
                    {:alida/clock-ms #(deref current-ms)
                     :alida/wait-on-lock (fn [_ millis]
                                           (swap! waits conj millis)
                                           (swap! current-ms + millis))}
                    index-cfg
                    {:id "fixtures"
                     :type "local"
                     :max_concurrency 2
                     :inter_request_delay_ms 25})]
        (is (= 3 (:document_count result)))
        (is (= [25 25] @waits))
        (is (= [1000 1025 1050] (sort (mapv #(nth % 2) @events))))
        (is (= 25 (get-in result [:crawl_stats :inter_request_delay_ms])))))))

(deftest process-source-gates-fetches-per-hostname
  (let [items [{:source_id "fixtures"
                :source_type "local"
                :canonical_url "https://a.example.test/1"
                :content_type "text/html"}
               {:source_id "fixtures"
                :source_type "local"
                :canonical_url "https://b.example.test/1"
                :content_type "text/html"}
               {:source_id "fixtures"
                :source_type "local"
                :canonical_url "https://a.example.test/2"
                :content_type "text/html"}
               {:source_id "fixtures"
                :source_type "local"
                :canonical_url "https://b.example.test/2"
                :content_type "text/html"}]
        current-ms (atom 1000)
        waits (atom [])
        events (atom [])]
    (with-redefs [source/discover (fn [_ _] items)
                  source/fetch (fn [_ _ item]
                                 (swap! events conj [(:canonical_url item) @current-ms])
                                 (assoc item
                                        :body "<html lang=\"en\"><body><h1>Hello</h1><p>This document can be processed.</p></body></html>"))]
      (let [result (crawl/process-source
                    {:alida/clock-ms #(deref current-ms)
                     :alida/wait-on-lock (fn [_ millis]
                                           (swap! waits conj millis)
                                           (swap! current-ms + millis))}
                    index-cfg
                    {:id "fixtures"
                     :type "local"
                     :max_concurrency 1
                     :inter_request_delay_ms 25})]
        (is (= 4 (:document_count result)))
        (is (= [25] @waits))
        (is (= [["https://a.example.test/1" 1000]
                ["https://b.example.test/1" 1000]
                ["https://a.example.test/2" 1025]
                ["https://b.example.test/2" 1025]]
               @events))))))

(deftest parallel-process-source-subprocess-exits
  (let [expr "(require '[alida.crawl :as crawl] '[alida.source :as source])
              (let [item {:source_id \"fixtures\"
                          :source_type \"local\"
                          :canonical_url \"https://example.test/1\"
                          :content_type \"text/html\"}]
                (with-redefs [source/discover (fn [_ _] [item item])
                              source/fetch (fn [_ _ item]
                                             (assoc item
                                                    :body \"<html lang=\\\"en\\\"><body><h1>Hello</h1><p>This document can be processed.</p></body></html>\"))]
                  (crawl/process-source
                   {}
                   {:name \"docs\"
                    :languages {:allowed [\"en\"] :fallback \"en\"}
                    :chunking {:max_tokens 24}}
                   {:id \"fixtures\"
                    :type \"local\"
                    :max_concurrency 2})
                  (shutdown-agents)
                  (println \"done\")))"
        process (-> (ProcessBuilder. ["clojure" "-M" "-e" expr])
                    (doto
                      (.directory (java.io.File. (System/getProperty "user.dir")))
                      (.redirectErrorStream true))
                    .start)
        exited? (.waitFor process 8 TimeUnit/SECONDS)
        output (slurp (.getInputStream process))]
    (when-not exited?
      (.destroyForcibly process))
    (is exited? output)
    (when exited?
      (is (zero? (.exitValue process)) output)
      (is (str/includes? output "done") output))))

(deftest crawl-continues-with-other-indexes-after-one-index-fails
  (let [sys {:alida/config {:indexes [{:name "broken"} {:name "ok"}]}}
        reconciled? (atom false)]
    (with-redefs [db/reconcile-orphaned-runs! (fn [_] (reset! reconciled? true))
                  crawl/crawl-index! (fn [_ _ index-cfg]
                                       (if (= "broken" (:name index-cfg))
                                         (throw (ex-info "boom" {:reason :test}))
                                         {:run_id #uuid "018c9099-041d-7f5b-9b65-5b8f08f8e61d"
                                          :index_name (:name index-cfg)}))]
      (let [result (crawl/crawl! sys :ignored {})]
        (is @reconciled?)
        (is (= ["ok"] (mapv :index_name (:succeeded result))))
        (is (= ["broken"] (mapv :index_name (:failed result))))
        (is (= "boom" (-> result :failed first :message)))))))
