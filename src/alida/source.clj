(ns alida.source
  (:require [alida.retry :as retry]
            [alida.url :as url]
            [clojure.string :as str]
            [hato.client :as http])
  (:import [java.net URI]))

(def default-request-timeout-ms 60000)
(def default-request-max-retries 3)
(def default-request-retry-initial-ms 1000)
(def default-request-retry-jitter-ms 250)
(def default-request-retry-max-delay-ms retry/default-retry-max-delay-ms)
(def max-error-body-length 1024)

(def ^:private retry-detail-keys
  [:retryable
   :retry-exhausted
   :attempts
   :max-retries
   :source-id
   :request-method
   :request-url])

(defn source-urls
  "Configured start URLs in precedence order: start_urls, start_url, then url."
  [source-cfg]
  (or (seq (:start_urls source-cfg))
      (when-let [url (:start_url source-cfg)] [url])
      (when-let [url (:url source-cfg)] [url])))

(defn internal-link-hosts
  "Normalized hosts trusted by a URL-backed source: every configured start URL
  host plus explicit internal_link_hosts."
  [source-cfg]
  (->> (concat (keep url/host (source-urls source-cfg))
               (:internal_link_hosts source-cfg))
       (keep (fn [host]
               (some-> host str/trim not-empty str/lower-case)))
       distinct
       vec))

(defn external-link-extraction-options
  [source-cfg]
  {:preserve-external-links? (not= false (:preserve_external_links source-cfg))
   :internal-hosts (set (internal-link-hosts source-cfg))})

(defn- source-type
  [source-cfg]
  (keyword (:type source-cfg)))

(defn- dispatch-type
  [_sys source-cfg & _]
  (source-type source-cfg))

(defmulti discover dispatch-type)

(defmulti fetch dispatch-type)

(defmulti html-extraction-options source-type)

(defmethod html-extraction-options :default
  [_source-cfg]
  {})

(defmethod discover :default
  [_sys source-cfg]
  (throw (ex-info (str "Unsupported source type: " (:type source-cfg))
                  {:type :alida.source/unsupported
                   :source-type (:type source-cfg)})))

(defmethod fetch :default
  [_sys source-cfg _discovered-item]
  (throw (ex-info (str "Unsupported source type: " (:type source-cfg))
                  {:type :alida.source/unsupported
                   :source-type (:type source-cfg)})))

(defn anomaly
  [category details]
  {:alida/error (assoc details
                       :cognitect.anomalies/category category)})

(defn anomaly?
  [value]
  (and (map? value)
       (contains? value :alida/error)))

(defn skipped
  [details]
  {:alida/skipped details})

(defn skipped?
  [value]
  (and (map? value)
       (contains? value :alida/skipped)))

(defn request!
  [sys request]
  (let [request-fn (or (:alida/http-request sys) http/request)]
    (request-fn
     (merge {:throw-exceptions false
             :connect-timeout default-request-timeout-ms
             :request-timeout default-request-timeout-ms}
            request))))

(defn- request-url-for-reporting
  [value]
  (try
    (let [uri (URI. (str value))
          origin (url/origin (str value))]
      (when origin
        (str origin (or (.getRawPath uri) ""))))
    (catch Exception _
      nil)))

(defn- request-retry-options
  [source-cfg request]
  (let [request-url (request-url-for-reporting (:url request))]
    {:max_retries (or (:max_retries source-cfg) default-request-max-retries)
     :retry_initial_ms (or (:retry_initial_ms source-cfg)
                           default-request-retry-initial-ms)
     :retry_jitter_ms (or (:retry_jitter_ms source-cfg)
                          default-request-retry-jitter-ms)
     :retry_max_delay_ms (or (:retry_max_delay_ms source-cfg)
                             default-request-retry-max-delay-ms)
     :operation :source-http-request
     :error-context (cond-> {:type :alida.source/transport-error
                             :source-id (:id source-cfg)
                             :request-method (or (:method request) :get)}
                      request-url (assoc :request-url request-url))}))

(defn- retryable-response-error
  [response]
  (ex-info (str "Source request failed with HTTP " (:status response))
           {:type :alida.source/http-error
            :status (:status response)
            :retryable true
            :retry-after-ms (retry/retry-after-ms (:headers response))
            :response response}))

(defn request-with-retries!
  "Perform an HTTP source request with bounded retries for 429, 5xx, and
   transport I/O failures. An exhausted HTTP response is returned with private
   retry metadata so connector-specific handling remains unchanged."
  [sys source-cfg request]
  (try
    (retry/with-retries
     sys
     (request-retry-options source-cfg request)
     (fn []
       (let [response (request! sys request)]
         (if (retry/retryable-status? (:status response))
           (throw (retryable-response-error response))
           response))))
    (catch clojure.lang.ExceptionInfo e
      (let [{:keys [response retry-exhausted] :as data} (ex-data e)]
        (if (and response retry-exhausted)
          (assoc response ::retry-details
                 (select-keys data retry-detail-keys))
          (throw e))))))

(defn successful-status?
  [status]
  (<= 200 status 299))

(defn header
  "Case-insensitive lookup of an HTTP response header value."
  [response header-name]
  (let [headers (:headers response)]
    (or (get headers header-name)
        (get headers (str/lower-case header-name)))))

(defn status-category
  "Map an HTTP status to a cognitect anomaly category (404 -> not-found, else fault)."
  [status]
  (case status
    404 :cognitect.anomalies/not-found
    :cognitect.anomalies/fault))

(defn error-response-details
  [response]
  (let [body (:body response)
        body (when (some? body) (str body))
        truncated? (and body (> (count body) max-error-body-length))]
    (cond-> {}
      body (assoc :body (if truncated?
                          (str (subs body 0 max-error-body-length) "...")
                          body))
      truncated? (assoc :body_truncated true))))

(defn- http-error-details
  [response]
  (merge
   {:status (:status response)}
   (when (retry/retryable-status? (:status response))
     {:retryable true
      :retry-after-ms (retry/retry-after-ms (:headers response))})
   (select-keys (::retry-details response) retry-detail-keys)
   (error-response-details response)))

(defn fetch-anomaly
  "Build a fetch-failure anomaly from an unsuccessful response: anomaly category
   derived from the status, merged with the caller's details (which should carry
   a connector-specific :type) plus a truncated response body."
  [response details]
  (anomaly (status-category (:status response))
           (merge details
                  (http-error-details response))))

(defn require-success!
  [response context]
  (when-not (successful-status? (:status response))
    (throw (ex-info (str "Source request failed with HTTP " (:status response))
                    (merge context
                           {:type :alida.source/http-error
                            :status (:status response)}
                           (http-error-details response)))))
  response)
