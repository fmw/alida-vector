(ns alida.verify-test
  (:require [alida.verify :as verify]
            [clojure.test :refer [deftest is]]))

(deftest deterministic-gate-passes-when-thresholds-are-not-exceeded
  (is (= {:deterministic_verdict "pass"
          :deterministic_findings []}
         (verify/deterministic-gate
          {:deterministic_thresholds {:max_removed_absolute 5
                                      :max_removed_percentage 0.5
                                      :max_changed_percentage 0.5
                                      :max_item_failure_percentage 0.5
                                      :max_empty_or_short_document_percentage 0.5}}
          {:document_count 10
           :error_count 1
           :empty_or_short_document_count 1}
          {:summary {:previous_document_count 10
                     :current_document_count 10
                     :removed_count 1
                     :changed_count 1}}))))

(deftest deterministic-gate-cautions-when-thresholds-are-exceeded
  (let [result (verify/deterministic-gate
                {:deterministic_thresholds {:max_removed_absolute 1
                                            :max_removed_percentage 0.1
                                            :max_changed_percentage 0.2
                                            :max_item_failure_percentage 0.1
                                            :max_empty_or_short_document_percentage 0.1}}
                {:document_count 8
                 :error_count 2
                 :empty_or_short_document_count 2}
                {:summary {:previous_document_count 10
                           :current_document_count 8
                           :removed_count 2
                           :changed_count 3}})]
    (is (= "caution" (:deterministic_verdict result)))
    (is (= #{:max_removed_absolute
             :max_removed_percentage
             :max_changed_percentage
             :max_item_failure_percentage
             :max_empty_or_short_document_percentage}
           (set (map :check (:deterministic_findings result)))))))

(deftest deterministic-gate-skips-delta-percentages-on-first-run
  (let [result (verify/deterministic-gate
                {:deterministic_thresholds {:max_removed_percentage 0.0
                                            :max_changed_percentage 0.0}}
                {:document_count 10
                 :error_count 0}
                {:summary {:previous_document_count 0
                           :current_document_count 10
                           :removed_count 0
                           :changed_count 10}})]
    (is (= "pass" (:deterministic_verdict result)))
    (is (= [] (:deterministic_findings result)))))
