(ns alida.report-test
  (:require [alida.report :as report]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]))

(def summary
  {:run_id #uuid "018c9099-041d-7f5b-9b65-5b8f08f8e61d"
   :index_name "docs"
   :lifecycle_status "complete"
   :verification_verdict nil
   :deterministic_verification
   {:deterministic_verdict "caution"
    :deterministic_findings [{:check :max_removed_percentage
                              :verdict "caution"
                              :message "removed percentage exceeded"}]}
   :diff {:previous_run_id #uuid "018c9099-041d-7f5b-9b65-5b8f08f8e61c"
          :summary {:added_count 1
                    :removed_count 1
                    :changed_count 1
                    :moved_count 1}
          :added_urls [{:source_id "website"
                        :canonical_url "https://example.test/added"}]
          :removed_urls [{:source_id "website"
                          :canonical_url "https://example.test/removed"}]
          :changed_urls [{:source_id "website"
                          :canonical_url "https://example.test/changed"
                          :previous_normalized_content_hash "old"
                          :current_normalized_content_hash "new"}]
          :moved_urls [{:source_id "website"
                        :previous_canonical_url "https://example.test/old"
                        :current_canonical_url "https://example.test/new"}]}
   :source_count 1
   :document_count 2
   :chunk_count 3
   :error_count 1
   :embedding_stats {:reused_chunk_count 2
                     :embedded_chunk_count 1
                     :embedding_request_count 1
                     :reuse_lookup_duration_ms 4
                     :provider_duration_ms 50}
   :phase_stats {:crawl_duration_ms 100
                 :fetch_duration_ms 40
                 :extract_duration_ms 20
                 :language_duration_ms 5
                 :chunk_duration_ms 10
                 :embedding_duration_ms 60
                 :persist_duration_ms 7}
   :sources [{:source_cfg {:id "website"
                           :type "website"}
              :document_count 2
              :chunk_count 3
              :error_count 1
              :crawl_stats {:fetch_duration_ms 40
                            :extract_duration_ms 20
                            :chunk_duration_ms 10}
              :embedding_stats {:reused_chunk_count 2
                                :embedded_chunk_count 1}}]})

(deftest builds-slack-summary
  (let [built (report/build summary)]
    (is (str/includes? (:slack_summary built) "docs run 018c9099-041d-7f5b-9b65-5b8f08f8e61d"))
    (is (str/includes? (:slack_summary built) "documents=2"))
    (is (str/includes? (:slack_summary built) "added=1"))
    (is (str/includes? (:slack_summary built) "deterministic=caution"))
    (is (str/includes? (:slack_summary built) "verdict=-"))))

(deftest builds-slack-blocks
  (let [blocks (:slack_blocks (report/build (assoc summary
                                                   :verification_verdict "caution"
                                                   :verification {:llm_verdict "caution"})))]
    (is (= "header" (:type (first blocks))))
    (is (= "⚠️ Alida Vector crawl needs review"
           (get-in (first blocks) [:text :text])))
    (is (some #(str/includes? % "Verdict")
              (mapcat (fn [block] (map :text (:fields block))) blocks)))
    (is (some #(str/includes? % "Changes")
              (mapcat (fn [block] (map :text (:fields block))) blocks)))
    (is (some #(str/includes? % "Actual changes")
              (keep #(get-in % [:text :text]) blocks)))
    (is (some #(str/includes? % "https://example.test/added")
              (keep #(get-in % [:text :text]) blocks)))
    (is (some #(str/includes? % "https://example.test/old")
              (keep #(get-in % [:text :text]) blocks)))
    (is (some #(str/includes? % "activate")
              (keep #(get-in % [:text :text]) blocks)))))

(deftest slack-blocks-truncate-change-lists
  (let [many-added (mapv (fn [n]
                           {:source_id "website"
                            :canonical_url (str "https://example.test/added/" n)})
                         (range 5))
        blocks (:slack_blocks (report/build (assoc-in summary
                                                       [:diff :added_urls]
                                                       many-added)))
        text (str/join "\n" (keep #(get-in % [:text :text]) blocks))]
    (is (str/includes? text "https://example.test/added/0"))
    (is (str/includes? text "https://example.test/added/2"))
    (is (not (str/includes? text "https://example.test/added/3")))
    (is (str/includes? text "2 more in the full report"))))

(deftest builds-full-report
  (let [full-report (:full_report (report/build summary))]
    (is (str/includes? full-report "Run: 018c9099-041d-7f5b-9b65-5b8f08f8e61d"))
    (is (str/includes? full-report "Diff"))
    (is (str/includes? full-report "Deterministic Gate"))
    (is (str/includes? full-report "max_removed_percentage caution: removed percentage exceeded"))
    (is (str/includes? full-report "Added URLs"))
    (is (str/includes? full-report "https://example.test/changed old -> new"))
    (is (str/includes? full-report "Timings"))
    (is (str/includes? full-report "Embedding"))
    (is (str/includes? full-report "- website (website): documents=2"))))
