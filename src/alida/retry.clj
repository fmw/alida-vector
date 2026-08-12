(ns alida.retry
  (:require [com.brunobonacci.mulog :as u])
  (:import [java.io IOException]
           [java.time Instant ZonedDateTime]
           [java.time.format DateTimeFormatter]))

(def default-retry-max-delay-ms 60000)

(defn sleep!
  [sys millis]
  (if-let [sleep-fn (:alida/sleep sys)]
    (sleep-fn millis)
    (java.util.concurrent.locks.LockSupport/parkNanos (* (long millis) 1000000))))

(defn random-int
  [sys bound]
  (if-let [random-int-fn (:alida/random-int sys)]
    (random-int-fn bound)
    (rand-int bound)))

(defn retryable-status?
  [status]
  (or (= 429 status)
      (<= 500 status 599)))

(defn retryable-exception?
  [e]
  (or (:retryable (ex-data e))
      (instance? IOException e)))

(defn- header-value
  [headers k]
  (or (get headers k)
      (get headers (.toLowerCase k))
      (get headers (.toUpperCase k))))

(defn- parse-long-safe
  [value]
  (try
    (Long/parseLong (str value))
    (catch Exception _ nil)))

(defn- retry-after-seconds-ms
  [value]
  (when-let [seconds (parse-long-safe value)]
    (when-not (neg? seconds)
      (if (> seconds (quot Long/MAX_VALUE 1000))
        Long/MAX_VALUE
        (* seconds 1000)))))

(defn retry-after-ms
  [headers]
  (when-let [value (header-value headers "Retry-After")]
    (or (retry-after-seconds-ms value)
        (try
          (let [retry-at (.toInstant (ZonedDateTime/parse value DateTimeFormatter/RFC_1123_DATE_TIME))
                millis (- (.toEpochMilli retry-at) (.toEpochMilli (Instant/now)))]
            (max 0 millis))
          (catch Exception _ nil)))))

(defn with-retries
  [sys {:keys [max_retries
               retry_initial_ms
               retry_jitter_ms
               retry_max_delay_ms
               operation
               error-context]}
   f]
  (let [max-retries max_retries
        retry-initial-ms retry_initial_ms
        retry-jitter-ms retry_jitter_ms
        retry-max-delay-ms (or retry_max_delay_ms default-retry-max-delay-ms)
        next-delay-ms (fn [delay-ms]
                        (long (min retry-max-delay-ms (*' 2 delay-ms))))
        retry-delay-ms (fn [delay-ms retry-after-ms]
                         (let [base-delay-ms (max delay-ms (or retry-after-ms 0))
                               jitter-ms (if (pos? retry-jitter-ms)
                                           (random-int sys (inc retry-jitter-ms))
                                           0)]
                           (long (min retry-max-delay-ms
                                      (+' base-delay-ms jitter-ms)))))]
    (letfn [(attempt [attempt-number delay-ms]
              (try
                (f)
                (catch Exception e
                  (if (retryable-exception? e)
                    (if (< attempt-number max-retries)
                      (let [error-data (ex-data e)
                            sleep-ms (retry-delay-ms delay-ms
                                                     (:retry-after-ms error-data))]
                        (u/log ::retry-sleep
                               :operation operation
                               :error-context error-context
                               :attempt attempt-number
                               :max-retries max-retries
                               :delay-ms sleep-ms
                               :status (:status error-data)
                               :error-type (:type error-data))
                        (sleep! sys sleep-ms)
                        (attempt (inc attempt-number) (next-delay-ms delay-ms)))
                      (throw (ex-info (or (ex-message e) "Retryable operation failed")
                                      (assoc (merge error-context (or (ex-data e) {}))
                                             :retryable true
                                             :retry-exhausted true
                                             :attempts attempt-number
                                             :max-retries max-retries)
                                      e)))
                    (throw e)))))]
      (attempt 1 retry-initial-ms))))
