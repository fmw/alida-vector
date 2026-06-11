(ns alida.embed
  (:require [clojure.data.json :as json]
            [hato.client :as http])
  (:import [java.io IOException]
           [java.time Instant ZonedDateTime]
           [java.time.format DateTimeFormatter]))

(def default-max-batch-size 96)
(def default-max-retries 3)
(def default-retry-initial-ms 250)
(def default-retry-jitter-ms 0)
(def default-inter-batch-delay-ms 0)
(def default-request-timeout-ms 60000)

(defn- dispatch-provider
  [_sys provider-cfg & _]
  (keyword (:provider provider-cfg)))

(defmulti embed-batch dispatch-provider)

(defmethod embed-batch :default
  [_sys provider-cfg _texts]
  (throw (ex-info (str "Unsupported embedding provider: " (:provider provider-cfg))
                  {:type :alida.embed/unsupported-provider
                   :provider (:provider provider-cfg)})))

(defn require-config!
  [provider-cfg k]
  (or (get provider-cfg k)
      (throw (ex-info (str "Missing embedding provider config: " (name k))
                      {:type :alida.embed/missing-config
                       :provider (:provider provider-cfg)
                       :key k}))))

(defn parse-json
  [body]
  (json/read-str body :key-fn keyword))

(defn request!
  [sys request]
  (let [request-fn (or (:alida/http-request sys) http/request)]
    (request-fn
     (merge {:throw-exceptions false
             :connect-timeout default-request-timeout-ms
             :request-timeout default-request-timeout-ms}
            request))))

(defn sleep!
  [sys millis]
  (if-let [sleep-fn (:alida/sleep sys)]
    (sleep-fn millis)
    (Thread/sleep millis)))

(defn- random-int
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

(defn request-json!
  [sys request]
  (let [response (request! sys request)
        status (:status response)]
    (if (<= 200 status 299)
      (parse-json (:body response))
      (throw (ex-info (str "Embedding provider request failed with HTTP " status)
                      {:type :alida.embed/http-error
                       :status status
                       :body (:body response)
                       :headers (:headers response)
                       :retry-after-ms (retry-after-ms (:headers response))
                       :retryable (retryable-status? status)})))))

(defn with-retries
  [sys provider-cfg f]
  (let [max-retries (or (:max_retries provider-cfg) default-max-retries)
        retry-initial-ms (or (:retry_initial_ms provider-cfg) default-retry-initial-ms)
        retry-jitter-ms (or (:retry_jitter_ms provider-cfg) default-retry-jitter-ms)
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
                    (do
                      (sleep! sys (retry-delay-ms delay-ms
                                                  (:retry-after-ms (ex-data e))))
                      (attempt (inc attempt-number) (* 2 delay-ms)))
                    (throw e)))))]
      (attempt 1 retry-initial-ms))))

(defn batches
  [provider-cfg texts]
  (let [batch-size (or (:max_batch_size provider-cfg) default-max-batch-size)]
    (when-not (pos-int? batch-size)
      (throw (ex-info "Embedding max_batch_size must be positive"
                      {:type :alida.embed/invalid-batch-size
                       :max-batch-size batch-size})))
    (partition-all batch-size texts)))

(defn validate-embedding-count!
  [texts embeddings]
  (when-not (= (count texts) (count embeddings))
    (throw (ex-info "Embedding provider returned a different number of embeddings than inputs"
                    {:type :alida.embed/embedding-count-mismatch
                     :input-count (count texts)
                     :embedding-count (count embeddings)})))
  embeddings)

(defn embed-in-batches
  [sys provider-cfg texts embed-one-batch]
  (let [texts (vec texts)
        batches (vec (batches provider-cfg texts))
        inter-batch-delay-ms (or (:inter_batch_delay_ms provider-cfg)
                                 default-inter-batch-delay-ms)
        embeddings (loop [remaining batches
                          result []]
                     (if-let [batch (first remaining)]
                       (let [batch-result (with-retries
                                            sys
                                            provider-cfg
                                            #(embed-one-batch sys provider-cfg (vec batch)))
                             more? (seq (rest remaining))]
                         (when (and more? (pos? inter-batch-delay-ms))
                           (sleep! sys inter-batch-delay-ms))
                         (recur (rest remaining)
                                (into result batch-result)))
                       result))]
    (validate-embedding-count! texts embeddings)))
