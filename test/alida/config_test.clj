(ns alida.config-test
  (:require [alida.config :as config]
            [clj-yaml.core]
            [clojure.test :refer [deftest is testing]]))

(def valid-config
  {:database {:jdbc_url "jdbc:postgresql://localhost/alida"}
   :verification {:provider "openai"
                  :model "gpt-4.1-mini"
                  :api_key "test-key"}
   :notifications {:slack_webhook_url "https://example.test/slack"
                   :label "staging"}
   :indexes [{:name "docs"
              :auto_activate true
              :embedding {:provider "openai"
                          :model "text-embedding-3-small"
                          :embedding_dimensions 1536}
              :chunking {:max_input_tokens 8192
                         :max_tokens 6550
                         :safety_multiplier 1.2}
              :sources [{:id "site"
                         :type "website"
                         :sitemap_url "https://example.test/sitemap.xml"}]}]})

(defn- load-from-map
  "Round-trip a config map through YAML so it exercises the full load-config path.
   Ensures the embedding has an api_key so source-level checks are reached."
  [cfg]
  (let [cfg (assoc-in cfg [:indexes 0 :embedding :api_key] "test-key")
        file (java.io.File/createTempFile "alida-config" ".yml")]
    (try
      (spit file (clj-yaml.core/generate-string cfg))
      (config/load-config (.getPath file))
      (finally (.delete file)))))

(deftest rejects-misspelled-source-key
  (testing "a typo in a source key fails validation instead of being ignored"
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo #"(?i)disallowed key|invalid"
         (load-from-map (assoc-in valid-config [:indexes 0 :sources 0 :sitemap_urll]
                                  "https://example.test/sitemap.xml"))))))

(deftest rejects-website-source-without-sitemap
  (is (thrown-with-msg?
       clojure.lang.ExceptionInfo #"sitemap_url or sitemap_urls"
       (load-from-map (assoc-in valid-config [:indexes 0 :sources 0]
                                {:id "site" :type "website"})))))

(deftest rejects-jsm-source-without-start-url
  (is (thrown-with-msg?
       clojure.lang.ExceptionInfo #"url, start_url, or start_urls"
       (load-from-map (assoc-in valid-config [:indexes 0 :sources 0]
                                {:id "support" :type "jira-service-management"})))))

(deftest rejects-s3-source-without-bucket
  (is (thrown-with-msg?
       clojure.lang.ExceptionInfo #"requires bucket"
       (load-from-map (assoc-in valid-config [:indexes 0 :sources 0]
                                {:id "objects"
                                 :type "s3"
                                 :prefix "docs/"})))))

(deftest rejects-gcs-source-without-bucket
  (is (thrown-with-msg?
       clojure.lang.ExceptionInfo #"requires bucket"
       (load-from-map (assoc-in valid-config [:indexes 0 :sources 0]
                                {:id "objects"
                                 :type "gcs"
                                 :prefix "docs/"})))))

(deftest structural-hash-redacts-secrets
  (testing "secret values do not change the structural hash"
    (is (= (config/structural-config-hash (assoc-in valid-config [:verification :api_key] "a"))
           (config/structural-config-hash (assoc-in valid-config [:verification :api_key] "b"))))))

(deftest structural-hash-keeps-token-limit-settings
  (testing "non-secret token limit fields remain structural"
    (is (not= (config/structural-config-hash (assoc-in valid-config [:indexes 0 :chunking :max_tokens] 5000))
              (config/structural-config-hash (assoc-in valid-config [:indexes 0 :chunking :max_tokens] 6000)))))
  (testing "credentials_path is treated as configuration, not a resolved secret value"
    (is (not= (config/structural-config-hash (assoc-in valid-config [:indexes 0 :embedding :credentials_path] "/tmp/a.json"))
              (config/structural-config-hash (assoc-in valid-config [:indexes 0 :embedding :credentials_path] "/tmp/b.json"))))))

(deftest notification-label-loads
  (is (= "staging" (get-in (load-from-map valid-config) [:notifications :label]))))

(deftest verification-attestations-config-loads
  (let [attestations {:attestor "candidate"
                      :trusted_sources [{:name "pre-production"
                                         :type "postgres"
                                         :jdbc_url "jdbc:postgresql://example.test/attestations"
                                         :user "reader"
                                         :password "secret"
                                         :attestors ["pre-production"]}]}
        loaded (load-from-map (assoc-in valid-config
                                        [:verification :attestations]
                                        attestations))]
    (is (= attestations (get-in loaded [:verification :attestations])))
    (is (= (config/structural-config-hash
            (assoc-in valid-config
                      [:verification :attestations :trusted_sources]
                      [(assoc (first (:trusted_sources attestations)) :password "first")]))
           (config/structural-config-hash
            (assoc-in valid-config
                      [:verification :attestations :trusted_sources]
                      [(assoc (first (:trusted_sources attestations)) :password "second")]))))))

(deftest verification-attestations-config-is-validated
  (doseq [[attestations message]
          [[{:attestor " "} #"attestor must not be blank"]
           [{:trusted_sources [{:name "source"
                                :type "postgres"
                                :jdbc_url "jdbc:postgresql://example.test/attestations"
                                :attestors []}]}
            #"requires at least one attestor"]
           [{:trusted_sources [{:name "source"
                                :type "postgres"
                                :jdbc_url "jdbc:postgresql://example.test/one"
                                :attestors ["one"]}
                               {:name "source"
                                :type "postgres"
                                :jdbc_url "jdbc:postgresql://example.test/two"
                                :attestors ["two"]}]}
            #"source names must be unique"]]]
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         message
         (load-from-map (assoc-in valid-config
                                  [:verification :attestations]
                                  attestations))))))

(deftest retention-config-loads-and-validates
  (is (= 30
         (get-in (load-from-map (assoc valid-config
                                      :retention {:max_age_days 30}))
                 [:retention :max_age_days])))
  (doseq [value [0 -1]]
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"max_age_days must be positive"
         (load-from-map (assoc valid-config
                              :retention {:max_age_days value}))))))

(deftest storage-metadata-is-normalized-to-database-config
  (let [file (java.io.File/createTempFile "alida-storage" ".yml")]
    (try
      (spit file
            "storage:
  metadata:
    type: postgres
    jdbc_url: jdbc:postgresql://localhost/alida
    user: alida
  vectors:
    type: pgvector
verification:
  provider: openai
  model: gpt-4.1-mini
  api_key: test-key
indexes:
  - name: docs
    embedding:
      provider: openai
      model: text-embedding-3-small
      embedding_dimensions: 1536
      api_key: test-key
    chunking:
      max_input_tokens: 8192
      max_tokens: 6550
      safety_multiplier: 1.2
    sources:
      - id: site
        type: website
        sitemap_url: https://example.test/sitemap.xml
")
      (is (= {:jdbc_url "jdbc:postgresql://localhost/alida"
              :user "alida"}
             (:database (config/load-config (.getPath file)))))
      (finally
        (.delete file)))))

(deftest invalid-chunking-is-rejected
  (let [file (java.io.File/createTempFile "alida-invalid" ".yml")]
    (try
      (spit file
            "database:
  jdbc_url: jdbc:postgresql://localhost/alida
verification:
  provider: openai
  model: gpt-4.1-mini
  api_key: test-key
indexes:
  - name: docs
    embedding:
      provider: openai
      model: text-embedding-3-small
      embedding_dimensions: 1536
      api_key: test-key
    chunking:
      max_input_tokens: 100
      max_tokens: 100
      safety_multiplier: 1.2
    sources:
      - id: site
        type: website
        sitemap_url: https://example.test/sitemap.xml
")
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"max_tokens \* safety_multiplier"
                            (config/load-config (.getPath file))))
      (finally
        (.delete file)))))

(deftest unsupported-pgvector-dimensions-are-rejected
  (let [file (java.io.File/createTempFile "alida-unsupported-dimensions" ".yml")]
    (try
      (spit file
            "storage:
  metadata:
    type: postgres
    jdbc_url: jdbc:postgresql://localhost/alida
  vectors:
    type: pgvector
verification:
  provider: openai
  model: gpt-4.1-mini
  api_key: test-key
indexes:
  - name: docs
    embedding:
      provider: openai
      model: text-embedding-3-small
      embedding_dimensions: 768
      api_key: test-key
    chunking:
      max_input_tokens: 8192
      max_tokens: 6550
      safety_multiplier: 1.2
    sources:
      - id: site
        type: website
        sitemap_url: https://example.test/sitemap.xml
")
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"Unsupported pgvector dimensions"
                            (config/load-config (.getPath file))))
      (finally
        (.delete file)))))

(deftest embedding-provider-required-fields-are-validated
  (let [file (java.io.File/createTempFile "alida-embedding-provider" ".yml")]
    (try
      (spit file
            "database:
  jdbc_url: jdbc:postgresql://localhost/alida
verification:
  provider: openai
  model: gpt-4.1-mini
  api_key: test-key
indexes:
  - name: docs
    embedding:
      provider: azure-openai
      deployment_name: text-embedding-ada-002
      embedding_dimensions: 1536
    chunking:
      max_input_tokens: 8192
      max_tokens: 6550
      safety_multiplier: 1.2
    sources:
      - id: site
        type: website
        sitemap_url: https://example.test/sitemap.xml
")
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"provider azure-openai requires endpoint"
                            (config/load-config (.getPath file))))
      (finally
        (.delete file)))))

(deftest openai-embedding-api-key-is-required
  (let [file (java.io.File/createTempFile "alida-openai-embedding-provider" ".yml")]
    (try
      (spit file
            "database:
  jdbc_url: jdbc:postgresql://localhost/alida
verification:
  provider: openai
  model: gpt-4.1-mini
  api_key: test-key
indexes:
  - name: docs
    embedding:
      provider: openai
      model: text-embedding-3-small
      embedding_dimensions: 1536
    chunking:
      max_input_tokens: 8192
      max_tokens: 6550
      safety_multiplier: 1.2
    sources:
      - id: site
        type: website
        sitemap_url: https://example.test/sitemap.xml
")
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"provider openai requires api_key"
                            (config/load-config (.getPath file))))
      (finally
        (.delete file)))))

(deftest noop-embedding-provider-does-not-require-provider-secrets
  (let [file (java.io.File/createTempFile "alida-noop-embedding-provider" ".yml")]
    (try
      (spit file
            "database:
  jdbc_url: jdbc:postgresql://localhost/alida
verification:
  provider: openai
  model: gpt-4.1-mini
  api_key: test-key
indexes:
  - name: docs
    embedding:
      provider: noop
      embedding_dimensions: 1536
    chunking:
      max_input_tokens: 8192
      max_tokens: 6550
      safety_multiplier: 1.2
    sources:
      - id: site
        type: website
        sitemap_url: https://example.test/sitemap.xml
")
      (is (= "noop"
             (-> (config/load-config (.getPath file))
                 :indexes
                 first
                 :embedding
                 :provider)))
      (finally
        (.delete file)))))

(deftest openai-verification-api-key-is-required
  (let [file (java.io.File/createTempFile "alida-openai-verification-provider" ".yml")]
    (try
      (spit file
            "database:
  jdbc_url: jdbc:postgresql://localhost/alida
verification:
  provider: openai
  model: gpt-4.1-mini
indexes:
  - name: docs
    embedding:
      provider: openai
      model: text-embedding-3-small
      embedding_dimensions: 1536
      api_key: test-key
    chunking:
      max_input_tokens: 8192
      max_tokens: 6550
      safety_multiplier: 1.2
    sources:
      - id: site
        type: website
        sitemap_url: https://example.test/sitemap.xml
")
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"provider openai requires api_key"
                            (config/load-config (.getPath file))))
      (finally
        (.delete file)))))

(deftest azure-openai-verification-endpoint-is-required
  (let [file (java.io.File/createTempFile "alida-azure-openai-verification-provider" ".yml")]
    (try
      (spit file
            "database:
  jdbc_url: jdbc:postgresql://localhost/alida
verification:
  provider: azure-openai
  deployment_name: gpt-5.1
  model: gpt-5.1
  api_key: test-key
indexes:
  - name: docs
    embedding:
      provider: openai
      model: text-embedding-3-small
      embedding_dimensions: 1536
      api_key: test-key
    chunking:
      max_input_tokens: 8192
      max_tokens: 6550
      safety_multiplier: 1.2
    sources:
      - id: site
        type: website
        sitemap_url: https://example.test/sitemap.xml
")
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"provider azure-openai requires endpoint"
                            (config/load-config (.getPath file))))
      (finally
        (.delete file)))))

(deftest azure-openai-verification-model-is-required
  (is (thrown-with-msg?
       clojure.lang.ExceptionInfo
       #"provider azure-openai requires model"
       (load-from-map
        (assoc valid-config
               :verification {:provider "azure-openai"
                              :endpoint "https://example.openai.azure.com"
                              :deployment_name "verifier"
                              :api_key "test-key"})))))

(deftest vertex-ai-verification-project-is-required
  (let [file (java.io.File/createTempFile "alida-vertex-ai-verification-provider" ".yml")]
    (try
      (spit file
            "database:
  jdbc_url: jdbc:postgresql://localhost/alida
verification:
  provider: vertex-ai
  location: europe-west4
  model: gemini-2.5-flash-lite
indexes:
  - name: docs
    embedding:
      provider: openai
      model: text-embedding-3-small
      embedding_dimensions: 1536
      api_key: test-key
    chunking:
      max_input_tokens: 8192
      max_tokens: 6550
      safety_multiplier: 1.2
    sources:
      - id: site
        type: website
        sitemap_url: https://example.test/sitemap.xml
")
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"provider vertex-ai requires project"
                            (config/load-config (.getPath file))))
      (finally
        (.delete file)))))

(deftest disabled-llm-verification-does-not-require-provider-secrets
  (let [file (java.io.File/createTempFile "alida-disabled-verification" ".yml")]
    (try
      (spit file
            "database:
  jdbc_url: jdbc:postgresql://localhost/alida
verification:
  enabled: false
  deterministic_thresholds:
    max_item_failure_percentage: 0.1
indexes:
  - name: docs
    embedding:
      provider: openai
      model: text-embedding-3-small
      embedding_dimensions: 1536
      api_key: test-key
    chunking:
      max_input_tokens: 8192
      max_tokens: 6550
      safety_multiplier: 1.2
    sources:
      - id: site
        type: website
        sitemap_url: https://example.test/sitemap.xml
")
      (is (false? (-> (config/load-config (.getPath file))
                      :verification
                      :enabled)))
      (finally
        (.delete file)))))

(deftest embedding-provider-positive-options-are-validated
  (let [file (java.io.File/createTempFile "alida-embedding-options" ".yml")]
    (try
      (spit file
            "database:
  jdbc_url: jdbc:postgresql://localhost/alida
verification:
  provider: openai
  model: gpt-4.1-mini
  api_key: test-key
indexes:
  - name: docs
    embedding:
      provider: openai
      model: text-embedding-3-small
      embedding_dimensions: 1536
      api_key: test-key
      max_batch_size: 0
    chunking:
      max_input_tokens: 8192
      max_tokens: 6550
      safety_multiplier: 1.2
    sources:
      - id: site
        type: website
        sitemap_url: https://example.test/sitemap.xml
")
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"max_batch_size must be positive"
                            (config/load-config (.getPath file))))
      (finally
        (.delete file)))))

(deftest embedding-provider-rate-limit-options-must-be-non-negative
  (let [file (java.io.File/createTempFile "alida-embedding-rate-limit-options" ".yml")]
    (try
      (spit file
            "database:
  jdbc_url: jdbc:postgresql://localhost/alida
verification:
  provider: openai
  model: gpt-4.1-mini
  api_key: test-key
indexes:
  - name: docs
    embedding:
      provider: openai
      model: text-embedding-3-small
      embedding_dimensions: 1536
      api_key: test-key
      inter_batch_delay_ms: -1
    chunking:
      max_input_tokens: 8192
      max_tokens: 6550
      safety_multiplier: 1.2
    sources:
      - id: site
        type: website
        sitemap_url: https://example.test/sitemap.xml
")
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"inter_batch_delay_ms must be zero or positive"
                            (config/load-config (.getPath file))))
      (finally
        (.delete file)))))

(deftest deterministic-threshold-percentages-are-fractions
  (let [file (java.io.File/createTempFile "alida-deterministic-thresholds" ".yml")]
    (try
      (spit file
            "database:
  jdbc_url: jdbc:postgresql://localhost/alida
verification:
  provider: openai
  model: gpt-4.1-mini
  api_key: test-key
  deterministic_thresholds:
    max_removed_percentage: 30.0
indexes:
  - name: docs
    embedding:
      provider: openai
      model: text-embedding-3-small
      embedding_dimensions: 1536
      api_key: test-key
    chunking:
      max_input_tokens: 8192
      max_tokens: 6550
      safety_multiplier: 1.2
    sources:
      - id: site
        type: website
        sitemap_url: https://example.test/sitemap.xml
")
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"percentage thresholds must be fractions"
                            (config/load-config (.getPath file))))
      (finally
        (.delete file)))))

(deftest deterministic-threshold-counts-are-non-negative
  (let [file (java.io.File/createTempFile "alida-deterministic-threshold-counts" ".yml")]
    (try
      (spit file
            "database:
  jdbc_url: jdbc:postgresql://localhost/alida
verification:
  provider: openai
  model: gpt-4.1-mini
  api_key: test-key
  deterministic_thresholds:
    max_removed_absolute: -1
indexes:
  - name: docs
    embedding:
      provider: openai
      model: text-embedding-3-small
      embedding_dimensions: 1536
      api_key: test-key
    chunking:
      max_input_tokens: 8192
      max_tokens: 6550
      safety_multiplier: 1.2
    sources:
      - id: site
        type: website
        sitemap_url: https://example.test/sitemap.xml
")
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"max_removed_absolute"
                            (config/load-config (.getPath file))))
      (finally
        (.delete file)))))

(deftest verification-max-prompt-tokens-must-be-positive
  (let [file (java.io.File/createTempFile "alida-verification-options" ".yml")]
    (try
      (spit file
            "database:
  jdbc_url: jdbc:postgresql://localhost/alida
verification:
  provider: openai
  model: gpt-4.1-mini
  api_key: test-key
  max_prompt_tokens: 0
indexes:
  - name: docs
    embedding:
      provider: openai
      model: text-embedding-3-small
      embedding_dimensions: 1536
      api_key: test-key
    chunking:
      max_input_tokens: 8192
      max_tokens: 6550
      safety_multiplier: 1.2
    sources:
      - id: site
        type: website
        sitemap_url: https://example.test/sitemap.xml
")
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"max_prompt_tokens must be positive"
                            (config/load-config (.getPath file))))
      (finally
        (.delete file)))))

(deftest verification-chat-completion-parameters-are-validated
  (testing "supported OpenAI chat completion parameters load"
    (let [cfg (load-from-map
               (update valid-config
                       :verification
                       assoc
                       :temperature 1
                       :max_completion_tokens 512
                       :reasoning_effort "low"
                       :verbosity "low"))]
      (is (= 1 (get-in cfg [:verification :temperature])))
      (is (= 512 (get-in cfg [:verification :max_completion_tokens])))
      (is (= "low" (get-in cfg [:verification :reasoning_effort])))
      (is (= "low" (get-in cfg [:verification :verbosity])))))
  (testing "temperature can be null to use the provider default"
    (let [cfg (load-from-map (assoc-in valid-config [:verification :temperature] nil))]
      (is (contains? (:verification cfg) :temperature))
      (is (nil? (get-in cfg [:verification :temperature])))))
  (testing "temperature and top_p are alternatives"
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"temperature or top_p"
         (load-from-map
          (-> valid-config
              (assoc-in [:verification :temperature] 0.5)
              (assoc-in [:verification :top_p] 0.9))))))
  (testing "OpenAI chat completion parameters are rejected for Vertex AI"
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"does not support OpenAI chat completion parameters"
         (load-from-map
          (-> valid-config
              (assoc :verification {:provider "vertex-ai"
                                    :model "gemini-model"
                                    :project "example-project"
                                    :location "europe-west4"
                                    :access_token "test-token"
                                    :temperature 0.5}))))))
  (doseq [[key value]
          [[:reasoning_effort "extreme"]
           [:verbosity "verbose"]]]
    (testing (str "invalid " (name key) " enum fails")
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo
           #"Invalid Alida config"
           (load-from-map (assoc-in valid-config [:verification key] value))))))
  (doseq [[key value message]
          [[:temperature -0.1 #"temperature must be between"]
           [:temperature 2.1 #"temperature must be between"]
           [:top_p -0.1 #"top_p must be between"]
           [:top_p 1.1 #"top_p must be between"]
           [:max_completion_tokens 0 #"max_completion_tokens must be positive"]]]
    (testing (str "invalid " (name key) " fails")
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo
           message
           (load-from-map (assoc-in valid-config [:verification key] value)))))))

(deftest verification-retry-options-are-validated
  (testing "positive retry options load"
    (let [cfg (load-from-map
               (update valid-config
                       :verification
                       assoc
                       :max_retries 2
                       :retry_initial_ms 1000
                       :retry_jitter_ms 250
                       :inter_prompt_delay_ms 500))]
      (is (= 2 (get-in cfg [:verification :max_retries])))
      (is (= 500 (get-in cfg [:verification :inter_prompt_delay_ms])))))
  (testing "invalid retry options fail"
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"max_retries must be positive"
         (load-from-map
          (assoc-in valid-config [:verification :max_retries] 0))))
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"inter_prompt_delay_ms must be zero or positive"
         (load-from-map
          (assoc-in valid-config [:verification :inter_prompt_delay_ms] -1))))))

(deftest language-config-loads-for-index-and-source
  (let [file (java.io.File/createTempFile "alida-language-config" ".yml")]
    (try
      (spit file
            "database:
  jdbc_url: jdbc:postgresql://localhost/alida
verification:
  provider: openai
  model: gpt-4.1-mini
  api_key: test-key
indexes:
  - name: docs
    languages:
      allowed: [en, de, nl, fr]
      fallback: en
    embedding:
      provider: openai
      model: text-embedding-3-small
      embedding_dimensions: 1536
      api_key: test-key
    chunking:
      max_input_tokens: 8192
      max_tokens: 6550
      safety_multiplier: 1.2
    sources:
      - id: site
        type: website
        sitemap_url: https://example.test/sitemap.xml
        language:
          mode: auto
          allowed: [en, nl]
          html_selectors: [\"html[lang]\"]
")
      (let [loaded (config/load-config (.getPath file))]
        (is (= ["en" "de" "nl" "fr"]
               (-> loaded :indexes first :languages :allowed)))
        (is (= "auto"
               (-> loaded :indexes first :sources first :language :mode))))
      (finally
        (.delete file)))))

(deftest unsupported-language-locales-are-rejected
  (let [file (java.io.File/createTempFile "alida-language-unsupported" ".yml")]
    (try
      (spit file
            "database:
  jdbc_url: jdbc:postgresql://localhost/alida
verification:
  provider: openai
  model: gpt-4.1-mini
  api_key: test-key
indexes:
  - name: docs
    languages:
      allowed: [xx]
    embedding:
      provider: openai
      model: text-embedding-3-small
      embedding_dimensions: 1536
      api_key: test-key
    chunking:
      max_input_tokens: 8192
      max_tokens: 6550
      safety_multiplier: 1.2
    sources:
      - id: site
        type: website
        sitemap_url: https://example.test/sitemap.xml
")
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"unsupported locale xx"
                            (config/load-config (.getPath file))))
      (finally
        (.delete file)))))

(deftest language-fallback-must-be-allowed
  (let [file (java.io.File/createTempFile "alida-language-fallback" ".yml")]
    (try
      (spit file
            "database:
  jdbc_url: jdbc:postgresql://localhost/alida
verification:
  provider: openai
  model: gpt-4.1-mini
  api_key: test-key
indexes:
  - name: docs
    languages:
      allowed: [en, de]
      fallback: nl
    embedding:
      provider: openai
      model: text-embedding-3-small
      embedding_dimensions: 1536
      api_key: test-key
    chunking:
      max_input_tokens: 8192
      max_tokens: 6550
      safety_multiplier: 1.2
    sources:
      - id: site
        type: website
        sitemap_url: https://example.test/sitemap.xml
")
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"fallback nl is not in allowed locales"
                            (config/load-config (.getPath file))))
      (finally
        (.delete file)))))

(deftest configured-source-language-requires-locale
  (let [file (java.io.File/createTempFile "alida-language-configured" ".yml")]
    (try
      (spit file
            "database:
  jdbc_url: jdbc:postgresql://localhost/alida
verification:
  provider: openai
  model: gpt-4.1-mini
  api_key: test-key
indexes:
  - name: docs
    embedding:
      provider: openai
      model: text-embedding-3-small
      embedding_dimensions: 1536
      api_key: test-key
    chunking:
      max_input_tokens: 8192
      max_tokens: 6550
      safety_multiplier: 1.2
    sources:
      - id: site
        type: website
        sitemap_url: https://example.test/sitemap.xml
        language:
          mode: configured
")
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"configured language mode without locale"
                            (config/load-config (.getPath file))))
      (finally
        (.delete file)))))

(deftest source-languages-must-stay-within-index-languages
  (let [file (java.io.File/createTempFile "alida-language-subset" ".yml")]
    (try
      (spit file
            "database:
  jdbc_url: jdbc:postgresql://localhost/alida
verification:
  provider: openai
  model: gpt-4.1-mini
  api_key: test-key
indexes:
  - name: docs
    languages:
      allowed: [en, de]
    embedding:
      provider: openai
      model: text-embedding-3-small
      embedding_dimensions: 1536
      api_key: test-key
    chunking:
      max_input_tokens: 8192
      max_tokens: 6550
      safety_multiplier: 1.2
    sources:
      - id: site
        type: website
        sitemap_url: https://example.test/sitemap.xml
        language:
          allowed: [fr]
")
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"allows locales outside the index allowed locales"
                            (config/load-config (.getPath file))))
      (finally
        (.delete file)))))

(deftest local-and-website-source-config-loads
  (let [file (java.io.File/createTempFile "alida-source-config" ".yml")]
    (try
      (spit file
            "database:
  jdbc_url: jdbc:postgresql://localhost/alida
verification:
  provider: openai
  model: gpt-4.1-mini
  api_key: test-key
indexes:
  - name: docs
    embedding:
      provider: openai
      model: text-embedding-3-small
      embedding_dimensions: 1536
      api_key: test-key
      retry_jitter_ms: 100
      inter_batch_delay_ms: 250
    chunking:
      max_input_tokens: 8192
      max_tokens: 6550
      safety_multiplier: 1.2
    sources:
      - id: fixtures
        type: local
        root: test/fixtures
        include_extensions: [html, md]
        include_globs: [public/*.html]
        exclude_globs: [private/**]
      - id: website
        type: website
        sitemap_url: https://example.test/sitemap.xml
        allowed_url_prefixes: [https://example.test/docs/]
        denied_urls: [https://example.test/docs/secret]
        denied_url_prefixes: [https://example.test/private/]
        dedupe_content: true
        dedupe_prefer_url_substrings: [/docs/canonical/]
        max_concurrency: 7
        inter_request_delay_ms: 100
        max_sitemap_depth: 5
      - id: support
        type: jira-service-management
        url: https://example.atlassian.net/servicedesk/customer/portal/1
        allowed_url_prefixes: [https://example.atlassian.net/servicedesk/customer/portal/1/topic/]
        denied_url_prefixes: [https://example.atlassian.net/servicedesk/customer/portal/1/private/]
        remove_selectors: [nav, footer]
        strip_text:
          - \"Did this article help? Yes No\"
        content_wait_selectors: [main, article]
        browser_args: [--disable-background-networking]
        internal_link_hosts: [example.atlassian.net, api.example.test]
        preserve_external_links: true
        max_pages: 25
        page_load_timeout_seconds: 20
        wait_timeout_ms: 5000
        wait_interval_ms: 100
        url_stabilization_ms: 100
        url_stabilization_attempts: 3
        url_stabilization_stable_count: 1
        browser_restart_after_pages: 50
        browser_restart_after_failures: 2
        progress_log_every_pages: 25
      - id: objects
        type: s3
        bucket: alida-fixtures
        prefix: docs/
        region: eu-west-1
        include_globs: [docs/**/*.md, docs/**/*.json]
        exclude_globs: [docs/private/**]
      - id: cloud-objects
        type: gcs
        bucket: alida-gcs-fixtures
        project_id: alida-dev
        credentials_path: config/alida-gcs-fixture-service-account.json
        prefix: fixtures/docs/
        include_globs: [fixtures/docs/**/*.json]
        exclude_globs: [fixtures/docs/private/**]
        language:
          mode: html
        json_extract:
          mode: html-fields
          field_type_key: type
          field_type_value: content_text
          html_field: content
          title_path: [title]
          locale_from_filename:
            pattern: \"^([A-Z]{2})-\"
            mappings:
              EN: en_US
")
      (let [index (-> (config/load-config (.getPath file)) :indexes first)
            sources (:sources index)]
        (is (= 100 (-> index :embedding :retry_jitter_ms)))
        (is (= 250 (-> index :embedding :inter_batch_delay_ms)))
        (is (= ["local" "website" "jira-service-management" "s3" "gcs"] (mapv :type sources)))
        (is (= ["html" "md"] (-> sources first :include_extensions)))
        (is (= ["public/*.html"] (-> sources first :include_globs)))
        (is (= ["private/**"] (-> sources first :exclude_globs)))
        (is (= ["https://example.test/docs/"] (-> sources second :allowed_url_prefixes)))
        (is (= ["https://example.test/docs/secret"] (-> sources second :denied_urls)))
        (is (true? (-> sources second :dedupe_content)))
        (is (= ["/docs/canonical/"] (-> sources second :dedupe_prefer_url_substrings)))
        (is (= 7 (-> sources second :max_concurrency)))
        (is (= 100 (-> sources second :inter_request_delay_ms)))
        (is (= 5 (-> sources second :max_sitemap_depth)))
        (is (= 25 (-> sources (nth 2) :max_pages)))
        (is (= ["main" "article"] (-> sources (nth 2) :content_wait_selectors)))
        (is (= ["example.atlassian.net" "api.example.test"] (-> sources (nth 2) :internal_link_hosts)))
        (is (true? (-> sources (nth 2) :preserve_external_links)))
        (is (= 20 (-> sources (nth 2) :page_load_timeout_seconds)))
        (is (= 50 (-> sources (nth 2) :browser_restart_after_pages)))
        (is (= 2 (-> sources (nth 2) :browser_restart_after_failures)))
        (is (= 25 (-> sources (nth 2) :progress_log_every_pages)))
        (is (= "alida-fixtures" (-> sources (nth 3) :bucket)))
        (is (= "docs/" (-> sources (nth 3) :prefix)))
        (is (= "eu-west-1" (-> sources (nth 3) :region)))
        (is (= ["docs/**/*.md" "docs/**/*.json"] (-> sources (nth 3) :include_globs)))
        (is (= ["docs/private/**"] (-> sources (nth 3) :exclude_globs)))
        (is (= "alida-gcs-fixtures" (-> sources (nth 4) :bucket)))
        (is (= "alida-dev" (-> sources (nth 4) :project_id)))
        (is (= "config/alida-gcs-fixture-service-account.json" (-> sources (nth 4) :credentials_path)))
        (is (= "fixtures/docs/" (-> sources (nth 4) :prefix)))
        (is (= ["fixtures/docs/**/*.json"] (-> sources (nth 4) :include_globs)))
        (is (= ["fixtures/docs/private/**"] (-> sources (nth 4) :exclude_globs)))
        (is (= "html-fields" (-> sources (nth 4) :json_extract :mode)))
        (is (= ["title"] (-> sources (nth 4) :json_extract :title_path))))
      (finally
        (.delete file)))))

(deftest source-delay-must-be-zero-or-positive
  (let [file (java.io.File/createTempFile "alida-source-delay" ".yml")]
    (try
      (spit file
            "database:
  jdbc_url: jdbc:postgresql://localhost/alida
verification:
  provider: openai
  model: gpt-4.1-mini
  api_key: test-key
indexes:
  - name: docs
    embedding:
      provider: openai
      model: text-embedding-3-small
      embedding_dimensions: 1536
      api_key: test-key
    chunking:
      max_input_tokens: 8192
      max_tokens: 6550
      safety_multiplier: 1.2
    sources:
      - id: website
        type: website
        sitemap_url: https://example.test/sitemap.xml
        inter_request_delay_ms: -1
")
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"inter_request_delay_ms must be zero or positive"
                            (config/load-config (.getPath file))))
      (finally
        (.delete file)))))

(deftest source-sitemap-depth-must-be-positive
  (let [file (java.io.File/createTempFile "alida-source-sitemap-depth" ".yml")]
    (try
      (spit file
            "database:
  jdbc_url: jdbc:postgresql://localhost/alida
verification:
  provider: openai
  model: gpt-4.1-mini
  api_key: test-key
indexes:
  - name: docs
    embedding:
      provider: openai
      model: text-embedding-3-small
      embedding_dimensions: 1536
      api_key: test-key
    chunking:
      max_input_tokens: 8192
      max_tokens: 6550
      safety_multiplier: 1.2
    sources:
      - id: website
        type: website
        sitemap_url: https://example.test/sitemap.xml
        max_sitemap_depth: 0
")
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"max_sitemap_depth must be positive"
                            (config/load-config (.getPath file))))
      (finally
        (.delete file)))))

(deftest source-browser-restart-options-must-be-zero-or-positive
  (let [file (java.io.File/createTempFile "alida-source-browser-restart" ".yml")]
    (try
      (spit file
            "database:
  jdbc_url: jdbc:postgresql://localhost/alida
verification:
  provider: openai
  model: gpt-4.1-mini
  api_key: test-key
indexes:
  - name: docs
    embedding:
      provider: openai
      model: text-embedding-3-small
      embedding_dimensions: 1536
      api_key: test-key
    chunking:
      max_input_tokens: 8192
      max_tokens: 6550
      safety_multiplier: 1.2
    sources:
      - id: support
        type: jira-service-management
        url: https://example.atlassian.net/servicedesk/customer/portal/1
        progress_log_every_pages: -1
")
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"progress_log_every_pages must be zero or positive"
                            (config/load-config (.getPath file))))
      (finally
        (.delete file)))))
