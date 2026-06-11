(ns alida.notify.slack
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [hato.client :as http]))

(def default-request-timeout-ms 30000)

(defn- webhook-url
  [sys]
  (not-empty (str/trim (or (get-in sys [:alida/config :notifications :slack_webhook_url])
                           ""))))

(defn- request!
  [sys request]
  (let [request-fn (or (:alida/http-request sys) http/request)]
    (request-fn
     (merge {:throw-exceptions false
             :connect-timeout default-request-timeout-ms
             :request-timeout default-request-timeout-ms}
            request))))

(defn- successful-status?
  [status]
  (<= 200 status 299))

(defn post-text!
  [sys text]
  (if-let [url (webhook-url sys)]
    (try
      (let [response (request! sys
                               {:method :post
                                :url url
                                :headers {"Content-Type" "application/json"}
                                :body (json/write-str {:text text})})
            status (:status response)]
        (if (successful-status? status)
          {:sent true
           :status status}
          {:sent false
           :status status
           :error "Slack webhook returned a non-success status"}))
      (catch Exception e
        {:sent false
         :error (or (ex-message e) (str e))
         :exception_type (str (class e))}))
    {:sent false
     :skipped true
     :reason :not-configured}))

(defn post-report!
  [sys {:keys [slack_summary]}]
  (post-text! sys slack_summary))
