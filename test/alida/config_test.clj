(ns alida.config-test
  (:require [alida.config :as config]
            [clojure.test :refer [deftest is testing]]))

(def valid-config
  {:database {:jdbc_url "jdbc:postgresql://localhost/alida"}
   :verification {:provider "openai"
                  :model "gpt-4.1-mini"
                  :api_key "test-key"}
   :notifications {:slack_webhook_url "https://example.test/slack"}
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
")
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"provider azure-openai requires endpoint"
                            (config/load-config (.getPath file))))
      (finally
        (.delete file)))))

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
")
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"max_prompt_tokens must be positive"
                            (config/load-config (.getPath file))))
      (finally
        (.delete file)))))

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
      - id: website
        type: website
        sitemap_url: https://example.test/sitemap.xml
        allowed_url_prefixes: [https://example.test/docs/]
        denied_urls: [https://example.test/docs/secret]
        denied_url_prefixes: [https://example.test/private/]
        max_concurrency: 7
        inter_request_delay_ms: 100
        max_sitemap_depth: 5
")
      (let [index (-> (config/load-config (.getPath file)) :indexes first)
            sources (:sources index)]
        (is (= 100 (-> index :embedding :retry_jitter_ms)))
        (is (= 250 (-> index :embedding :inter_batch_delay_ms)))
        (is (= ["local" "website"] (mapv :type sources)))
        (is (= ["html" "md"] (-> sources first :include_extensions)))
        (is (= ["https://example.test/docs/"] (-> sources second :allowed_url_prefixes)))
        (is (= ["https://example.test/docs/secret"] (-> sources second :denied_urls)))
        (is (= 7 (-> sources second :max_concurrency)))
        (is (= 100 (-> sources second :inter_request_delay_ms)))
        (is (= 5 (-> sources second :max_sitemap_depth))))
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
