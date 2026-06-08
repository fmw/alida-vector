(ns alida.run-test
  (:require [alida.run :as run]
            [clojure.test :refer [deftest is]]))

(deftest decide-action-never-auto-activates-first-run
  (is (= :hold
         (run/decide-action {:auto_activate true}
                            {:final_verdict "pass"
                             :first_run true}))))

(deftest decide-action-auto-activates-safe-delta-run
  (is (= :activate
         (run/decide-action {:auto_activate true}
                            {:final_verdict "pass"
                             :first_run false}))))

(deftest decide-action-holds-caution
  (is (= :hold
         (run/decide-action {:auto_activate true}
                            {:final_verdict "caution"
                             :first_run false}))))
