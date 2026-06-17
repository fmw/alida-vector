(ns alida.embed
  (:require [alida.retry :as retry]
            [alida.text :as text]
            [clojure.data.json :as json]
            [hato.client :as http]))

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

(defmethod embed-batch :noop
  [_sys provider-cfg texts]
  (let [dimensions (:embedding_dimensions provider-cfg)]
    (when-not (pos-int? dimensions)
      (throw (ex-info "Noop embedding provider requires positive embedding_dimensions"
                      {:type :alida.embed/invalid-dimensions
                       :embedding-dimensions dimensions})))
    (mapv (fn [_] (vec (repeat dimensions 0.0))) texts)))

(defn fingerprint
  [provider-cfg]
  (-> provider-cfg
      (select-keys [:provider
                    :model
                    :deployment_name
                    :endpoint
                    :api_version
                    :project
                    :location
                    :embedding_dimensions])
      pr-str
      text/sha-256))

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
                       :retry-after-ms (retry/retry-after-ms (:headers response))
                       :retryable (retry/retryable-status? status)})))))

(defn with-retries
  [sys provider-cfg f]
  (retry/with-retries sys
                      (merge {:max_retries default-max-retries
                              :retry_initial_ms default-retry-initial-ms
                              :retry_jitter_ms default-retry-jitter-ms
                              :operation "embedding-provider"}
                             provider-cfg)
                      f))

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
                           (retry/sleep! sys inter-batch-delay-ms))
                         (recur (rest remaining)
                                (into result batch-result)))
                       result))]
    (validate-embedding-count! texts embeddings)))
