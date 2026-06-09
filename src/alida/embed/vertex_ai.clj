(ns alida.embed.vertex-ai
  (:require [alida.embed :as embed]
            [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str])
  (:import [com.google.auth.oauth2 GoogleCredentials]
           [java.net URLEncoder]))

(def cloud-platform-scope
  "https://www.googleapis.com/auth/cloud-platform")

(defn- encode-path
  [value]
  (str/replace (URLEncoder/encode (str value) "UTF-8") "+" "%20"))

(defn- credentials
  [provider-cfg]
  (let [creds (if-let [path (:credentials_path provider-cfg)]
                (with-open [in (io/input-stream path)]
                  (GoogleCredentials/fromStream in))
                (GoogleCredentials/getApplicationDefault))]
    (.createScoped creds [cloud-platform-scope])))

(defn- access-token
  [provider-cfg]
  (or (:access_token provider-cfg)
      (let [creds (credentials provider-cfg)]
        (.refreshIfExpired creds)
        (.. creds getAccessToken getTokenValue))))

(defn- endpoint
  [provider-cfg]
  (let [project (embed/require-config! provider-cfg :project)
        location (embed/require-config! provider-cfg :location)
        model (embed/require-config! provider-cfg :model)]
    (str "https://"
         location
         "-aiplatform.googleapis.com/v1/projects/"
         (encode-path project)
         "/locations/"
         (encode-path location)
         "/publishers/google/models/"
         (encode-path model)
         ":predict")))

(defn- prediction->embedding
  [prediction]
  (or (get-in prediction [:embeddings :values])
      (:embedding prediction)
      (get-in prediction [:embedding :values])))

(defn- response->embeddings
  [response]
  (mapv prediction->embedding (:predictions response)))

(defn- embed-one-batch
  [sys provider-cfg texts]
  (->> (embed/request-json!
        sys
        {:method :post
         :url (endpoint provider-cfg)
         :headers {"Authorization" (str "Bearer " (access-token provider-cfg))
                   "Content-Type" "application/json"}
         :body (json/write-str {:instances (mapv #(hash-map :content %) texts)})})
       response->embeddings
       (embed/validate-embedding-count! texts)))

(defmethod embed/embed-batch :vertex-ai
  [sys provider-cfg texts]
  (embed/embed-in-batches sys provider-cfg texts embed-one-batch))
