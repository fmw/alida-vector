(ns alida.verify.vertex-ai
  (:require [alida.verify :as verify]
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
  (let [project (verify/require-config! provider-cfg :project)
        location (verify/require-config! provider-cfg :location)
        model (verify/require-config! provider-cfg :model)]
    (str "https://"
         location
         "-aiplatform.googleapis.com/v1/projects/"
         (encode-path project)
         "/locations/"
         (encode-path location)
         "/publishers/google/models/"
         (encode-path model)
         ":generateContent")))

(defn- response-content
  [response]
  (or (get-in response [:candidates 0 :content :parts 0 :text])
      (throw (ex-info "Vertex AI verification response did not include text content"
                      {:type :alida.verify.vertex-ai/missing-content
                       :response response}))))

(defmethod verify/complete :vertex-ai
  [sys provider-cfg {:keys [system-prompt]} prompt]
  (let [response (verify/request-json!
                  sys
                  {:method :post
                   :url (endpoint provider-cfg)
                   :headers {"Authorization" (str "Bearer " (access-token provider-cfg))
                             "Content-Type" "application/json"}
                   :body (json/write-str
                          {:systemInstruction
                           {:parts [{:text (or system-prompt verify/system-prompt)}]}
                           :contents [{:role "user"
                                       :parts [{:text prompt}]}]
                           :generationConfig
                           {:temperature 0
                            :responseMimeType "application/json"}})})]
    (verify/parse-structured-verdict (response-content response))))
