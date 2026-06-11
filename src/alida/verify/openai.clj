(ns alida.verify.openai
  (:require [alida.verify :as verify]
            [clojure.data.json :as json]))

(def endpoint
  "https://api.openai.com/v1/chat/completions")

(defn- response-content
  [response]
  (or (get-in response [:choices 0 :message :content])
      (throw (ex-info "Verification provider response did not include message content"
                      {:type :alida.verify.openai/missing-content
                       :response response}))))

(defmethod verify/complete :openai
  [sys provider-cfg prompt]
  (let [api-key (verify/require-config! provider-cfg :api_key)
        model (verify/require-config! provider-cfg :model)
        response (verify/request-json!
                  sys
                  {:method :post
                   :url endpoint
                   :headers {"Authorization" (str "Bearer " api-key)
                             "Content-Type" "application/json"}
                   :body (json/write-str
                          {:model model
                           :temperature 0
                           :response_format {:type "json_object"}
                           :messages [{:role "system"
                                       :content verify/system-prompt}
                                      {:role "user"
                                       :content prompt}]})})]
    (verify/parse-structured-verdict (response-content response))))
