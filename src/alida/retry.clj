(ns alida.retry
  (:require [com.brunobonacci.mulog :as u])
  (:import [java.io IOException]
           [java.time Instant ZonedDateTime]
           [java.time.format DateTimeFormatter]))

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

(defn retry-after-ms
  [headers]
  (when-let [value (header-value headers "Retry-After")]
    (or (some-> value parse-long-safe (* 1000))
        (try
          (let [retry-at (.toInstant (ZonedDateTime/parse value DateTimeFormatter/RFC_1123_DATE_TIME))
                millis (- (.toEpochMilli retry-at) (.toEpochMilli (Instant/now)))]
            (max 0 millis))
          (catch Exception _ nil)))))

(defn with-retries
  [sys {:keys [max_retries retry_initial_ms retry_jitter_ms operation]} f]
  (let [max-retries max_retries
        retry-initial-ms retry_initial_ms
        retry-jitter-ms retry_jitter_ms
        retry-delay-ms (fn [delay-ms retry-after-ms]
                         (+ (max delay-ms (or retry-after-ms 0))
                            (if (pos? retry-jitter-ms)
                              (random-int sys (inc retry-jitter-ms))
                              0)))]
    (letfn [(attempt [attempt-number delay-ms]
              (try
                (f)
                (catch Exception e
                  (if (and (retryable-exception? e) (< attempt-number max-retries))
                    (let [error-data (ex-data e)
                          sleep-ms (retry-delay-ms delay-ms
                                                   (:retry-after-ms error-data))]
                      (u/log ::retry-sleep
                             :operation operation
                             :attempt attempt-number
                             :max-retries max-retries
                             :delay-ms sleep-ms
                             :status (:status error-data)
                             :error-type (:type error-data))
                      (sleep! sys sleep-ms)
                      (attempt (inc attempt-number) (* 2 delay-ms)))
                    (throw e)))))]
      (attempt 1 retry-initial-ms))))
