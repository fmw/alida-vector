(ns alida.cli-test
  (:require [alida.cli :as cli]
            [alida.crawl :as crawl]
            [alida.db.postgres :as db]
            [alida.search :as search]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [integrant.core :as ig])
  (:import [java.time Instant]))

(def test-system
  {:alida/config {:database {:jdbc_url "jdbc:postgresql://example/alida"}}})

(defn- with-system-stub
  [f]
  (with-redefs [ig/init (fn [_] test-system)
                ig/halt! (fn [_] nil)]
    (f)))

(deftest help-does-not-require-config
  (let [result (cli/run ["help"])]
    (is (= 0 (:exit-code result)))
    (is (str/includes? (:message result) "alida-vector <command>"))))

(deftest unknown-command-reports-usage
  (let [result (cli/run ["wat"])]
    (is (= 2 (:exit-code result)))
    (is (str/includes? (:message result) "Unknown command: wat"))
    (is (str/includes? (:message result) "Commands:"))))

(deftest missing-required-argument-is-reported
  (with-system-stub
    (fn []
      (let [result (cli/run ["activate" "--config" "ignored.yml"])]
        (is (= 1 (:exit-code result)))
        (is (= "Missing required argument: run-id" (:message result)))))))

(deftest runs-command-formats-empty-result
  (with-system-stub
    (fn []
      (with-redefs [db/datasource (fn [_]
                                    (reify java.io.Closeable
                                      (close [_] nil)))
                    db/list-runs (fn [_ opts]
                                   (is (= {:index_name "docs" :limit 5} opts))
                                   [])]
        (let [result (cli/run ["runs" "--config" "ignored.yml" "--index" "docs" "--limit" "5"])]
          (is (= 0 (:exit-code result)))
          (is (= "No runs found." (:message result))))))))

(deftest runs-command-formats-run-rows
  (with-system-stub
    (fn []
      (with-redefs [db/datasource (fn [_]
                                    (reify java.io.Closeable
                                      (close [_] nil)))
                    db/list-runs (fn [_ _]
                                   [{:id #uuid "018c9099-041d-7f5b-9b65-5b8f08f8e61d"
                                     :index_name "docs"
                                     :lifecycle_status "activated"
                                     :verification_verdict "pass"
                                     :started_at #inst "2026-06-08T12:00:00.000-00:00"}])]
        (let [result (cli/run ["runs" "--config" "ignored.yml"])]
          (is (= 0 (:exit-code result)))
          (is (str/includes? (:message result) "018c9099-041d-7f5b-9b65-5b8f08f8e61d"))
          (is (str/includes? (:message result) "docs"))
          (is (str/includes? (:message result) "activated"))
          (is (str/includes? (:message result) "pass")))))))

(deftest lifecycle-commands-call-db-layer
  (testing "activate"
    (with-system-stub
      (fn []
        (with-redefs [db/datasource (fn [_]
                                      (reify java.io.Closeable
                                        (close [_] nil)))
                      db/activate-run! (fn [_ run-id opts]
                                         (is (= "018c9099-041d-7f5b-9b65-5b8f08f8e61d" run-id))
                                         (is (= {:allow-caution? nil} opts))
                                         {:id (parse-uuid run-id)})]
          (let [result (cli/run ["activate" "018c9099-041d-7f5b-9b65-5b8f08f8e61d"])]
            (is (= 0 (:exit-code result)))
            (is (= "Activated run 018c9099-041d-7f5b-9b65-5b8f08f8e61d." (:message result))))))))
  (testing "activate with caution override"
    (with-system-stub
      (fn []
        (with-redefs [db/datasource (fn [_]
                                      (reify java.io.Closeable
                                        (close [_] nil)))
                      db/activate-run! (fn [_ run-id opts]
                                         (is (= {:allow-caution? true} opts))
                                         {:id (parse-uuid run-id)})]
          (let [result (cli/run ["activate" "018c9099-041d-7f5b-9b65-5b8f08f8e61d" "--allow-caution"])]
            (is (= 0 (:exit-code result)))
            (is (= "Activated run 018c9099-041d-7f5b-9b65-5b8f08f8e61d." (:message result))))))))
  (testing "reject"
    (with-system-stub
      (fn []
        (with-redefs [db/datasource (fn [_]
                                      (reify java.io.Closeable
                                        (close [_] nil)))
                      db/reject-run! (fn [_ run-id]
                                       {:id (parse-uuid run-id)})]
          (let [result (cli/run ["reject" "018c9099-041d-7f5b-9b65-5b8f08f8e61d"])]
            (is (= 0 (:exit-code result)))
            (is (= "Rejected run 018c9099-041d-7f5b-9b65-5b8f08f8e61d." (:message result))))))))
  (testing "rollback"
    (with-system-stub
      (fn []
        (with-redefs [db/datasource (fn [_]
                                      (reify java.io.Closeable
                                        (close [_] nil)))
                      db/rollback-index! (fn [_ index-name]
                                           (is (= "docs" index-name)))]
          (let [result (cli/run ["rollback" "docs"])]
            (is (= 0 (:exit-code result)))
            (is (= "Rolled back index docs." (:message result)))))))))

(deftest report-command-prints-stored-report
  (with-system-stub
    (fn []
      (with-redefs [db/datasource (fn [_]
                                    (reify java.io.Closeable
                                      (close [_] nil)))
                    db/get-report (fn [_ run-id]
                                    (is (= "018c9099-041d-7f5b-9b65-5b8f08f8e61d" run-id))
                                    {:full_report "Run report"})]
        (let [result (cli/run ["report" "018c9099-041d-7f5b-9b65-5b8f08f8e61d"])]
          (is (= 0 (:exit-code result)))
          (is (= "Run report" (:message result))))))))

(deftest report-command-handles-missing-report
  (with-system-stub
    (fn []
      (with-redefs [db/datasource (fn [_]
                                    (reify java.io.Closeable
                                      (close [_] nil)))
                    db/get-report (fn [_ _] nil)]
        (let [result (cli/run ["report" "018c9099-041d-7f5b-9b65-5b8f08f8e61d"])]
          (is (= 1 (:exit-code result)))
          (is (= "No report found for run 018c9099-041d-7f5b-9b65-5b8f08f8e61d."
                 (:message result))))))))

(deftest search-command-prints-live-results
  (with-system-stub
    (fn []
      (with-redefs [db/datasource (fn [_]
                                    (reify java.io.Closeable
                                      (close [_] nil)))
                    search/search-live (fn [sys _ query opts]
                                         (is (= test-system sys))
                                         (is (= "vacation balance" query))
                                         (is (= {:index-name "docs" :limit 3} opts))
                                         [{:score 0.87654
                                           :index_name "docs"
                                           :source_id "website"
                                           :canonical_url "https://example.test/docs"
                                           :title "Docs"
                                           :locale "en"
                                           :content "A matching document"}])]
        (let [result (cli/run ["search" "vacation" "balance" "--config" "ignored.yml" "--index" "docs" "--limit" "3"])]
          (is (= 0 (:exit-code result)))
          (is (str/includes? (:message result) "0.8765"))
          (is (str/includes? (:message result) "https://example.test/docs"))
          (is (str/includes? (:message result) "A matching document")))))))

(deftest search-run-command-prints-run-results
  (with-system-stub
    (fn []
      (with-redefs [db/datasource (fn [_]
                                    (reify java.io.Closeable
                                      (close [_] nil)))
                    search/search-run (fn [_ _ run-id query opts]
                                        (is (= "018c9099-041d-7f5b-9b65-5b8f08f8e61d" run-id))
                                        (is (= "support article" query))
                                        (is (= {:limit nil} opts))
                                        [])]
        (let [result (cli/run ["search-run" "018c9099-041d-7f5b-9b65-5b8f08f8e61d" "support" "article"])]
          (is (= 0 (:exit-code result)))
          (is (= "No results found." (:message result))))))))

(deftest prune-command-calls-db-layer
  (with-system-stub
    (fn []
      (with-redefs [db/datasource (fn [_]
                                    (reify java.io.Closeable
                                      (close [_] nil)))
                    db/prune-runs! (fn [_ opts]
                                     (is (= 2 (:keep-last opts)))
                                     (is (instance? Instant (:older-than opts)))
                                     (is (nil? (:disabled-embeddings opts)))
                                     (is (= ["docs"] (:index-names opts)))
                                     {:pruned [{:id #uuid "018c9099-041d-7f5b-9b65-5b8f08f8e61d"
                                                :index_name "docs"
                                                :lifecycle_status "error"
                                                :partition "alida_chunks_1536_run_018c9099041d7f5b9b655b8f08f8e61d"}]})]
        (let [result (cli/run ["prune"
                               "--config" "ignored.yml"
                               "--index" "docs"
                               "--keep-last" "2"
                               "--older-than" "30d"])]
          (is (= 0 (:exit-code result)))
          (is (str/includes? (:message result) "Pruned 1 runs."))
          (is (str/includes? (:message result) "018c9099-041d-7f5b-9b65-5b8f08f8e61d")))))))

(deftest prune-disabled-embeddings-command-calls-db-layer
  (with-system-stub
    (fn []
      (with-redefs [db/datasource (fn [_]
                                    (reify java.io.Closeable
                                      (close [_] nil)))
                    db/prune-runs! (fn [_ opts]
                                     (is (nil? (:keep-last opts)))
                                     (is (nil? (:older-than opts)))
                                     (is (true? (:disabled-embeddings opts)))
                                     {:pruned []})]
        (let [result (cli/run ["prune" "--config" "ignored.yml" "--disabled-embeddings"])]
          (is (= 0 (:exit-code result)))
          (is (= "Pruned 0 runs." (:message result))))))))

(deftest migrate-command-calls-db-layer
  (with-system-stub
    (fn []
      (with-redefs [db/migrate! (fn [config]
                                  (is (= (:alida/config test-system) config)))]
        (let [result (cli/run ["migrate" "--config" "ignored.yml"])]
          (is (= 0 (:exit-code result)))
          (is (= "Migrations complete." (:message result))))))))

(deftest crawl-command-runs-candidate-crawl
  (with-system-stub
    (fn []
      (with-redefs [db/datasource (fn [_]
                                    (reify java.io.Closeable
                                      (close [_] nil)))
                    crawl/crawl! (fn [sys _ opts]
                                   (is (= test-system sys))
                                   (is (= {:index-name "docs"} opts))
                                   {:succeeded [{:run_id #uuid "018c9099-041d-7f5b-9b65-5b8f08f8e61d"
                                                 :index_name "docs"
                                                 :document_count 2
                                                 :chunk_count 3
                                                 :error_count 0
                                                 :skipped_count 1
                                                 :verification_verdict nil
                                                 :notification {:sent true}}]
                                    :failed []
                                    :pruning {:pruned_count 2
                                              :max_age_days 30}})]
        (let [result (cli/run ["crawl" "--config" "ignored.yml" "--index" "docs"])]
          (is (= 0 (:exit-code result)))
          (is (str/includes? (:message result) "1 succeeded, 0 failed"))
          (is (str/includes? (:message result) "docs"))
          (is (str/includes? (:message result) "skipped=1"))
          (is (str/includes? (:message result) "verdict=-"))
          (is (str/includes? (:message result) "notification=sent"))
          (is (str/includes? (:message result)
                             "History pruning removed 2 runs older than 30 days.")))))))

(deftest crawl-command-exits-nonzero-when-any-index-fails
  (with-system-stub
    (fn []
      (with-redefs [db/datasource (fn [_]
                                    (reify java.io.Closeable
                                      (close [_] nil)))
                    crawl/crawl! (fn [_ _ _]
                                   {:succeeded []
                                    :failed [{:index_name "docs"
                                              :message "boom"
                                              :data {:notification {:status 500}}}]})]
        (let [result (cli/run ["crawl" "--config" "ignored.yml"])]
          (is (= 1 (:exit-code result)))
          (is (str/includes? (:message result) "0 succeeded, 1 failed"))
          (is (str/includes? (:message result) "docs  failed: boom"))
          (is (str/includes? (:message result) "notification=failed(status=500)")))))))

(deftest crawl-command-preserves-summary-when-automatic-pruning-fails
  (with-system-stub
    (fn []
      (with-redefs [db/datasource (fn [_]
                                    (reify java.io.Closeable
                                      (close [_] nil)))
                    crawl/crawl! (fn [_ _ _]
                                   {:succeeded [{:run_id #uuid "018c9099-041d-7f5b-9b65-5b8f08f8e61d"
                                                 :index_name "docs"
                                                 :document_count 2
                                                 :chunk_count 3
                                                 :error_count 0
                                                 :notification {:sent true}}]
                                    :failed []
                                    :pruning {:failed true
                                              :message "database unavailable"
                                              :max_age_days 30}})]
        (let [result (cli/run ["crawl" "--config" "ignored.yml"])]
          (is (= 1 (:exit-code result)))
          (is (str/includes? (:message result) "1 succeeded, 0 failed"))
          (is (str/includes? (:message result) "018c9099-041d-7f5b-9b65-5b8f08f8e61d"))
          (is (str/includes? (:message result)
                             "History pruning failed after successful crawls: database unavailable")))))))

(deftest crawl-command-distinguishes-retryable-failures
  (testing "all failures are retryable"
    (with-system-stub
      (fn []
        (with-redefs [db/datasource (fn [_]
                                      (reify java.io.Closeable
                                        (close [_] nil)))
                      crawl/crawl! (fn [_ _ _]
                                     {:succeeded []
                                      :failed [{:index_name "docs"
                                                :message "rate limited"
                                                :data {:status 429
                                                       :retryable true}}]})]
          (let [result (cli/run ["crawl" "--config" "ignored.yml"])]
            (is (= 75 (:exit-code result))))))))

  (testing "a permanent failure takes precedence over retryable failures"
    (with-system-stub
      (fn []
        (with-redefs [db/datasource (fn [_]
                                      (reify java.io.Closeable
                                        (close [_] nil)))
                      crawl/crawl! (fn [_ _ _]
                                     {:succeeded []
                                      :failed [{:index_name "temporary"
                                                :message "rate limited"
                                                :data {:retryable true}}
                                               {:index_name "permanent"
                                                :message "invalid configuration"
                                                :data {:retryable false}}]})]
          (let [result (cli/run ["crawl" "--config" "ignored.yml"])]
            (is (= 1 (:exit-code result)))))))))

(deftest prune-command-requires-explicit-criteria
  (with-system-stub
    (fn []
      (with-redefs [db/datasource (fn [_]
                                    (reify java.io.Closeable
                                      (close [_] nil)))]
        (let [result (cli/run ["prune" "--config" "ignored.yml"])]
          (is (= 1 (:exit-code result)))
          (is (= "Prune requires --keep-last, --older-than, or --disabled-embeddings" (:message result))))))))
