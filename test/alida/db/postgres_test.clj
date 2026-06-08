(ns alida.db.postgres-test
  (:require [alida.db.postgres :as db]
            [clojure.test :refer [deftest is]]))

(deftest advisory-lock-key-is-stable
  (is (= 1849900176771858938
         (db/advisory-lock-key "support-knowledge-base"))))
