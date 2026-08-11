(ns alida.db.postgres-test
  (:require [alida.db.postgres :as db]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [next.jdbc :as jdbc]))

(deftest advisory-lock-key-is-stable
  (is (= 1849900176771858938
         (db/advisory-lock-key "support-knowledge-base"))))

(deftest prune-candidates-can-be-scoped-to-selected-indexes
  (let [candidates [{:id 1 :index_name "docs"}
                    {:id 2 :index_name "blog"}
                    {:id 3 :index_name "docs"}]]
    (is (= candidates
           (#'db/restrict-prune-candidates candidates nil)))
    (is (= []
           (#'db/restrict-prune-candidates candidates [])))
    (is (= [{:id 1 :index_name "docs"}
            {:id 3 :index_name "docs"}]
           (#'db/restrict-prune-candidates candidates ["docs"])))))

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

(deftest lists-chunk-content-for-selected-documents
  (let [calls (atom [])
        run-id (java.util.UUID/randomUUID)]
    (with-redefs [jdbc/execute! (fn [connectable statement opts]
                                  (swap! calls conj {:connectable connectable
                                                     :statement statement
                                                     :opts opts})
                                  [{:source_id "docs"
                                    :canonical_url "https://example.test/a"
                                    :chunk_index 0
                                    :content "Previous content"}])]
      (is (= [{:source_id "docs"
               :canonical_url "https://example.test/a"
               :chunk_index 0
               :content "Previous content"}]
             (db/list-document-chunk-content
              :ds
              1536
              run-id
              [["docs" "https://example.test/a"]
               ["support" "https://example.test/b"]]))))
    (let [{:keys [connectable statement opts]} (first @calls)]
      (is (= :ds connectable))
      (is (= db/jdbc-opts opts))
      (is (str/includes? (first statement) "JOIN alida_chunks_1536"))
      (is (= [run-id
              "docs"
              "https://example.test/a"
              "support"
              "https://example.test/b"]
             (vec (rest statement)))))))

(deftest listing-chunk-content-skips-the-database-for-no-document-keys
  (with-redefs [jdbc/execute! (fn [& _]
                                (throw (ex-info "must not query" {})))]
    (is (= [] (db/list-document-chunk-content :ds 1536 (java.util.UUID/randomUUID) [])))))
