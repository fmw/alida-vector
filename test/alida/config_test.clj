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
