(ns alida.cli-test
  (:require [alida.cli :as cli]
            [alida.crawl :as crawl]
            [alida.db.postgres :as db]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [integrant.core :as ig]))

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
                                                 :verification_verdict nil}]
                                    :failed []})]
        (let [result (cli/run ["crawl" "--config" "ignored.yml" "--index" "docs"])]
          (is (= 0 (:exit-code result)))
          (is (str/includes? (:message result) "1 succeeded, 0 failed"))
          (is (str/includes? (:message result) "docs"))
          (is (str/includes? (:message result) "verdict=-")))))))

(deftest crawl-command-exits-nonzero-when-any-index-fails
  (with-system-stub
    (fn []
      (with-redefs [db/datasource (fn [_]
                                    (reify java.io.Closeable
                                      (close [_] nil)))
                    crawl/crawl! (fn [_ _ _]
                                   {:succeeded []
                                    :failed [{:index_name "docs"
                                              :message "boom"}]})]
        (let [result (cli/run ["crawl" "--config" "ignored.yml"])]
          (is (= 1 (:exit-code result)))
          (is (str/includes? (:message result) "0 succeeded, 1 failed"))
          (is (str/includes? (:message result) "docs  failed: boom")))))))

(deftest stubbed-commands-validate-arguments-before-returning-not-implemented
  (with-system-stub
    (fn []
      (let [report-result (cli/run ["report" "018c9099-041d-7f5b-9b65-5b8f08f8e61d"])
            search-result (cli/run ["search" "hello"])
            search-run-result (cli/run ["search-run" "018c9099-041d-7f5b-9b65-5b8f08f8e61d" "hello"])]
        (is (= 2 (:exit-code report-result)))
        (is (= "Command 'report' is wired but not implemented yet." (:message report-result)))
        (is (= 2 (:exit-code search-result)))
        (is (= "Command 'search' is wired but not implemented yet." (:message search-result)))
        (is (= 2 (:exit-code search-run-result)))
        (is (= "Command 'search-run' is wired but not implemented yet." (:message search-run-result)))))))
