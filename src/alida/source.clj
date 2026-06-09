(ns alida.source
  (:require [hato.client :as http]))

(def default-request-timeout-ms 60000)

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
  (contains? value :alida/error))

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

(defn require-success!
  [response context]
  (when-not (successful-status? (:status response))
    (throw (ex-info (str "Source request failed with HTTP " (:status response))
                    (assoc context
                           :type :alida.source/http-error
                           :status (:status response)
                           :body (:body response)))))
  response)
