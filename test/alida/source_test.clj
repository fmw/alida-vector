(ns alida.source-test
  (:require [alida.source :as source]
            [clojure.test :refer [deftest is testing]]))

(defn- sequential-http
  [responses requests sleeps]
  (let [remaining (atom responses)]
    {:alida/http-request
     (fn [request]
       (swap! requests conj request)
       (let [response (first @remaining)]
         (swap! remaining subvec 1)
         (if (instance? Throwable response)
           (throw response)
           response)))
     :alida/sleep #(swap! sleeps conj %)
     :alida/random-int (constantly 0)}))

(deftest unsupported-source-types-are-rejected
  (is (thrown-with-msg?
       clojure.lang.ExceptionInfo
       #"Unsupported source type"
       (source/discover {} {:type "unknown"})))
  (is (thrown-with-msg?
       clojure.lang.ExceptionInfo
       #"Unsupported source type"
       (source/fetch {} {:type "unknown"} {}))))

(deftest anomaly-helper-shapes-recoverable-errors
  (is (= {:alida/error {:cognitect.anomalies/category :cognitect.anomalies/not-found
                       :type :example}}
         (source/anomaly :cognitect.anomalies/not-found {:type :example})))
  (is (source/anomaly? (source/anomaly :cognitect.anomalies/fault {})))
  (is (false? (source/anomaly? nil)))
  (is (false? (source/anomaly? "not a map")))
  (is (false? (source/anomaly? (ex-info "boom" {})))))

(deftest resolves-start-urls-and-internal-hosts-once-for-source-consumers
  (let [source-cfg {:start_urls ["https://example.test/" "https://docs.example.test/"]
                    :start_url "https://ignored.example.test/"
                    :url "https://also-ignored.example.test/"
                    :internal_link_hosts [" API.EXAMPLE.TEST " "docs.example.test"]}]
    (is (= ["https://example.test/" "https://docs.example.test/"]
           (source/source-urls source-cfg)))
    (is (= ["example.test" "docs.example.test" "api.example.test"]
           (source/internal-link-hosts source-cfg)))))

(deftest source-http-requests-retry-transient-responses
  (let [requests (atom [])
        sleeps (atom [])
        sys (sequential-http [{:status 503 :body "unavailable"}
                              {:status 200 :body "ok"}]
                             requests
                             sleeps)
        response (source/request-with-retries!
                  sys
                  {:id "site"
                   :max_retries 3
                   :retry_initial_ms 10
                   :retry_jitter_ms 0}
                  {:method :get :url "https://example.test/sitemap.xml"})]
    (is (= 200 (:status response)))
    (is (= 2 (count @requests)))
    (is (= [10] @sleeps))))

(deftest source-http-retries-honor-retry-after
  (let [requests (atom [])
        sleeps (atom [])
        sys (sequential-http [{:status 429
                               :headers {"Retry-After" "2"}}
                              {:status 200}]
                             requests
                             sleeps)]
    (source/request-with-retries!
     sys
     {:id "site"
      :max_retries 2
      :retry_initial_ms 10
      :retry_jitter_ms 0}
     {:method :get :url "https://example.test/sitemap.xml"})
    (is (= [2000] @sleeps))))

(deftest exhausted-source-http-responses-carry-safe-retry-context
  (let [requests (atom [])
        sleeps (atom [])
        sys (sequential-http [{:status 503 :body "unavailable"}
                              {:status 503 :body "still unavailable"}]
                             requests
                             sleeps)
        response (source/request-with-retries!
                  sys
                  {:id "site"
                   :max_retries 2
                   :retry_initial_ms 10
                   :retry_jitter_ms 0}
                  {:method :get
                   :url "https://user:secret@example.test/sitemap.xml?token=secret#fragment"})]
    (try
      (source/require-success! response {:phase :discovery})
      (is false "Expected the exhausted response to fail")
      (catch clojure.lang.ExceptionInfo e
        (let [data (ex-data e)]
          (is (= 503 (:status data)))
          (is (true? (:retryable data)))
          (is (true? (:retry-exhausted data)))
          (is (= 2 (:attempts data)))
          (is (= 2 (:max-retries data)))
          (is (= "site" (:source-id data)))
          (is (= :get (:request-method data)))
          (is (= "https://example.test/sitemap.xml" (:request-url data)))
          (is (= "still unavailable" (:body data)))
          (is (nil? (:response data))))))
    (is (= 2 (count @requests)))
    (is (= [10] @sleeps))))

(deftest source-http-request-retry-classification
  (testing "transport failures remain retryable after exhaustion"
    (let [requests (atom [])
          sleeps (atom [])
          sys (sequential-http [(java.io.IOException. "connection reset")
                                (java.io.IOException. "connection reset")]
                               requests
                               sleeps)]
      (try
        (source/request-with-retries!
         sys
         {:id "site"
          :max_retries 2
          :retry_initial_ms 10
          :retry_jitter_ms 0}
         {:method :get :url "https://example.test/sitemap.xml?token=secret"})
        (is false "Expected the transport failure to propagate")
        (catch clojure.lang.ExceptionInfo e
          (is (= :alida.source/transport-error (:type (ex-data e))))
          (is (true? (:retryable (ex-data e))))
          (is (true? (:retry-exhausted (ex-data e))))
          (is (= "https://example.test/sitemap.xml"
                 (:request-url (ex-data e))))))))
  (testing "permanent HTTP responses are not retried"
    (let [requests (atom [])
          sleeps (atom [])
          response (source/request-with-retries!
                    (sequential-http [{:status 404 :body "missing"}]
                                     requests
                                     sleeps)
                    {:id "site"
                     :max_retries 3
                     :retry_initial_ms 10
                     :retry_jitter_ms 0}
                    {:method :get :url "https://example.test/missing"})]
      (is (= 404 (:status response)))
      (is (= 1 (count @requests)))
      (is (empty? @sleeps)))))
