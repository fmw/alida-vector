(ns alida.verify.azure-openai
  (:require [alida.verify :as verify]
            [clojure.data.json :as json]
            [clojure.string :as str])
  (:import [java.net URLEncoder]))

(def default-api-version
  verify/default-azure-openai-api-version)

(defn- encode
  [value]
  (str/replace (URLEncoder/encode (str value) "UTF-8") "+" "%20"))

(defn- endpoint
  [provider-cfg]
  (let [base-url (verify/require-config! provider-cfg :endpoint)
        deployment-name (verify/require-config! provider-cfg :deployment_name)
        api-version (or (:api_version provider-cfg) default-api-version)]
    (str (if (.endsWith base-url "/")
           (subs base-url 0 (dec (count base-url)))
           base-url)
         "/openai/deployments/"
         (encode deployment-name)
         "/chat/completions?api-version="
         (encode api-version))))

(defn- response-content
  [response]
  (or (get-in response [:choices 0 :message :content])
      (throw (ex-info "Azure OpenAI verification response did not include message content"
                      {:type :alida.verify.azure-openai/missing-content
                       :response response}))))

(defmethod verify/complete :azure-openai
  [sys provider-cfg prompt]
  (let [api-key (verify/require-config! provider-cfg :api_key)
        response (verify/request-json!
                  sys
                  {:method :post
                   :url (endpoint provider-cfg)
                   :headers {"api-key" api-key
                             "Content-Type" "application/json"}
                   :body (json/write-str
                          (merge
                           (verify/chat-completion-parameters provider-cfg)
                           {:response_format {:type "json_object"}
                            :messages [{:role "system"
                                        :content verify/system-prompt}
                                       {:role "user"
                                        :content prompt}]}))})]
    (verify/parse-structured-verdict (response-content response))))
