(ns alida.notify.slack-test
  (:require [alida.notify.slack :as slack]
            [clojure.data.json :as json]
            [clojure.test :refer [deftest is]]))

(deftest post-report-skips-when-webhook-is-not-configured
  (is (= {:sent false
          :skipped true
          :reason :not-configured}
         (slack/post-report! {:alida/config {}}
                             {:slack_summary "run summary"}))))

(deftest post-report-sends-json-payload-to-webhook
  (let [requests (atom [])
        result (slack/post-report!
                {:alida/config {:notifications {:slack_webhook_url "https://example.test/slack"}}
                 :alida/http-request (fn [request]
                                       (swap! requests conj request)
                                       {:status 200
                                        :body "ok"})}
                {:slack_summary "run summary"
                 :slack_blocks [{:type "section"
                                 :text {:type "mrkdwn"
                                        :text "Pretty summary"}}]})]
    (is (= {:sent true
            :status 200}
           result))
    (is (= :post (:method (first @requests))))
    (is (= "https://example.test/slack" (:url (first @requests))))
    (is (= {"text" "run summary"
            "blocks" [{"type" "section"
                       "text" {"type" "mrkdwn"
                               "text" "Pretty summary"}}]}
           (json/read-str (:body (first @requests)))))))

(deftest post-report-returns-failure-for-non-success-status
  (let [result (slack/post-report!
                {:alida/config {:notifications {:slack_webhook_url "https://example.test/slack"}}
                 :alida/http-request (fn [_]
                                       {:status 500
                                        :body "nope"})}
                {:slack_summary "run summary"})]
    (is (= false (:sent result)))
    (is (= 500 (:status result)))))

(deftest post-report-returns-failure-for-request-exceptions
  (let [result (slack/post-report!
                {:alida/config {:notifications {:slack_webhook_url "https://example.test/slack"}}
                 :alida/http-request (fn [_]
                                       (throw (ex-info "network failed"
                                                       {:type :test/network-failed})))}
                {:slack_summary "run summary"})]
    (is (= false (:sent result)))
    (is (= "network failed" (:error result)))))
