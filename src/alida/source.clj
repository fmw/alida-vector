(ns alida.source
  (:require [clojure.string]
            [hato.client :as http]))

(def default-request-timeout-ms 60000)
(def max-error-body-length 1024)

(defn- dispatch-type
  [_sys source-cfg & _]
  (keyword (:type source-cfg)))

(defmulti discover dispatch-type)

(defmulti fetch dispatch-type)

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

(defn successful-status?
  [status]
  (<= 200 status 299))

(defn header
  "Case-insensitive lookup of an HTTP response header value."
  [response header-name]
  (let [headers (:headers response)]
    (or (get headers header-name)
        (get headers (clojure.string/lower-case header-name)))))

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

(defn fetch-anomaly
  "Build a fetch-failure anomaly from an unsuccessful response: anomaly category
   derived from the status, merged with the caller's details (which should carry
   a connector-specific :type) plus a truncated response body."
  [response details]
  (anomaly (status-category (:status response))
           (merge details
                  {:status (:status response)}
                  (error-response-details response))))

(defn require-success!
  [response context]
  (when-not (successful-status? (:status response))
    (throw (ex-info (str "Source request failed with HTTP " (:status response))
                    (merge context
                           {:type :alida.source/http-error
                            :status (:status response)}
                           (error-response-details response)))))
  response)
