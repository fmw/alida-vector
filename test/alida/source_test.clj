(ns alida.source-test
  (:require [alida.source :as source]
            [clojure.test :refer [deftest is]]))

(deftest unsupported-source-types-are-rejected
  (is (thrown-with-msg?
       clojure.lang.ExceptionInfo
       #"Unsupported source type"
       (source/discover {} {:type "unknown"})))
  (is (thrown-with-msg?
       clojure.lang.ExceptionInfo
       #"Unsupported source type"
       (source/fetch {} {:type "unknown"} {}))))

(deftest anomaly-helper-shapes-recoverable-errors
  (is (= {:alida/error {:cognitect.anomalies/category :cognitect.anomalies/not-found
                       :type :example}}
         (source/anomaly :cognitect.anomalies/not-found {:type :example})))
  (is (source/anomaly? (source/anomaly :cognitect.anomalies/fault {})))
  (is (false? (source/anomaly? nil)))
  (is (false? (source/anomaly? "not a map")))
  (is (false? (source/anomaly? (ex-info "boom" {})))))
