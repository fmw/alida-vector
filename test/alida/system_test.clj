(ns alida.system-test
  (:require [alida.system :as system]
            [clojure.test :refer [deftest is]]))

(deftest system-config-uses-json-console-logs
  (is (= {:type :console-json}
         (:alida/log (system/system-config "alida.yml")))))
