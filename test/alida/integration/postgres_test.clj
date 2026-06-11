(ns alida.integration.postgres-test
  (:require [alida.config :as config]
            [alida.crawl :as crawl]
            [alida.db.postgres :as db]
            [alida.embed :as embed]
            [alida.source.local]
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

(defn- first-axis-vector-sql
  [dimensions]
  (format "('[' || array_to_string(ARRAY[1.0::float8] || array_fill(0.0::float8, ARRAY[%d]), ',') || ']')::vector"
          (dec dimensions)))

(defn- first-axis-vector
  [dimensions]
  (vec (cons 1.0 (repeat (dec dimensions) 0.0))))

(defn- zero-vector
  [dimensions]
  (vec (repeat dimensions 0.0)))

(defn- insert-searchable-chunk!
  [ds run-id content]
  (pgvector/ensure-run-partition! ds 1536 run-id)
  (jdbc/execute!
   ds
   [(str
     "WITH doc AS (
        INSERT INTO alida_documents
          (run_id, source_id, canonical_url, normalized_content_hash)
        VALUES (?, 'support', ?, ?)
        RETURNING id
      )
      INSERT INTO alida_chunks_1536
        (run_id, source_id, document_id, chunk_index, chunk_count, content_hash, content, embedding, estimated_tokens)
      SELECT ?, 'support', id, 0, 1, ?, ?, "
     (first-axis-vector-sql 1536)
     ", 2 FROM doc")
    run-id
    (str "https://example.test/" run-id)
    (str "doc-hash-" run-id)
    run-id
    (str "chunk-hash-" run-id)
    content]))

(defn- temp-file
  [suffix body]
  (let [file (java.io.File/createTempFile "alida-integration" suffix)]
    (.deleteOnExit file)
    (spit file body)
    file))

(deftest ^:integration migrates-supported-pgvector-schema
  (let [result (with-temp-database
                 (fn [db-config _ds]
                   (db/migrate! {:database db-config})
                   (with-open [ds (db/datasource db-config)]
                     {:relations (jdbc/execute!
                                  ds
                                  ["SELECT relname, relkind
                                    FROM pg_class
                                    WHERE relname IN (
                                      'alida_chunks_1536',
                                      'alida_chunks_3072',
                                      'alida_live_chunks_1536',
                                      'alida_live_chunks_3072')
                                    ORDER BY relname"]
                                  db/jdbc-opts)
                      :reuse-index (:indexname
                                    (jdbc/execute-one!
                                     ds
                                     ["SELECT indexname
                                       FROM pg_indexes
                                       WHERE indexname = 'alida_runs_embedding_reuse_idx'"]
                                     db/jdbc-opts))})))]
    (if (= :skipped result)
      (is true "Skipping Postgres integration test; ALIDA_TEST_DATABASE_URL is not set.")
      (do
        (is (= [{:relname "alida_chunks_1536" :relkind "p"}
                {:relname "alida_chunks_3072" :relkind "p"}
                {:relname "alida_live_chunks_1536" :relkind "v"}
                {:relname "alida_live_chunks_3072" :relkind "v"}]
               (:relations result)))
        (is (= "alida_runs_embedding_reuse_idx" (:reuse-index result)))))))

(deftest ^:integration lifecycle-and-live-view-round-trip
  (let [result (with-temp-database
                 (fn [db-config _ds]
                   (db/migrate! {:database db-config})
                   (with-open [ds (db/datasource db-config)]
                     (let [embedding-metadata {:embedding_fingerprint (embed/fingerprint (:embedding index-cfg))}
                           run-1 (db/create-run! ds index-cfg "hash-1" embedding-metadata)
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
                             (run_id, source_id, document_id, chunk_index, chunk_count, content_hash, content, embedding, estimated_tokens)
                           SELECT ?, 'support', id, 0, 1, 'chunk-hash', 'Example content', "
                          (first-axis-vector-sql 1536)
                          ", 2 FROM doc")
                         (:id run-1)
                         (:id run-1)])
                       {:runs (mapv #(select-keys % [:id :lifecycle_status :verification_verdict])
                                    (db/list-runs ds {:limit 10}))
                        :live-chunks (jdbc/execute!
                                      ds
                                      ["SELECT index_name, source_id, canonical_url, title, locale, content_hash, content, estimated_tokens
                                        FROM alida_live_chunks_1536"]
                                      db/jdbc-opts)
                        :search-live (db/search-live-chunks ds
                                                            1536
                                                            (first-axis-vector 1536)
                                                            {:index_names [(:name index-cfg)]
                                                             :limit 5})
                        :search-run (db/search-run-chunks ds
                                                          1536
                                                          (:id run-1)
                                                          (first-axis-vector 1536)
                                                          {:limit 5})
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
                 :canonical_url "https://example.test/article"
                 :title nil
                 :locale nil
                 :content_hash "chunk-hash"
                 :content "Example content"
                 :estimated_tokens 2}]
               (:live-chunks result)))
        (is (= ["Example content"] (mapv :content (:search-live result))))
        (is (= ["Example content"] (mapv :content (:search-run result))))
        (is (= 1.0 (double (-> result :search-live first :score))))
        (is (<= 8 (:events result)))))))

(deftest ^:integration crawl-index-stores-candidate-documents-and-chunks
  (let [result (with-temp-database
                 (fn [db-config ds]
                   (db/migrate! {:database db-config})
                   (let [file (temp-file ".html"
                                         "<html lang=\"en\"><head><title>Support</title></head>
                                          <body><h1>Support</h1><p>This page explains how support works.</p></body></html>")
                         test-index (assoc index-cfg
                                           :sources [{:id "fixtures"
                                                      :type "local"
                                                      :path (.getPath file)}])
                         sys {:alida/config {:alida.config/structural-hash "hash-1"
                                             :indexes [test-index]}}]
                     (with-redefs [embed/embed-batch (fn [_ _ texts]
                                                       (mapv (fn [_] (zero-vector 1536)) texts))]
                       (let [summary (crawl/crawl-index! sys ds test-index)]
                         {:summary summary
                          :run (db/get-run ds (:run_id summary))
                          :report (db/get-report ds (:run_id summary))
                          :sources (jdbc/execute!
                                    ds
                                    ["SELECT source_id, source_type, document_count, error_count
                                      FROM alida_sources
                                      WHERE run_id = ?"
                                     (:run_id summary)]
                                    db/jdbc-opts)
                          :documents (jdbc/execute!
                                      ds
                                      ["SELECT source_id, canonical_url, title, locale, normalized_content_hash
                                        FROM alida_documents
                                        WHERE run_id = ?"
                                       (:run_id summary)]
                                      db/jdbc-opts)
                          :chunks (jdbc/execute!
                                   ds
                                   ["SELECT source_id, content_hash, content, estimated_tokens
                                     FROM alida_chunks_1536
                                     WHERE run_id = ?"
                                    (:run_id summary)]
                                   db/jdbc-opts)})))))]
    (if (= :skipped result)
      (is true "Skipping Postgres integration test; ALIDA_TEST_DATABASE_URL is not set.")
      (testing "candidate crawl persists run content"
        (is (= "complete" (get-in result [:run :lifecycle_status])))
        (is (nil? (get-in result [:run :verification_verdict])))
        (is (str/includes? (get-in result [:report :slack_summary])
                           "support-knowledge-base run"))
        (is (str/includes? (get-in result [:report :full_report])
                           "Documents: 1"))
        (is (= 1 (get-in result [:summary :document_count])))
        (is (= 1 (get-in result [:summary :chunk_count])))
        (is (= [{:source_id "fixtures"
                 :source_type "local"
                 :document_count 1
                 :error_count 0}]
               (:sources result)))
        (is (str/ends-with? (-> result :documents first :title) ".html"))
        (is (= "en" (-> result :documents first :locale)))
        (is (= 64 (count (-> result :documents first :normalized_content_hash))))
        (is (= "fixtures" (-> result :chunks first :source_id)))
        (is (= 64 (count (-> result :chunks first :content_hash))))
        (is (str/includes? (-> result :chunks first :content) "Support"))
        (is (pos-int? (-> result :chunks first :estimated_tokens)))))))

(deftest ^:integration crawl-index-reuses-unchanged-chunk-embeddings
  (let [result (with-temp-database
                 (fn [db-config ds]
                   (db/migrate! {:database db-config})
                   (let [file (temp-file ".html"
                                         "<html lang=\"en\"><head><title>Support</title></head>
                                          <body><h1>Support</h1><p>This page explains how support works.</p></body></html>")
                         test-index (assoc index-cfg
                                           :sources [{:id "fixtures"
                                                      :type "local"
                                                      :path (.getPath file)}])
                         sys {:alida/config {:alida.config/structural-hash "hash-1"
                                             :indexes [test-index]}}
                         embedded-texts (atom [])]
                     (with-redefs [embed/embed-batch (fn [_ _ texts]
                                                       (swap! embedded-texts conj (vec texts))
                                                       (mapv (fn [_] (zero-vector 1536)) texts))]
                       (let [first-summary (crawl/crawl-index! sys ds test-index)
                             second-summary (crawl/crawl-index! sys ds test-index)]
                         {:first-summary first-summary
                          :second-summary second-summary
                          :embedded-texts @embedded-texts
                          :second-chunks (jdbc/execute!
                                          ds
                                          ["SELECT content_hash, embedding::text AS embedding
                                            FROM alida_chunks_1536
                                            WHERE run_id = ?"
                                           (:run_id second-summary)]
                                          db/jdbc-opts)})))))]
    (if (= :skipped result)
      (is true "Skipping Postgres integration test; ALIDA_TEST_DATABASE_URL is not set.")
      (testing "unchanged chunks copy vectors from a compatible previous run"
        (is (= 1 (count (:embedded-texts result))))
        (is (= 1 (get-in result [:first-summary :embedding_stats :embedding_request_count])))
        (is (= 0 (get-in result [:first-summary :embedding_stats :reused_chunk_count])))
        (is (= 0 (get-in result [:second-summary :embedding_stats :embedding_request_count])))
        (is (= 1 (get-in result [:second-summary :embedding_stats :reused_chunk_count])))
        (is (nat-int? (get-in result [:second-summary :embedding_stats :reuse_lookup_duration_ms])))
        (is (nat-int? (get-in result [:second-summary :embedding_stats :provider_duration_ms])))
        (is (= 1 (count (:second-chunks result))))
        (is (= 64 (count (-> result :second-chunks first :content_hash))))))))

(deftest ^:integration crawl-index-does-not-reuse-embeddings-after-provider-endpoint-change
  (let [result (with-temp-database
                 (fn [db-config ds]
                   (db/migrate! {:database db-config})
                   (let [file (temp-file ".html"
                                         "<html lang=\"en\"><head><title>Support</title></head>
                                          <body><h1>Support</h1><p>This page explains how support works.</p></body></html>")
                         base-index (assoc index-cfg
                                           :embedding {:provider "azure-openai"
                                                       :endpoint "https://first.example.openai.azure.com/"
                                                       :deployment_name "embedding"
                                                       :api_version "2024-02-01"
                                                       :api_key "test"
                                                       :embedding_dimensions 1536}
                                           :sources [{:id "fixtures"
                                                      :type "local"
                                                      :path (.getPath file)}])
                         changed-index (assoc-in base-index
                                                 [:embedding :endpoint]
                                                 "https://second.example.openai.azure.com/")
                         sys {:alida/config {:alida.config/structural-hash "hash-1"
                                             :indexes [base-index]}}
                         embedded-texts (atom [])]
                     (with-redefs [embed/embed-batch (fn [_ _ texts]
                                                       (swap! embedded-texts conj (vec texts))
                                                       (mapv (fn [_] (zero-vector 1536)) texts))]
                       (let [first-summary (crawl/crawl-index! sys ds base-index)
                             second-summary (crawl/crawl-index! sys ds changed-index)]
                         {:first-summary first-summary
                          :second-summary second-summary
                          :embedded-texts @embedded-texts})))))]
    (if (= :skipped result)
      (is true "Skipping Postgres integration test; ALIDA_TEST_DATABASE_URL is not set.")
      (testing "provider endpoint changes invalidate embedding reuse"
        (is (= 2 (count (:embedded-texts result))))
        (is (= 1 (get-in result [:first-summary :embedding_stats :embedding_request_count])))
        (is (= 1 (get-in result [:second-summary :embedding_stats :embedding_request_count])))
        (is (= 0 (get-in result [:second-summary :embedding_stats :reused_chunk_count])))))))

(deftest ^:integration lifecycle-guards-invalid-transitions
  (let [result (with-temp-database
                 (fn [db-config _ds]
                   (db/migrate! {:database db-config})
                   (with-open [ds (db/datasource db-config)]
                     (let [created-run (db/create-run! ds index-cfg "hash-1")
                           unverified-run (db/create-run! ds index-cfg "hash-1")
                           caution-run (db/create-run! ds index-cfg "hash-1")
                           caution-override-run (db/create-run! ds index-cfg "hash-1")
                           failed-run (db/create-run! ds index-cfg "hash-1")
                           run-1 (db/create-run! ds index-cfg "hash-1")
                           run-2 (db/create-run! ds index-cfg "hash-1")]
                       (db/update-run-status! ds (:id unverified-run) "complete")
                       (db/update-run-status! ds (:id caution-run) "complete" {:verification_verdict "caution"})
                       (db/update-run-status! ds (:id caution-override-run) "complete" {:verification_verdict "caution"})
                       (db/update-run-status! ds (:id failed-run) "complete" {:verification_verdict "fail"})
                       (let [caution-override-activation (select-keys
                                                          (db/activate-run! ds
                                                                            (:id caution-override-run)
                                                                            {:allow-caution? true})
                                                          [:id :lifecycle_status :verification_verdict])]
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
                          :unverified-activation (try
                                                   (db/activate-run! ds (:id unverified-run))
                                                   :activated
                                                   (catch clojure.lang.ExceptionInfo e
                                                     (ex-data e)))
                          :caution-activation (try
                                                (db/activate-run! ds (:id caution-run))
                                                :activated
                                                (catch clojure.lang.ExceptionInfo e
                                                  (ex-data e)))
                          :caution-override-activation caution-override-activation
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
                                                    (ex-data e)))})))))]
    (if (= :skipped result)
      (is true "Skipping Postgres integration test; ALIDA_TEST_DATABASE_URL is not set.")
      (testing "invalid lifecycle transitions are rejected"
        (is (= :alida.db.postgres/run-not-activatable
               (get-in result [:created-activation :type])))
        (is (= :not-complete
               (get-in result [:created-activation :reason])))
        (is (= :not-verified
               (get-in result [:unverified-activation :reason])))
        (is (= :caution-requires-override
               (get-in result [:caution-activation :reason])))
        (is (= "activated"
               (get-in result [:caution-override-activation :lifecycle_status])))
        (is (= "caution"
               (get-in result [:caution-override-activation :verification_verdict])))
        (is (= :alida.db.postgres/run-not-activatable
               (get-in result [:failed-activation :type])))
        (is (= :verification-not-pass
               (get-in result [:failed-activation :reason])))
        (is (= :live-run
               (get-in result [:reject-live :pointer])))
        (is (= :previous-live-run
               (get-in result [:reject-previous-live :pointer])))))))

(deftest ^:integration prune-removes-eligible-runs-and-keeps-protected-runs
  (let [result (with-temp-database
                 (fn [db-config ds]
                   (db/migrate! {:database db-config})
                     (let [previous-live (db/create-run! ds index-cfg "hash-1")
                           current-live (db/create-run! ds index-cfg "hash-1")
                           prunable (db/create-run! ds index-cfg "hash-1")
                           in-progress (db/create-run! ds index-cfg "hash-1")
                           recent (db/create-run! ds index-cfg "hash-1")]
                     (doseq [run [previous-live current-live prunable recent]]
                       (insert-searchable-chunk! ds (:id run) (str "Content " (:id run)))
                       (db/save-report! ds (:id run) {:slack_summary "summary"
                                                      :full_report "full"}))
                     (db/update-run-status! ds (:id previous-live) "complete" {:verification_verdict "pass"})
                     (db/activate-run! ds (:id previous-live))
                     (db/update-run-status! ds (:id current-live) "complete" {:verification_verdict "pass"})
                     (db/activate-run! ds (:id current-live))
                     (db/update-run-status! ds (:id prunable) "error")
                     (db/update-run-status! ds (:id in-progress) "crawling")
                     (db/update-run-status! ds (:id recent) "error")
                     (jdbc/execute! ds
                                    ["UPDATE alida_runs
                                      SET started_at = now() - interval '90 days'
                                      WHERE id IN (?, ?, ?, ?)"
                                     (:id previous-live)
                                     (:id current-live)
                                     (:id prunable)
                                     (:id in-progress)])
                     (let [pruned (db/prune-runs! ds
                                                  {:older-than (.minus (java.time.Instant/now)
                                                                       (java.time.Duration/ofDays 30))})
                           partition-name (pgvector/run-partition-name 1536 (:id prunable))]
                       {:pruned pruned
                        :previous-live (db/get-run ds (:id previous-live))
                        :current-live (db/get-run ds (:id current-live))
                        :prunable (db/get-run ds (:id prunable))
                        :in-progress (db/get-run ds (:id in-progress))
                        :recent (db/get-run ds (:id recent))
                        :prunable-partition (:partition
                                             (jdbc/execute-one!
                                              ds
                                              ["SELECT to_regclass(?)::text AS partition" partition-name]
                                              db/jdbc-opts))
                        :prunable-report (db/get-report ds (:id prunable))
                        :events (:n (jdbc/execute-one!
                                     ds
                                     ["SELECT count(*) AS n
                                       FROM alida_events
                                       WHERE event_type = 'run-pruned'
                                         AND details->>'run_id' = ?"
                                      (str (:id prunable))]
                                     db/jdbc-opts))}))))]
    (if (= :skipped result)
      (is true "Skipping Postgres integration test; ALIDA_TEST_DATABASE_URL is not set.")
      (testing "manual pruning only removes eligible runs"
        (is (= 1 (get-in result [:pruned :pruned_count])))
        (is (= "error" (-> result :pruned :pruned first :lifecycle_status)))
        (is (some? (:previous-live result)))
        (is (some? (:current-live result)))
        (is (nil? (:prunable result)))
        (is (= "crawling" (get-in result [:in-progress :lifecycle_status])))
        (is (some? (:recent result)))
        (is (nil? (:prunable-partition result)))
        (is (nil? (:prunable-report result)))
        (is (= 1 (:events result)))))))

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

(deftest ^:integration orphan-reconciliation-marks-unlocked-in-progress-runs
  (let [result (with-temp-database
                 (fn [db-config ds]
                   (db/migrate! {:database db-config})
                   (let [run (db/create-run! ds index-cfg "hash-1")]
                     (db/update-run-status! ds (:id run) "crawling")
                     (db/reconcile-orphaned-runs! ds {:stale-after-minutes 360})
                     (select-keys (db/get-run ds (:id run))
                                  [:lifecycle_status :error_summary]))))]
    (if (= :skipped result)
      (is true "Skipping Postgres integration test; ALIDA_TEST_DATABASE_URL is not set.")
      (do
        (is (= "error" (:lifecycle_status result)))
        (is (= "Marked as orphaned after startup reconciliation"
               (:error_summary result)))))))

(deftest ^:integration orphan-reconciliation-skips-locked-in-progress-runs
  (let [result (with-temp-database
                 (fn [db-config ds]
                   (db/migrate! {:database db-config})
                   (let [run (db/create-run! ds index-cfg "hash-1")]
                     (db/update-run-status! ds (:id run) "embedding")
                     (db/with-index-lock!
                       ds
                       (:name index-cfg)
                       #(do
                          (db/reconcile-orphaned-runs! ds {:stale-after-minutes 360})
                          (select-keys (db/get-run ds (:id run))
                                       [:lifecycle_status :error_summary]))))))]
    (if (= :skipped result)
      (is true "Skipping Postgres integration test; ALIDA_TEST_DATABASE_URL is not set.")
      (do
        (is (= "embedding" (:lifecycle_status result)))
        (is (nil? (:error_summary result)))))))

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
