(ns alida.db.postgres-test
  (:require [alida.db.postgres :as db]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [next.jdbc :as jdbc]))

(deftest advisory-lock-key-is-stable
  (is (= 1849900176771858938
         (db/advisory-lock-key "support-knowledge-base"))))

(deftest insert-chunks-uses-one-batch-statement
  (let [calls (atom [])
        run-id (java.util.UUID/randomUUID)
        document-id (java.util.UUID/randomUUID)]
    (with-redefs [jdbc/execute-batch! (fn [connectable sql param-rows opts]
                                        (swap! calls conj {:connectable connectable
                                                           :sql sql
                                                           :param-rows param-rows
                                                           :opts opts})
                                        [{:next.jdbc/update-count 1}
                                         {:next.jdbc/update-count 1}])]
      (db/insert-chunks! :ds
                         1536
                         {:id run-id}
                         {:id "docs"}
                         {:id document-id}
                         [{:chunk_index 0
                           :chunk_count 2
                           :content_hash "hash-1"
                           :content "First"
                           :embedding [0.1 0.2]
                           :estimated_tokens 10
                           :heading_path ["Intro"]
                           :metadata {:locale "en"}}
                          {:chunk_index 1
                           :chunk_count 2
                           :content_hash "hash-2"
                           :content "Second"
                           :embedding [0.3 0.4]
                           :estimated_tokens 11}]))
    (is (= 1 (count @calls)))
    (let [{:keys [connectable sql param-rows opts]} (first @calls)]
      (is (= :ds connectable))
      (is (str/includes? sql "INSERT INTO alida_chunks_1536"))
      (is (not (str/includes? sql "RETURNING")))
      (is (= db/jdbc-opts opts))
      (is (= 2 (count param-rows)))
      (is (= [run-id "docs" document-id 0 2 "hash-1" "First" "[0.1,0.2]" 10]
             (subvec (first param-rows) 0 9)))
      (is (= [run-id "docs" document-id 1 2 "hash-2" "Second" "[0.3,0.4]" 11]
             (subvec (second param-rows) 0 9)))
      (is (= "jsonb" (.getType (nth (first param-rows) 9))))
      (is (= "[\"Intro\"]" (.getValue (nth (first param-rows) 9))))
      (is (= "{\"locale\":\"en\"}" (.getValue (nth (first param-rows) 10))))
      (is (= "[]" (.getValue (nth (second param-rows) 9))))
      (is (= "{}" (.getValue (nth (second param-rows) 10)))))))
