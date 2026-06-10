(ns alida.env-test
  (:require [alida.env :as env]
            [clojure.test :refer [deftest is testing]]))

(deftest redact-removes-secret-values
  (testing "nested secret-looking keys are redacted"
    (is (= {:database {:password "<redacted>"}
            :normal "visible"
            :items [{:api_key "<redacted>"
                     :access_token "<redacted>"
                     :slack_webhook_url "<redacted>"
                     :authorization "<redacted>"}]}
           (env/redact {:database {:password "secret"}
                        :normal "visible"
                        :items [{:api_key "key"
                                 :access_token "token"
                                 :slack_webhook_url "https://example.test/slack"
                                 :authorization "Bearer token"}]})))))

(deftest redact-keeps-non-secret-token-limit-values
  (is (= {:chunking {:max_tokens 6550
                     :max_input_tokens 8192}
          :embedding {:credentials_path "/var/run/secrets/provider.json"
                      :deterministic_gate_version "2026-06-08"}}
         (env/redact {:chunking {:max_tokens 6550
                                 :max_input_tokens 8192}
                      :embedding {:credentials_path "/var/run/secrets/provider.json"
                                  :deterministic_gate_version "2026-06-08"}}))))
