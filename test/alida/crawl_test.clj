(ns alida.crawl-test
  (:require [alida.attestation :as attestation]
            [alida.crawl :as crawl]
            [alida.db.postgres :as db]
            [alida.source :as source]
            [alida.source.local]
            [alida.verify :as verify]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]])
  (:import [java.time Duration Instant]
           [java.util.concurrent TimeUnit]))

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

(deftest process-source-records-empty-extracted-pages-as-skipped-items
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
    (is (= 0 (:error_count result)))
    (is (= 1 (:skipped_count result)))
    (is (= 1 (:empty_or_short_document_count result)))
    (is (= :alida.crawl/empty-document (-> result :skipped first :type)))
    (is (= "en" (-> result :skipped first :locale)))))

(deftest process-source-records-skipped-discovered-items-without-errors
  (let [item (source/skipped {:type :example/not-found
                              :canonical-url "https://example.test/missing"})
        result (with-redefs [source/discover (fn [_ _] [item])]
                 (crawl/process-source
                  {}
                  index-cfg
                  {:id "fixtures"
                   :type "test"}))]
    (is (= 1 (:discovered_count result)))
    (is (= 0 (:document_count result)))
    (is (= 0 (:chunk_count result)))
    (is (= 0 (:error_count result)))
    (is (= 1 (:skipped_count result)))
    (is (= :example/not-found (-> result :skipped first :type)))))

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
  (let [sys {:alida/config {:retention {:max_age_days 30}
                            :indexes [{:name "broken"} {:name "ok"}]}}
        reconciled? (atom false)
        pruned? (atom false)]
    (with-redefs [db/reconcile-orphaned-runs! (fn [_] (reset! reconciled? true))
                  db/prune-runs! (fn [_ _] (reset! pruned? true))
                  crawl/crawl-index! (fn [_ _ index-cfg]
                                       (if (= "broken" (:name index-cfg))
                                         (throw (ex-info "boom" {:reason :test}))
                                         {:run_id #uuid "018c9099-041d-7f5b-9b65-5b8f08f8e61d"
                                          :index_name (:name index-cfg)}))]
      (let [result (crawl/crawl! sys :ignored {})]
        (is @reconciled?)
        (is (false? @pruned?))
        (is (= ["ok"] (mapv :index_name (:succeeded result))))
        (is (= ["broken"] (mapv :index_name (:failed result))))
        (is (= "boom" (-> result :failed first :message)))
        (is (= {:skipped true
                :reason :crawl-failed
                :max_age_days 30}
               (:pruning result)))))))

(deftest crawl-prunes-selected-index-history-after-success
  (let [sys {:alida/config {:retention {:max_age_days 30}
                            :indexes [{:name "docs"} {:name "blog"}]}}
        prune-opts (atom nil)
        before (.minus (Instant/now) (Duration/ofDays 30))]
    (with-redefs [db/reconcile-orphaned-runs! (constantly nil)
                  db/prune-runs! (fn [_ opts]
                                   (reset! prune-opts opts)
                                   {:pruned []
                                    :pruned_count 0})
                  crawl/crawl-index! (fn [_ _ index-cfg]
                                       {:run_id #uuid "018c9099-041d-7f5b-9b65-5b8f08f8e61d"
                                        :index_name (:name index-cfg)})]
      (let [result (crawl/crawl! sys :ignored {:index-name "docs"})
            after (.minus (Instant/now) (Duration/ofDays 30))
            cutoff (:older-than @prune-opts)]
        (is (= ["docs"] (:index-names @prune-opts)))
        (is (instance? Instant cutoff))
        (is (not (.isBefore cutoff before)))
        (is (not (.isAfter cutoff after)))
        (is (= {:pruned []
                :pruned_count 0
                :max_age_days 30}
               (:pruning result)))))))

(deftest crawl-does-not-prune-when-retention-is-omitted
  (let [sys {:alida/config {:indexes [{:name "docs"}]}}]
    (with-redefs [db/reconcile-orphaned-runs! (constantly nil)
                  db/prune-runs! (fn [& _]
                                   (throw (ex-info "unexpected pruning" {})))
                  crawl/crawl-index! (fn [_ _ index-cfg]
                                       {:run_id #uuid "018c9099-041d-7f5b-9b65-5b8f08f8e61d"
                                        :index_name (:name index-cfg)})]
      (is (nil? (:pruning (crawl/crawl! sys :ignored {})))))))

(deftest crawl-reports-automatic-pruning-failures-after-success
  (let [sys {:alida/config {:retention {:max_age_days 30}
                            :indexes [{:name "docs"}]}}]
    (with-redefs [db/reconcile-orphaned-runs! (constantly nil)
                  db/prune-runs! (fn [_ _]
                                   (throw (ex-info "database unavailable" {})))
                  crawl/crawl-index! (fn [_ _ index-cfg]
                                       {:run_id #uuid "018c9099-041d-7f5b-9b65-5b8f08f8e61d"
                                        :index_name (:name index-cfg)})]
      (let [result (crawl/crawl! sys :ignored {})]
        (is (= ["docs"] (mapv :index_name (:succeeded result))))
        (is (= []
               (:failed result)))
        (is (= {:failed true
                :message "database unavailable"
                :max_age_days 30}
               (:pruning result)))))))

(deftest verification-persists-run-reference-before-local-attestation
  (let [calls (atom [])
        verification-cfg {:provider "openai"
                          :model "gpt-test"
                          :attestations {:attestor "candidate"}}
        llm-result {:verdict "pass"
                    :reasoning "Looks good."
                    :findings []
                    :security_findings []
                    :raw_response {:id "response"}}]
    (with-redefs [verify/build-prompts (constantly ["prompt"])
                  verify/verification-input-hash (constantly "input-hash")
                  verify/complete-with-retries (fn [& _] llm-result)
                  attestation/find-result (constantly nil)
                  db/save-verification! (fn [_ _ verification]
                                          (swap! calls conj [:verification verification]))
                  attestation/save-result! (fn [_ _ input-hash result]
                                             (swap! calls conj [:attestation input-hash result])
                                             "candidate")]
      (#'crawl/verify-run!
       {:alida/config {:verification verification-cfg}}
       :datasource
       {:id #uuid "018c9099-041d-7f5b-9b65-5b8f08f8e61d"
        :index_name "docs"}
       {}
       {:deterministic_verdict "pass"
        :deterministic_findings []}
       [])
      (is (= [:verification :attestation] (mapv first @calls)))
      (is (= "input-hash" (get-in @calls [0 1 :verification_input_hash])))
      (is (= "candidate" (get-in @calls [0 1 :attestation_attestor])))
      (is (= "input-hash" (get-in @calls [1 1]))))))

(deftest verification-synthesizes-multiple-distinct-review-reasons
  (let [calls (atom [])
        sleeps (atom [])
        batch-results
        (atom [{:verdict "pass"
                :reasoning "No concerns."
                :raw_response {:verdict "pass" :reasoning "No concerns."}}
               {:verdict "caution"
                :reasoning "Review the unexpected redirect."
                :findings [{:url "https://example.test/a"}]
                :raw_response {:verdict "caution"
                               :reasoning "Review the unexpected redirect."
                               :findings [{:url "https://example.test/a"}]}}
               {:verdict "caution"
                :reasoning "An outdated destination appears in another page."
                :security_findings [{:url "https://example.test/b"}]
                :raw_response {:verdict "caution"
                               :reasoning "An outdated destination appears in another page."
                               :security_findings [{:url "https://example.test/b"}]}}
               {:verdict "caution"
                :reasoning "A third page is missing its title."
                :raw_response {:verdict "caution"
                               :reasoning "A third page is missing its title."}}])
        synthesis-result
        {:verdict "caution"
         :reasoning "Three pages require review for destinations or missing metadata."
         :findings []
         :security_findings []
         :raw_response {:verdict "caution"
                        :reasoning "Three pages require review for destinations or missing metadata."}}
        result
        (with-redefs [verify/complete-with-retries
                      (fn [& args]
                        (swap! calls conj args)
                        (if (= 3 (count args))
                          (let [result (first @batch-results)]
                            (swap! batch-results subvec 1)
                            result)
                          synthesis-result))]
          (#'crawl/complete-llm-verification!
           {:alida/sleep #(swap! sleeps conj %)}
           {:inter_prompt_delay_ms 7}
           {:id #uuid "018c9099-041d-7f5b-9b65-5b8f08f8e61d"
            :index_name "docs"}
           ["prompt 1" "prompt 2" "prompt 3" "prompt 4"]))]
    (is (= 5 (count @calls)) "four verification calls plus one prose synthesis call")
    (is (= [7 7 7 7] @sleeps))
    (is (= {:system-prompt verify/prose-summary-system-prompt}
           (nth (last @calls) 2)))
    (is (= "caution" (:verdict result)))
    (is (= (str "4 verification batches reviewed: 1 passed; 3 flagged for review."
                "\n\nReview summary:\n"
                "Three pages require review for destinations or missing metadata.")
           (:reasoning result)))
    (is (= [{:url "https://example.test/a"}] (:findings result)))
    (is (= [{:url "https://example.test/b"}] (:security_findings result)))
    (is (= 4 (count (get-in result [:raw_response :batches]))))
    (is (= (:raw_response synthesis-result)
           (get-in result [:raw_response :prose_summary])))
    (is (= verify/prose-summary-version
           (get-in result [:raw_response :prose_summary_version])))
    (is (str/includes? (get-in result [:raw_response :batch_review_details])
                       "A third page is missing its title."))))

(deftest verification-synthesizes-multiple-passing-change-summaries
  (let [calls (atom [])
        batch-results
        (atom [{:verdict "pass"
                :reasoning "Added English and Dutch setup guides."
                :raw_response {:verdict "pass"
                               :reasoning "Added English and Dutch setup guides."}}
               {:verdict "pass"
                :reasoning "Updated billing terms in three locales."
                :raw_response {:verdict "pass"
                               :reasoning "Updated billing terms in three locales."}}])
        synthesis-result
        {:verdict "pass"
         :reasoning "Added localized setup guides and updated multilingual billing terms."
         :findings []
         :security_findings []
         :raw_response {:verdict "pass"
                        :reasoning (str "Added localized setup guides and updated multilingual "
                                        "billing terms.")}}
        result
        (with-redefs [verify/complete-with-retries
                      (fn [& args]
                        (swap! calls conj args)
                        (if (= 3 (count args))
                          (let [result (first @batch-results)]
                            (swap! batch-results subvec 1)
                            result)
                          synthesis-result))]
          (#'crawl/complete-llm-verification!
           {}
           {}
           {:id #uuid "018c9099-041d-7f5b-9b65-5b8f08f8e61d"
            :index_name "docs"}
           ["prompt 1" "prompt 2"]))]
    (is (= 3 (count @calls)))
    (is (= {:system-prompt verify/prose-summary-system-prompt}
           (nth (last @calls) 2)))
    (is (= "pass" (:verdict result)))
    (is (= (str "All 2 verification batches passed."
                "\n\nChange summary:\n"
                "Added localized setup guides and updated multilingual billing terms.")
           (:reasoning result)))
    (is (verify/prose-summary-current? result))))

(deftest verification-skips-prose-call-for-two-review-reasons
  (let [calls (atom 0)
        batch-results
        (atom [{:verdict "caution"
                :reasoning "Review one redirect."
                :raw_response {:verdict "caution" :reasoning "Review one redirect."}}
               {:verdict "caution"
                :reasoning "Review a missing title."
                :raw_response {:verdict "caution" :reasoning "Review a missing title."}}])
        result
        (with-redefs [verify/complete-with-retries
                      (fn [& _]
                        (swap! calls inc)
                        (let [result (first @batch-results)]
                          (swap! batch-results subvec 1)
                          result))]
          (#'crawl/complete-llm-verification!
           {}
           {}
           {:id #uuid "018c9099-041d-7f5b-9b65-5b8f08f8e61d"
            :index_name "docs"}
           ["prompt 1" "prompt 2"]))]
    (is (= 2 @calls))
    (is (str/includes? (:reasoning result) "Review one redirect."))
    (is (str/includes? (:reasoning result) "Review a missing title."))
    (is (not (contains? (:raw_response result) :prose_summary)))))

(deftest prose-synthesis-failure-falls-back-to-deterministic-reasoning
  (let [calls (atom 0)
        batch-results
        (atom [{:verdict "caution"
                :reasoning "Review redirect A."
                :raw_response {:verdict "caution" :reasoning "Review redirect A."}}
               {:verdict "caution"
                :reasoning "Review redirect B."
                :raw_response {:verdict "caution" :reasoning "Review redirect B."}}
               {:verdict "caution"
                :reasoning "Review redirect C."
                :raw_response {:verdict "caution" :reasoning "Review redirect C."}}])
        result
        (with-redefs [verify/complete-with-retries
                      (fn [& args]
                        (swap! calls inc)
                        (if (= 3 (count args))
                          (let [result (first @batch-results)]
                            (swap! batch-results subvec 1)
                            result)
                          (throw (ex-info "Summary provider unavailable"
                                          {:type :test/summary-unavailable}))))]
          (#'crawl/complete-llm-verification!
           {}
           {}
           {:id #uuid "018c9099-041d-7f5b-9b65-5b8f08f8e61d"
            :index_name "docs"}
           ["prompt 1" "prompt 2" "prompt 3"]))]
    (is (= 4 @calls))
    (is (str/includes? (:reasoning result) "Review redirect A."))
    (is (str/includes? (:reasoning result) "Review redirect B."))
    (is (str/includes? (:reasoning result) "Review redirect C."))
    (is (not (contains? (:raw_response result) :prose_summary)))))

(deftest trusted-attestation-reuse-never-calls-the-provider-for-prose
  (let [results (mapv (fn [reasoning]
                        {:verdict "caution"
                         :reasoning reasoning
                         :raw_response {:verdict "caution"
                                        :reasoning reasoning}})
                      ["Review redirect A."
                       "Review redirect B."
                       "Review redirect C."])
        trusted {:llm-result (verify/combine-batch-results results)
                 :source "trusted:pre-production"
                 :attestor "pre-production"}]
    (with-redefs [verify/verification-input-hash (constantly "input-hash")
                  attestation/find-result (constantly trusted)
                  verify/complete-with-retries
                  (fn [& _]
                    (throw (ex-info "trusted reuse must not call the provider" {})))]
      (let [resolved (#'crawl/resolve-llm-verification!
                      {}
                      :datasource
                      {}
                      {:id #uuid "018c9099-041d-7f5b-9b65-5b8f08f8e61d"
                       :index_name "docs"}
                      ["prompt"])]
        (is (= trusted (dissoc resolved :verification-input-hash)))
        (is (= "input-hash" (:verification-input-hash resolved)))
        (is (not (contains? (get-in resolved [:llm-result :raw_response])
                            :prose_summary)))))))

(deftest local-cache-reuse-tolerates-an-opaque-raw-response
  (let [cached {:llm-result {:verdict "pass"
                             :reasoning "Attested pass."
                             :findings []
                             :security_findings []
                             :raw_response "opaque"}
                :source "cache"
                :attestor "candidate"}]
    (with-redefs [verify/verification-input-hash (constantly "input-hash")
                  attestation/find-result (constantly cached)
                  verify/complete-with-retries
                  (fn [& _]
                    (throw (ex-info "cache reuse must not call the provider" {})))]
      (let [resolved (#'crawl/resolve-llm-verification!
                      {}
                      :datasource
                      {}
                      {:id #uuid "018c9099-041d-7f5b-9b65-5b8f08f8e61d"
                       :index_name "docs"}
                      ["prompt"])]
        (is (= cached (dissoc resolved :verification-input-hash)))
        (is (= "input-hash" (:verification-input-hash resolved)))))))

(deftest verification-documents-forwards-changed-and-added-page-content
  ;; Regression: the in-memory document map has no :source_id (only attached at
  ;; persist time), while the diff entries do. verification-documents must stamp
  ;; the source id from source-cfg so changed/added pages match and their content
  ;; reaches the LLM verifier instead of an empty document list.
  (let [doc (fn [url hash]
              {:document {:canonical_url url
                          :title (str "Page " url)
                          :locale "en"
                          :normalized_content_hash hash}
               :chunks [{:chunk_index 0
                         :chunk_count 1
                         :content (str "Body of " url)
                         :content_hash hash
                         :estimated_tokens 5}]})
        source-results [{:source_cfg {:id "website"}
                         :documents [(doc "https://example.com/changed/" "new-hash")
                                     (doc "https://example.com/unchanged/" "unchanged-hash")]}]
        run-diff {:added_urls []
                  :removed_urls []
                  :moved_urls []
                  :changed_urls [{:source_id "website"
                                  :canonical_url "https://example.com/changed/"
                                  :locale "en"
                                  :normalized_content_hash "new-hash"
                                  :previous_normalized_content_hash "old-hash"
                                  :current_normalized_content_hash "new-hash"}]}
        documents (#'crawl/verification-documents source-results run-diff)]
    (is (= 1 (count documents)) "only the changed page is forwarded")
    (is (= "website" (-> documents first :source_id)))
    (is (= "https://example.com/changed/" (-> documents first :canonical_url)))
    (is (= ["Body of https://example.com/changed/"]
           (mapv :content (-> documents first :chunks)))
        "the changed page's body content is included for the verifier")))

(deftest verification-documents-adds-old-versus-new-content-segments
  (let [url "https://example.com/changed/"
        source-results
        [{:source_cfg {:id "website"}
          :documents [{:document {:canonical_url url
                                  :title "Changed page"
                                  :locale "en"
                                  :normalized_content_hash "new-hash"}
                       :chunks [{:chunk_index 0
                                 :chunk_count 1
                                 :content (str "Shared introduction\n"
                                               "Sixty (60) days\n"
                                               "New utilization dashboard")
                                 :content_hash "new-hash"
                                 :estimated_tokens 10}]}]}]
        run-diff {:added_urls []
                  :removed_urls []
                  :moved_urls []
                  :changed_urls [{:source_id "website"
                                  :canonical_url url
                                  :previous_normalized_content_hash "old-hash"
                                  :current_normalized_content_hash "new-hash"}]}
        previous-chunks [{:chunk_index 0
                          :content (str "Shared introduction\n"
                                        "Thirty (60) days\n"
                                        "Classic utilization dashboards")}]
        documents (#'crawl/verification-documents source-results run-diff)
        changes (#'crawl/content-changes previous-chunks (:chunks (first documents)))
        document (first (#'crawl/attach-content-changes
                         documents
                         {["website" url] changes}))]
    (is (= {:removed_segments ["Thirty (60) days"
                               "Classic utilization dashboards"]
            :added_segments ["Sixty (60) days"
                             "New utilization dashboard"]}
           (:content_changes document)))))

(deftest verification-content-changes-are-bounded
  (let [many-segments (str/join "\n" (map #(str "Old segment " %) (range 101)))
        long-segment (apply str (repeat (+ crawl/max-verification-change-characters 10) "x"))
        changes (#'crawl/content-changes
                 [{:content many-segments}]
                 [{:content long-segment}])]
    (is (= crawl/max-verification-change-segments
           (count (:removed_segments changes))))
    (is (= 1 (:removed_segments_omitted changes)))
    (is (= crawl/max-verification-change-characters
           (count (first (:added_segments changes)))))
    (is (str/ends-with? (first (:added_segments changes)) "..."))
    (is (nil? (:added_segments_omitted changes)))))

(deftest previous-content-changes-loads-changed-documents-in-bounded-batches
  (let [previous-run-id #uuid "018c9099-041d-7f5b-9b65-5b8f08f8e61c"
        calls (atom [])
        urls ["https://example.com/changed/a" "https://example.com/changed/b"]
        documents (mapv (fn [url]
                          {:source_id "website"
                           :canonical_url url
                           :chunks [{:content (str "Current content " url)}]})
                        urls)
        rows-by-key (into {}
                          (map (fn [url]
                                 [["website" url]
                                  {:source_id "website"
                                   :canonical_url url
                                   :chunk_index 0
                                   :content (str "Previous content " url)}]))
                          urls)]
    (with-redefs [db/get-run (fn [ds run-id]
                              (swap! calls conj [:run ds run-id])
                              {:id previous-run-id
                               :embedding_dimensions 1536})
                  crawl/previous-change-document-batch-size 1
                  db/list-document-chunk-content
                  (fn [ds dimensions run-id document-keys]
                    (swap! calls conj [:chunks ds dimensions run-id document-keys])
                    (mapv rows-by-key document-keys))]
      (let [changes (#'crawl/previous-content-changes
                     :ds
                     {:previous_run_id previous-run-id
                      :changed_urls (mapv (fn [url]
                                            {:source_id "website"
                                             :canonical_url url})
                                          urls)
                      :added_urls [{:source_id "website"
                                    :canonical_url "https://example.com/added/"}]}
                     documents)]
        (is (= (set (map (fn [url] ["website" url]) urls))
               (set (keys changes))))
        (is (= ["Previous content https://example.com/changed/a"]
               (get-in changes [["website" (first urls)] :removed_segments])))))
    (is (= [[:run :ds previous-run-id]
            [:chunks :ds 1536 previous-run-id
             [["website" "https://example.com/changed/a"]]]
            [:chunks :ds 1536 previous-run-id
             [["website" "https://example.com/changed/b"]]]]
           @calls))))

(deftest failed-runs-store-bounded-structured-request-context
  (let [run-id #uuid "018c9099-041d-7f5b-9b65-5b8f08f8e61d"
        updates (atom [])
        error (ex-info "Source request failed with HTTP 503"
                       {:type :alida.source/http-error
                        :phase :discovery
                        :source-id "site"
                        :request-method :get
                        :request-url "https://example.test/sitemap.xml"
                        :status 503
                        :retryable true
                        :retry-exhausted true
                        :attempts 3
                        :max-retries 3
                        :body "sensitive response"
                        :headers {"Authorization" "secret"}
                        :response {:body "also sensitive"}})]
    (with-redefs [db/update-run-status!
                  (fn [& args]
                    (swap! updates conj args)
                    {:id run-id})]
      (is (identical? error (#'crawl/fail-run! :ds {:id run-id} error))))
    (is (= [[:ds
             run-id
             "error"
             {:error_summary "Source request failed with HTTP 503"
              :metadata
              {:failure {:type :alida.source/http-error
                         :phase :discovery
                         :source_id "site"
                         :request_method :get
                         :request_url "https://example.test/sitemap.xml"
                         :status 503
                         :retryable true
                         :retry_exhausted true
                         :attempts 3
                         :max_retries 3}}}]]
           @updates))
    (is (not (str/includes? (pr-str @updates) "sensitive")))
    (is (not (str/includes? (pr-str @updates) "Authorization")))))
