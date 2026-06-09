(ns alida.embed.openai
  (:require [alida.embed :as embed]
            [clojure.data.json :as json]))

(def endpoint
  "https://api.openai.com/v1/embeddings")

(defn- response->embeddings
  [response]
  (->> (:data response)
       (sort-by :index)
       (mapv :embedding)))

(defn- embed-one-batch
  [sys provider-cfg texts]
  (let [api-key (embed/require-config! provider-cfg :api_key)
        model (embed/require-config! provider-cfg :model)]
    (->> (embed/request-json!
          sys
          {:method :post
           :url endpoint
           :headers {"Authorization" (str "Bearer " api-key)
                     "Content-Type" "application/json"}
           :body (json/write-str {:model model
                                  :input texts
                                  :encoding_format "float"})})
         response->embeddings
         (embed/validate-embedding-count! texts))))

(defmethod embed/embed-batch :openai
  [sys provider-cfg texts]
  (embed/embed-in-batches sys provider-cfg texts embed-one-batch))
