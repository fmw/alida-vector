(ns alida.retry-test
  (:require [alida.retry :as retry]
            [clojure.test :refer [deftest is testing]]))

(deftest retry-after-parsing-is-overflow-safe
  (testing "large integral seconds saturate instead of throwing"
    (is (= Long/MAX_VALUE
           (retry/retry-after-ms
            {"Retry-After" "9999999999999999"}))))
  (testing "integral values outside a long are treated as invalid"
    (is (nil? (retry/retry-after-ms
               {"Retry-After" "999999999999999999999999999999999999"}))))
  (testing "distant HTTP dates remain parseable and are capped by the caller"
    (is (> (retry/retry-after-ms
            {"Retry-After" "Thu, 01 Jan 2099 00:00:00 GMT"})
           retry/default-retry-max-delay-ms))))

(deftest retry-delays-are-bounded
  (let [sleeps (atom [])
        attempts (atom 0)
        error (try
                (retry/with-retries
                 {:alida/sleep #(swap! sleeps conj %)
                  :alida/random-int (constantly 50)}
                 {:max_retries 3
                  :retry_initial_ms 10
                  :retry_jitter_ms 50
                  :retry_max_delay_ms 100
                  :operation :test}
                 (fn []
                   (swap! attempts inc)
                   (throw (ex-info "temporarily unavailable"
                                   {:retryable true
                                    :retry-after-ms Long/MAX_VALUE}))))
                nil
                (catch clojure.lang.ExceptionInfo e e))]
    (is (= [100 100] @sleeps))
    (is (= 3 @attempts))
    (is (true? (:retryable (ex-data error))))
    (is (true? (:retry-exhausted (ex-data error))))))

(deftest retry-delay-uses-a-bounded-default
  (let [sleeps (atom [])
        attempts (atom 0)]
    (try
      (retry/with-retries
       {:alida/sleep #(swap! sleeps conj %)}
       {:max_retries 2
        :retry_initial_ms 10
        :retry_jitter_ms 0
        :operation :test}
       (fn []
         (swap! attempts inc)
         (throw (ex-info "temporarily unavailable"
                         {:retryable true
                          :retry-after-ms Long/MAX_VALUE}))))
      (catch clojure.lang.ExceptionInfo _))
    (is (= [retry/default-retry-max-delay-ms] @sleeps))
    (is (= 2 @attempts))))
