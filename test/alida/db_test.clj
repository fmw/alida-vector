(ns alida.db-test
  (:require [alida.db :as db]
            [clojure.test :refer [deftest is]]))

(deftest dimension-table-name-requires-positive-integer
  (is (= "alida_chunks_1536" (db/dimension-table-name 1536)))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo
                        #"positive integer"
                        (db/dimension-table-name 0))))

(deftest run-partition-name-is-stable
  (is (= "alida_chunks_1536_run_018c9099041d7f5b9b655b8f08f8e61d"
         (db/run-partition-name 1536 "018c9099-041d-7f5b-9b65-5b8f08f8e61d"))))
