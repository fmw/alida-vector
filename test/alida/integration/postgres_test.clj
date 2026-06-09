(ns alida.integration.postgres-test
  (:require [alida.config :as config]
            [alida.db.postgres :as db]
            [alida.vector.pgvector :as pgvector]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [next.jdbc :as jdbc]))

(def test-database-url
  (System/getenv "ALIDA_TEST_DATABASE_URL"))

(defn- random-db-name
  []
  (str "alida_test_" (str/replace (str (random-uuid)) "-" "_")))

(defn- admin-url
  [db-url]
  (str/replace db-url #"/[^/?]+(\?.*)?$" "/postgres$1"))

(defn- database-url
  [db-url db-name]
  (str/replace db-url #"/[^/?]+(\?.*)?$" (str "/" db-name "$1")))

(defn- test-db-config
  [jdbc-url]
  {:jdbc_url jdbc-url
   :user (System/getenv "ALIDA_TEST_DATABASE_USER")
   :password (System/getenv "ALIDA_TEST_DATABASE_PASSWORD")})

(defn- with-temp-database
  [f]
  (if-not test-database-url
    :skipped
    (let [db-name (random-db-name)
          admin-config (test-db-config (admin-url test-database-url))
          db-config (test-db-config (database-url test-database-url db-name))]
      (with-open [admin-ds (db/datasource admin-config)]
        (jdbc/execute! admin-ds [(str "CREATE DATABASE " db-name)])
        (try
          (with-open [ds (db/datasource db-config)]
            (f db-config ds))
          (finally
            (jdbc/execute! admin-ds [(str "DROP DATABASE IF EXISTS " db-name " WITH (FORCE)")])))))))

(def index-cfg
  {:name "support-knowledge-base"
   :embedding {:provider "openai"
               :model "text-embedding-3-small"
               :embedding_dimensions 1536}
   :chunking {:max_input_tokens 8192
              :max_tokens 6550
              :safety_multiplier 1.2}
   :sources [{:id "support"
              :type "website"}]})

(defn- zero-vector-sql
  [dimensions]
  (format "('[' || array_to_string(array_fill(0.0::float8, ARRAY[%d]), ',') || ']')::vector"
          dimensions))

(deftest ^:integration migrates-supported-pgvector-schema
  (let [result (with-temp-database
                 (fn [db-config _ds]
                   (db/migrate! {:database db-config})
                   (with-open [ds (db/datasource db-config)]
                     (jdbc/execute!
                      ds
                      ["SELECT relname, relkind
                        FROM pg_class
                        WHERE relname IN (
                          'alida_chunks_1536',
                          'alida_chunks_3072',
                          'alida_live_chunks_1536',
                          'alida_live_chunks_3072')
                        ORDER BY relname"]
                      db/jdbc-opts))))]
    (if (= :skipped result)
      (is true "Skipping Postgres integration test; ALIDA_TEST_DATABASE_URL is not set.")
      (is (= [{:relname "alida_chunks_1536" :relkind "p"}
              {:relname "alida_chunks_3072" :relkind "p"}
              {:relname "alida_live_chunks_1536" :relkind "v"}
              {:relname "alida_live_chunks_3072" :relkind "v"}]
             result)))))

(deftest ^:integration lifecycle-and-live-view-round-trip
  (let [result (with-temp-database
                 (fn [db-config _ds]
                   (db/migrate! {:database db-config})
                   (with-open [ds (db/datasource db-config)]
                     (let [run-1 (db/create-run! ds index-cfg "hash-1")
                           run-2 (db/create-run! ds index-cfg "hash-1")
                           run-3 (db/create-run! ds index-cfg "hash-1")]
                       (db/update-run-status! ds (:id run-1) "complete" {:verification_verdict "pass"})
                       (db/activate-run! ds (:id run-1))
                       (db/update-run-status! ds (:id run-2) "complete" {:verification_verdict "pass"})
                       (db/activate-run! ds (:id run-2))
                       (db/rollback-index! ds (:name index-cfg))
                       (db/reject-run! ds (:id run-3))
                       (pgvector/ensure-run-partition! ds 1536 (:id run-1))
                       (jdbc/execute!
                        ds
                        [(str
                          "WITH doc AS (
                             INSERT INTO alida_documents
                               (run_id, source_id, canonical_url, normalized_content_hash)
                             VALUES (?, 'support', 'https://example.test/article', 'hash')
                             RETURNING id
                           )
                           INSERT INTO alida_chunks_1536
                             (run_id, source_id, document_id, chunk_index, chunk_count, content, embedding, estimated_tokens)
                           SELECT ?, 'support', id, 0, 1, 'Example content', "
                          (zero-vector-sql 1536)
                          ", 2 FROM doc")
                         (:id run-1)
                         (:id run-1)])
                       {:runs (mapv #(select-keys % [:id :lifecycle_status :verification_verdict])
                                    (db/list-runs ds {:limit 10}))
                        :live-chunks (jdbc/execute!
                                      ds
                                      ["SELECT index_name, source_id, content, estimated_tokens
                                        FROM alida_live_chunks_1536"]
                                      db/jdbc-opts)
                        :events (:n (jdbc/execute-one!
                                     ds
                                     ["SELECT count(*) AS n FROM alida_events"]
                                     db/jdbc-opts))}))))]
    (if (= :skipped result)
      (is true "Skipping Postgres integration test; ALIDA_TEST_DATABASE_URL is not set.")
      (testing "run lifecycle and live view behavior"
        (is (= #{"activated" "rejected" "superseded"}
               (set (map :lifecycle_status (:runs result)))))
        (is (= [{:index_name "support-knowledge-base"
                 :source_id "support"
                 :content "Example content"
                 :estimated_tokens 2}]
               (:live-chunks result)))
        (is (<= 8 (:events result)))))))

(deftest ^:integration lifecycle-guards-invalid-transitions
  (let [result (with-temp-database
                 (fn [db-config _ds]
                   (db/migrate! {:database db-config})
                   (with-open [ds (db/datasource db-config)]
                     (let [created-run (db/create-run! ds index-cfg "hash-1")
                           failed-run (db/create-run! ds index-cfg "hash-1")
                           run-1 (db/create-run! ds index-cfg "hash-1")
                           run-2 (db/create-run! ds index-cfg "hash-1")]
                       (db/update-run-status! ds (:id failed-run) "complete" {:verification_verdict "fail"})
                       (db/update-run-status! ds (:id run-1) "complete" {:verification_verdict "pass"})
                       (db/activate-run! ds (:id run-1))
                       (db/update-run-status! ds (:id run-2) "complete" {:verification_verdict "pass"})
                       (db/activate-run! ds (:id run-2))
                       (db/rollback-index! ds (:name index-cfg))
                       {:created-activation (try
                                              (db/activate-run! ds (:id created-run))
                                              :activated
                                              (catch clojure.lang.ExceptionInfo e
                                                (ex-data e)))
                        :failed-activation (try
                                             (db/activate-run! ds (:id failed-run))
                                             :activated
                                             (catch clojure.lang.ExceptionInfo e
                                               (ex-data e)))
                        :reject-live (try
                                       (db/reject-run! ds (:id run-1))
                                       :rejected
                                       (catch clojure.lang.ExceptionInfo e
                                         (ex-data e)))
                        :reject-previous-live (try
                                                (db/reject-run! ds (:id run-2))
                                                :rejected
                                                (catch clojure.lang.ExceptionInfo e
                                                  (ex-data e)))}))))]
    (if (= :skipped result)
      (is true "Skipping Postgres integration test; ALIDA_TEST_DATABASE_URL is not set.")
      (testing "invalid lifecycle transitions are rejected"
        (is (= :alida.db.postgres/run-not-activatable
               (get-in result [:created-activation :type])))
        (is (= :not-complete
               (get-in result [:created-activation :reason])))
        (is (= :alida.db.postgres/run-not-activatable
               (get-in result [:failed-activation :type])))
        (is (= :verification-not-pass
               (get-in result [:failed-activation :reason])))
        (is (= :live-run
               (get-in result [:reject-live :pointer])))
        (is (= :previous-live-run
               (get-in result [:reject-previous-live :pointer])))))))

(deftest ^:integration advisory-locks-use-held-database-sessions
  (let [result (with-temp-database
                 (fn [db-config _ds]
                   (db/migrate! {:database db-config})
                   (with-open [ds (db/datasource db-config)]
                     (db/with-index-lock!
                       ds
                       (:name index-cfg)
                       #(try
                          (db/with-index-lock! ds (:name index-cfg) (constantly :unexpected))
                          :acquired
                          (catch clojure.lang.ExceptionInfo e
                            (ex-data e)))))))]
    (if (= :skipped result)
      (is true "Skipping Postgres integration test; ALIDA_TEST_DATABASE_URL is not set.")
      (is (= :alida.db.postgres/index-locked (:type result))))))

(deftest ^:integration migration-rollback-removes-alida-objects
  (let [result (with-temp-database
                 (fn [db-config _ds]
                   (db/migrate! {:database db-config})
                   (db/rollback-migration! {:database db-config})
                   (with-open [ds (db/datasource db-config)]
                     (jdbc/execute!
                      ds
                      ["SELECT relname FROM pg_class WHERE relname LIKE 'alida_%' ORDER BY relname"]
                      db/jdbc-opts))))]
    (if (= :skipped result)
      (is true "Skipping Postgres integration test; ALIDA_TEST_DATABASE_URL is not set.")
      (is (= [] result)))))

(deftest ^:integration example-storage-config-loads
  (if-not test-database-url
    (is true "Skipping Postgres integration test; ALIDA_TEST_DATABASE_URL is not set.")
    (is (= {:type "pgvector"}
           (-> (config/load-config "resources/example-config.yml")
               :storage
               :vectors)))))
