(ns alida.embed
  (:require [clojure.data.json :as json]
            [hato.client :as http]))

(def default-max-batch-size 96)
(def default-max-retries 3)
(def default-retry-initial-ms 250)
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

(defn retryable-status?
  [status]
  (or (= 429 status)
      (<= 500 status 599)))

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
                       :retryable (retryable-status? status)})))))

(defn with-retries
  [sys provider-cfg f]
  (let [max-retries (or (:max_retries provider-cfg) default-max-retries)
        retry-initial-ms (or (:retry_initial_ms provider-cfg) default-retry-initial-ms)]
    (letfn [(attempt [attempt-number delay-ms]
              (try
                (f)
                (catch Exception e
                  (let [{:keys [retryable]} (ex-data e)]
                    (if (and retryable (< attempt-number max-retries))
                      (do
                        (sleep! sys delay-ms)
                        (attempt (inc attempt-number) (* 2 delay-ms)))
                      (throw e))))))]
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
  (let [texts (vec texts)]
    (->> (batches provider-cfg texts)
         (mapcat (fn [batch]
                   (with-retries
                     sys
                     provider-cfg
                     #(embed-one-batch sys provider-cfg (vec batch)))))
         vec
         (validate-embedding-count! texts))))
