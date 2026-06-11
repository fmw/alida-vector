(ns alida.vector.pgvector-test
  (:require [alida.vector.pgvector :as pgvector]
            [clojure.test :refer [deftest is]]
            [next.jdbc :as jdbc]))

(deftest dimension-table-name-requires-supported-dimension
  (is (= "alida_chunks_1536" (pgvector/dimension-table-name 1536)))
  (is (= "alida_chunks_3072" (pgvector/dimension-table-name 3072)))
  (is (= "alida_live_chunks_1536" (pgvector/live-view-name 1536)))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo
                        #"Unsupported pgvector dimensions"
                        (pgvector/dimension-table-name 768)))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo
                        #"Unsupported pgvector dimensions"
                        (pgvector/live-view-name 768))))

(deftest run-partition-name-is-stable
  (is (= "alida_chunks_1536_run_018c9099041d7f5b9b655b8f08f8e61d"
         (pgvector/run-partition-name 1536 "018c9099-041d-7f5b-9b65-5b8f08f8e61d"))))

(deftest run-partition-hnsw-index-name-fits-postgres-limit
  (with-redefs [jdbc/execute! (fn [& _] [])]
    (let [index-name (:index (pgvector/ensure-run-partition!
                              nil
                              1536
                              "018c9099-041d-7f5b-9b65-5b8f08f8e61d"))]
      (is (= "alida_chunks_1536_run_018c9099041d7f5b9b655b8f08f8e61d_hnsw_idx"
             index-name))
      (is (<= (count index-name) 63)))))
