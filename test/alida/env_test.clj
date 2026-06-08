(ns alida.env-test
  (:require [alida.env :as env]
            [clojure.test :refer [deftest is testing]]))

(deftest redact-removes-secret-values
  (testing "nested secret-looking keys are redacted"
    (is (= {:database {:password "<redacted>"}
            :normal "visible"
            :items [{:api_key "<redacted>"}]}
           (env/redact {:database {:password "secret"}
                        :normal "visible"
                        :items [{:api_key "key"}]})))))
