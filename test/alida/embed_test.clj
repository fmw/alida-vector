(ns alida.embed-test
  (:require [alida.embed :as embed]
            [alida.embed.azure-openai]
            [alida.embed.openai]
            [alida.embed.vertex-ai]
            [clojure.data.json :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]))

(defn- json-response
  [body]
  {:status 200
   :body (json/write-str body)})

(defn- fake-sys
  ([responses requests sleeps]
   (fake-sys responses requests sleeps (constantly 0)))
  ([responses requests sleeps random-int-fn]
   {:alida/http-request (fn [request]
                          (swap! requests conj request)
                          (let [response (first @responses)]
                            (swap! responses subvec 1)
                            (if (instance? Throwable response)
                              (throw response)
                              response)))
    :alida/sleep (fn [millis]
                   (swap! sleeps conj millis))
    :alida/random-int random-int-fn}))

(deftest openai-embeds-in-batches-and-retries-retryable-errors
  (let [responses (atom [{:status 429 :body "{\"error\":\"rate limited\"}"}
                         (json-response {:data [{:index 1 :embedding [0.2]}
                                                {:index 0 :embedding [0.1]}]})
                         (json-response {:data [{:index 0 :embedding [0.3]}]})])
        requests (atom [])
        sleeps (atom [])
        result (embed/embed-batch
                (fake-sys responses requests sleeps)
                {:provider "openai"
                 :api_key "openai-key"
                 :model "text-embedding-3-small"
                 :max_batch_size 2
                 :max_retries 2
                 :retry_initial_ms 5}
                ["a" "b" "c"])]
    (is (= [[0.1] [0.2] [0.3]] result))
    (is (= [5] @sleeps))
    (is (= 3 (count @requests)))
    (is (= "https://api.openai.com/v1/embeddings"
           (:url (first @requests))))
    (is (= "Bearer openai-key"
           (get-in (first @requests) [:headers "Authorization"])))
    (is (= {:model "text-embedding-3-small"
            :input ["a" "b"]
            :encoding_format "float"}
           (json/read-str (:body (first @requests)) :key-fn keyword)))))

(deftest embedding-retries-can-add-jitter-to-backoff
  (let [responses (atom [{:status 429 :body "{\"error\":\"rate limited\"}"}
                         (json-response {:data [{:index 0 :embedding [0.1]}]})])
        requests (atom [])
        sleeps (atom [])
        result (embed/embed-batch
                (fake-sys responses requests sleeps (fn [bound]
                                                      (is (= 11 bound))
                                                      7))
                {:provider "openai"
                 :api_key "openai-key"
                 :model "text-embedding-3-small"
                 :max_retries 2
                 :retry_initial_ms 5
                 :retry_jitter_ms 10}
                ["a"])]
    (is (= [[0.1]] result))
    (is (= [12] @sleeps))))

(deftest embedding-retries-honor-retry-after-header
  (let [responses (atom [{:status 429
                          :headers {"Retry-After" "2"}
                          :body "{\"error\":\"rate limited\"}"}
                         (json-response {:data [{:index 0 :embedding [0.1]}]})])
        requests (atom [])
        sleeps (atom [])
        result (embed/embed-batch
                (fake-sys responses requests sleeps)
                {:provider "openai"
                 :api_key "openai-key"
                 :model "text-embedding-3-small"
                 :max_retries 2
                 :retry_initial_ms 5}
                ["a"])]
    (is (= [[0.1]] result))
    (is (= [2000] @sleeps))))

(deftest embedding-retries-transport-io-errors
  (let [responses (atom [(java.io.IOException. "connection reset")
                         (json-response {:data [{:index 0 :embedding [0.1]}]})])
        requests (atom [])
        sleeps (atom [])
        result (embed/embed-batch
                (fake-sys responses requests sleeps)
                {:provider "openai"
                 :api_key "openai-key"
                 :model "text-embedding-3-small"
                 :max_retries 2
                 :retry_initial_ms 5}
                ["a"])]
    (is (= [[0.1]] result))
    (is (= 2 (count @requests)))
    (is (= [5] @sleeps))))

(deftest embedding-batches-can-pause-between-provider-calls
  (let [responses (atom [(json-response {:data [{:index 0 :embedding [0.1]}]})
                         (json-response {:data [{:index 0 :embedding [0.2]}]})
                         (json-response {:data [{:index 0 :embedding [0.3]}]})])
        requests (atom [])
        sleeps (atom [])
        result (embed/embed-batch
                (fake-sys responses requests sleeps)
                {:provider "openai"
                 :api_key "openai-key"
                 :model "text-embedding-3-small"
                 :max_batch_size 1
                 :inter_batch_delay_ms 25}
                ["a" "b" "c"])]
    (is (= [[0.1] [0.2] [0.3]] result))
    (is (= 3 (count @requests)))
    (is (= [25 25] @sleeps))))

(deftest azure-openai-uses-deployment-endpoint
  (let [responses (atom [(json-response {:data [{:index 0 :embedding [1.0 2.0]}]})])
        requests (atom [])
        sleeps (atom [])
        result (embed/embed-batch
                (fake-sys responses requests sleeps)
                {:provider "azure-openai"
                 :endpoint "https://example.openai.azure.com/"
                 :deployment_name "ada deployment"
                 :api_version "2024-02-01"
                 :api_key "azure-key"}
                ["hello"])]
    (is (= [[1.0 2.0]] result))
    (is (= "azure-key"
           (get-in (first @requests) [:headers "api-key"])))
    (is (= "https://example.openai.azure.com/openai/deployments/ada%20deployment/embeddings?api-version=2024-02-01"
           (:url (first @requests))))
    (is (= {:input ["hello"]
            :encoding_format "float"}
           (json/read-str (:body (first @requests)) :key-fn keyword)))))

(deftest vertex-ai-uses-predict-endpoint
  (let [responses (atom [(json-response {:predictions [{:embeddings {:values [1.0 2.0 3.0]}}]})])
        requests (atom [])
        sleeps (atom [])
        result (embed/embed-batch
                (fake-sys responses requests sleeps)
                {:provider "vertex-ai"
                 :project "alida-project"
                 :location "europe-west4"
                 :model "text-embedding-005"
                 :access_token "vertex-token"}
                ["hello"])]
    (is (= [[1.0 2.0 3.0]] result))
    (is (= "Bearer vertex-token"
           (get-in (first @requests) [:headers "Authorization"])))
    (is (= "https://europe-west4-aiplatform.googleapis.com/v1/projects/alida-project/locations/europe-west4/publishers/google/models/text-embedding-005:predict"
           (:url (first @requests))))
    (is (= {:instances [{:content "hello"}]}
           (json/read-str (:body (first @requests)) :key-fn keyword)))))

(deftest provider-response-size-must-match-input-size
  (let [responses (atom [(json-response {:data [{:index 0 :embedding [0.1]}]})])
        requests (atom [])
        sleeps (atom [])]
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"different number of embeddings"
         (embed/embed-batch
          (fake-sys responses requests sleeps)
          {:provider "openai"
           :api_key "openai-key"
           :model "text-embedding-3-small"}
          ["a" "b"])))))

(deftest noop-provider-returns-placeholder-vectors
  (is (= [[0.0 0.0 0.0] [0.0 0.0 0.0]]
         (embed/embed-batch
          {}
          {:provider "noop"
           :embedding_dimensions 3}
          ["a" "b"]))))

(deftest unsupported-provider-is-rejected
  (is (thrown-with-msg?
       clojure.lang.ExceptionInfo
       #"Unsupported embedding provider"
       (embed/embed-batch {} {:provider "wat"} ["a"]))))

(deftest non-retryable-provider-errors-are-not-retried
  (let [responses (atom [{:status 400 :body "{\"error\":\"bad request\"}"}])
        requests (atom [])
        sleeps (atom [])]
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"HTTP 400"
         (embed/embed-batch
          (fake-sys responses requests sleeps)
          {:provider "openai"
           :api_key "openai-key"
           :model "text-embedding-3-small"}
          ["a"])))
    (is (= 1 (count @requests)))
    (is (= [] @sleeps))))

(deftest missing-provider-config-is-rejected
  (is (thrown-with-msg?
       clojure.lang.ExceptionInfo
       #"Missing embedding provider config: model"
       (embed/embed-batch
        (fake-sys (atom []) (atom []) (atom []))
        {:provider "openai"
         :api_key "openai-key"}
        ["a"]))))

(deftest azure-url-does-not-keep-trailing-slash
  (let [responses (atom [(json-response {:data [{:index 0 :embedding [1.0]}]})])
        requests (atom [])
        sleeps (atom [])]
    (embed/embed-batch
     (fake-sys responses requests sleeps)
     {:provider "azure-openai"
      :endpoint "https://example.openai.azure.com/"
      :deployment_name "embedding"
      :api_key "azure-key"}
     ["hello"])
    (is (not (str/includes? (:url (first @requests)) ".com//openai")))))
