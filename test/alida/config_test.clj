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

(deftest embedding-provider-positive-options-are-validated
  (let [file (java.io.File/createTempFile "alida-embedding-options" ".yml")]
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

(deftest language-config-loads-for-index-and-source
  (let [file (java.io.File/createTempFile "alida-language-config" ".yml")]
    (try
      (spit file
            "database:
  jdbc_url: jdbc:postgresql://localhost/alida
verification:
  provider: openai
  model: gpt-4.1-mini
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
      - id: fixtures
        type: local
        root: test/fixtures
        include_extensions: [html, md]
      - id: website
        type: website
        sitemap_url: https://example.test/sitemap.xml
        allowed_url_prefixes: [https://example.test/docs/]
        denied_url_prefixes: [https://example.test/private/]
")
      (let [sources (-> (config/load-config (.getPath file)) :indexes first :sources)]
        (is (= ["local" "website"] (mapv :type sources)))
        (is (= ["html" "md"] (-> sources first :include_extensions)))
        (is (= ["https://example.test/docs/"] (-> sources second :allowed_url_prefixes))))
      (finally
        (.delete file)))))
