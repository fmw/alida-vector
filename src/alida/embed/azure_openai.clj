(ns alida.embed.azure-openai
  (:require [alida.embed :as embed]
            [clojure.data.json :as json]
            [clojure.string :as str])
  (:import [java.net URLEncoder]))

(def default-api-version
  "2024-02-01")

(defn- encode
  [value]
  (str/replace (URLEncoder/encode (str value) "UTF-8") "+" "%20"))

(defn- endpoint
  [provider-cfg]
  (let [base-url (embed/require-config! provider-cfg :endpoint)
        deployment-name (embed/require-config! provider-cfg :deployment_name)
        api-version (or (:api_version provider-cfg) default-api-version)]
    (str (if (.endsWith base-url "/")
           (subs base-url 0 (dec (count base-url)))
           base-url)
         "/openai/deployments/"
         (encode deployment-name)
         "/embeddings?api-version="
         (encode api-version))))

(defn- response->embeddings
  [response]
  (->> (:data response)
       (sort-by :index)
       (mapv :embedding)))

(defn- embed-one-batch
  [sys provider-cfg texts]
  (let [api-key (embed/require-config! provider-cfg :api_key)]
    (->> (embed/request-json!
          sys
          {:method :post
           :url (endpoint provider-cfg)
           :headers {"api-key" api-key
                     "Content-Type" "application/json"}
           :body (json/write-str {:input texts
                                  :encoding_format "float"})})
         response->embeddings
         (embed/validate-embedding-count! texts))))

(defmethod embed/embed-batch :azure-openai
  [sys provider-cfg texts]
  (embed/embed-in-batches sys provider-cfg texts embed-one-batch))
