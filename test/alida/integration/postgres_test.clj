(ns alida.integration.postgres-test
  (:require [alida.config :as config]
            [alida.crawl :as crawl]
            [alida.db.postgres :as db]
            [alida.embed :as embed]
            [alida.source.local]
            [alida.vector.pgvector :as pgvector]
            [alida.verify :as verify]
            [clojure.data.json :as json]
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

(def verification-cfg
  {:provider "openai"
   :model "gpt-test"
   :api_key "test-key"})

(defn- test-system
  [index]
  {:alida/config {:alida.config/structural-hash "hash-1"
                  :verification verification-cfg
                  :indexes [index]}})

(defn- passing-verification
  [_sys _provider-cfg _prompt]
  {:verdict "pass"
   :reasoning "Fixture verification passed"
   :findings []
   :security_findings []
   :raw_response {:verdict "pass"
                  :reasoning "Fixture verification passed"}})

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

(defn- jsonb-value
  [value]
  (cond
    (instance? org.postgresql.util.PGobject value)
    (json/read-str (.getValue value) :key-fn keyword)

    (string? value)
    (json/read-str value :key-fn keyword)

    :else value))

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
                                      'alida_live_chunks_3072',
                                      'alida_verification_attestations')
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
                {:relname "alida_live_chunks_3072" :relkind "v"}
                {:relname "alida_verification_attestations" :relkind "r"}]
               (:relations result)))
        (is (= "alida_runs_embedding_reuse_idx" (:reuse-index result)))))))

(deftest ^:integration verification-attestations-round-trip-with-trust-order
  (let [result (with-temp-database
                 (fn [db-config ds]
                   (db/migrate! {:database db-config})
                   (db/save-verification-attestation!
                    ds
                    {:verification_input_hash "input-hash"
                     :attestor "candidate"
                     :provider "openai"
                     :model "gpt-test"
                     :prompt_policy_version "policy-1"
                     :deterministic_gate_version "gate-1"
                     :verification_input_version "2"
                     :llm_verdict "caution"
                     :reasoning "Candidate needs review"
                     :llm_findings [{:type "possible-issue"}]
                     :llm_security_findings []
                     :raw_response {:verdict "caution"}})
                   (db/save-verification-attestation!
                    ds
                    {:verification_input_hash "input-hash"
                     :attestor "pre-production"
                     :provider "openai"
                     :model "gpt-test"
                     :prompt_policy_version "policy-1"
                     :deterministic_gate_version "gate-1"
                     :verification_input_version "2"
                     :llm_verdict "pass"
                     :reasoning "Pre-production passed"
                     :llm_findings []
                     :llm_security_findings []
                     :raw_response {:verdict "pass"}})
                   {:preferred (db/find-verification-attestation
                                ds
                                "input-hash"
                                ["pre-production" "candidate"])
                    :candidate (db/find-verification-attestation
                                ds
                                "input-hash"
                                ["candidate"])}))]
    (if (= :skipped result)
      (is true "Skipping Postgres integration test; ALIDA_TEST_DATABASE_URL is not set.")
      (do
        (is (= "pre-production" (get-in result [:preferred :attestor])))
        (is (= "pass" (get-in result [:preferred :llm_verdict])))
        (is (= {:verdict "pass"} (get-in result [:preferred :raw_response])))
        (is (= "candidate" (get-in result [:candidate :attestor])))
        (is (= [{:type "possible-issue"}]
               (get-in result [:candidate :llm_findings])))))))

(deftest ^:integration verification-attestation-upsert-preserves-created-at
  (let [result (with-temp-database
                 (fn [db-config ds]
                   (db/migrate! {:database db-config})
                   (let [record {:verification_input_hash "stable-input-hash"
                                 :attestor "candidate"
                                 :provider "openai"
                                 :model "gpt-test"
                                 :verification_input_version "2"
                                 :llm_verdict "pass"
                                 :reasoning "Verified"
                                 :raw_response {:verdict "pass"}}]
                     (db/save-verification-attestation! ds record)
                     (jdbc/execute! ds
                                    ["UPDATE alida_verification_attestations
                                      SET created_at = TIMESTAMPTZ '2000-01-01 00:00:00+00',
                                          last_used_at = TIMESTAMPTZ '2000-01-01 00:00:00+00'
                                      WHERE verification_input_hash = ? AND attestor = ?"
                                     (:verification_input_hash record)
                                     (:attestor record)])
                     (db/save-verification-attestation! ds record)
                     (jdbc/execute-one!
                      ds
                      ["SELECT created_at = TIMESTAMPTZ '2000-01-01 00:00:00+00'
                                AS created_at_preserved,
                               last_used_at > TIMESTAMPTZ '2000-01-01 00:00:00+00'
                                AS last_used_at_advanced
                        FROM alida_verification_attestations
                        WHERE verification_input_hash = ? AND attestor = ?"
                       (:verification_input_hash record)
                       (:attestor record)]
                      db/jdbc-opts))))]
    (if (= :skipped result)
      (is true "Skipping Postgres integration test; ALIDA_TEST_DATABASE_URL is not set.")
      (do
        (is (true? (:created_at_preserved result)))
        (is (true? (:last_used_at_advanced result)))))))

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
                         slack-requests (atom [])
                         sys (-> (test-system test-index)
                                 (assoc-in [:alida/config :notifications :slack_webhook_url]
                                           "https://example.test/slack")
                                 (assoc :alida/http-request
                                        (fn [request]
                                          (swap! slack-requests conj request)
                                          {:status 200
                                           :body "ok"})))]
                     (with-redefs [embed/embed-batch (fn [_ _ texts]
                                                       (mapv (fn [_] (zero-vector 1536)) texts))
                                   verify/complete passing-verification]
                       (let [summary (crawl/crawl-index! sys ds test-index)]
                         {:summary summary
                          :run (db/get-run ds (:run_id summary))
                          :verification (db/get-verification ds (:run_id summary))
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
                                   db/jdbc-opts)
                          :slack-requests @slack-requests})))))]
    (if (= :skipped result)
      (is true "Skipping Postgres integration test; ALIDA_TEST_DATABASE_URL is not set.")
      (testing "candidate crawl persists run content"
        (is (= "complete" (get-in result [:run :lifecycle_status])))
        (is (= "pass" (get-in result [:run :verification_verdict])))
        (is (= "pass" (get-in result [:verification :deterministic_verdict])))
        (is (= "pass" (get-in result [:verification :llm_verdict])))
        (is (= "pass" (get-in result [:verification :final_verdict])))
        (is (str/includes? (get-in result [:report :slack_summary])
                           "support-knowledge-base run"))
        (is (= 1 (count (:slack-requests result))))
        (let [payload (json/read-str (:body (first (:slack-requests result))))]
          (is (= (get-in result [:report :slack_summary])
                 (get payload "text")))
          (is (seq (get payload "blocks"))))
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

(deftest ^:integration crawl-index-can-skip-llm-verification
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
                         sys (assoc-in (test-system test-index)
                                       [:alida/config :verification :enabled]
                                       false)]
                     (with-redefs [embed/embed-batch (fn [_ _ texts]
                                                       (mapv (fn [_] (zero-vector 1536)) texts))
                                   verify/complete (fn [& _]
                                                     (throw (ex-info "LLM verifier should not be called"
                                                                     {:type :test/unexpected-llm-call})))]
                       (let [summary (crawl/crawl-index! sys ds test-index)]
                         {:summary summary
                          :run (db/get-run ds (:run_id summary))
                          :verification (db/get-verification ds (:run_id summary))})))))]
    (if (= :skipped result)
      (is true "Skipping Postgres integration test; ALIDA_TEST_DATABASE_URL is not set.")
      (testing "disabled LLM verification caps the final verdict at caution"
        (is (= "complete" (get-in result [:run :lifecycle_status])))
        ;; Deterministic checks pass, but with the LLM leg disabled the run must
        ;; not earn an auto-activating "pass" — it is capped at "caution".
        (is (= "caution" (get-in result [:run :verification_verdict])))
        (is (= "disabled" (get-in result [:verification :provider])))
        (is (= "pass" (get-in result [:verification :deterministic_verdict])))
        (is (nil? (get-in result [:verification :llm_verdict])))
        (is (= "caution" (get-in result [:verification :final_verdict])))
        (is (= "LLM verification was disabled by config."
               (get-in result [:verification :reasoning])))))))

(deftest ^:integration repeated-verification-inputs-reuse-the-local-attestation
  (let [result (with-temp-database
                 (fn [db-config ds]
                   (db/migrate! {:database db-config})
                   (let [file (temp-file ".html"
                                         "<html lang=\"en\"><head><title>Support</title></head>
                                          <body><h1>Support</h1><p>This page explains how support works.</p></body></html>")
                         test-index (assoc index-cfg
                                           :auto_activate true
                                           :sources [{:id "fixtures"
                                                      :type "local"
                                                      :path (.getPath file)}])
                         sys (assoc-in (test-system test-index)
                                       [:alida/config :verification :attestations :attestor]
                                       "candidate")
                         provider-calls (atom 0)]
                     (with-redefs [embed/embed-batch (fn [_ _ texts]
                                                       (mapv (fn [_] (zero-vector 1536)) texts))
                                   verify/complete (fn [& args]
                                                     (swap! provider-calls inc)
                                                     (apply passing-verification args))]
                       (let [first-summary (crawl/crawl-index! sys ds test-index)
                             _ (db/activate-run! ds (:run_id first-summary))
                             second-summary (crawl/crawl-index! sys ds test-index)
                             third-summary (crawl/crawl-index! sys ds test-index)
                             second-verification (db/get-verification ds (:run_id second-summary))
                             third-verification (db/get-verification ds (:run_id third-summary))]
                         {:provider-calls @provider-calls
                          :second-verification second-verification
                          :third-verification third-verification
                          :attestation-count
                          (:n (jdbc/execute-one!
                               ds
                               ["SELECT count(*) AS n FROM alida_verification_attestations"]
                               db/jdbc-opts))})))))]
    (if (= :skipped result)
      (is true "Skipping Postgres integration test; ALIDA_TEST_DATABASE_URL is not set.")
      (do
        (is (= 2 (:provider-calls result))
            "the first-run and first empty-diff inputs are unique; the next empty diff is reused")
        (is (= "provider" (get-in result [:second-verification :llm_result_source])))
        (is (= "cache" (get-in result [:third-verification :llm_result_source])))
        (is (= "candidate" (get-in result [:third-verification :attestation_attestor])))
        (is (= (get-in result [:second-verification :verification_input_hash])
               (get-in result [:third-verification :verification_input_hash])))
        (is (= 64 (count (get-in result [:third-verification :verification_input_hash]))))
        (is (= 2 (:attestation-count result)))))))

(deftest ^:integration trusted-database-attestation-skips-a-duplicate-provider-call
  (let [result
        (with-temp-database
          (fn [trusted-db-config trusted-ds]
            (db/migrate! {:database trusted-db-config})
            (let [file (temp-file ".html"
                                  "<html lang=\"en\"><head><title>Support</title></head>
                                   <body><h1>Support</h1><p>This page explains how support works.</p></body></html>")
                  test-index (assoc index-cfg
                                    :sources [{:id "fixtures"
                                               :type "local"
                                               :path (.getPath file)}])
                  trusted-sys (assoc-in (test-system test-index)
                                        [:alida/config :verification :attestations :attestor]
                                        "pre-production")]
              (with-redefs [embed/embed-batch (fn [_ _ texts]
                                                (mapv (fn [_] (zero-vector 1536)) texts))
                            verify/complete passing-verification]
                (let [trusted-summary (crawl/crawl-index! trusted-sys trusted-ds test-index)]
                  (with-temp-database
                    (fn [candidate-db-config candidate-ds]
                      (db/migrate! {:database candidate-db-config})
                      (let [trusted-source (merge trusted-db-config
                                                  {:name "pre-production"
                                                   :type "postgres"
                                                   :attestors ["pre-production"]})
                            candidate-sys (assoc-in
                                           (test-system test-index)
                                           [:alida/config :verification :attestations]
                                           {:attestor "candidate"
                                            :trusted_sources [trusted-source]})]
                        (with-redefs [verify/complete
                                      (fn [& _]
                                        (throw (ex-info "trusted input should skip the provider" {})))]
                          (let [candidate-summary (crawl/crawl-index! candidate-sys
                                                                      candidate-ds
                                                                      test-index)
                                verification (db/get-verification
                                              candidate-ds
                                              (:run_id candidate-summary))]
                            {:trusted-hash (:verification_input_hash
                                           (db/get-verification trusted-ds
                                                                (:run_id trusted-summary)))
                             :candidate-hash (:verification_input_hash verification)
                             :source (:llm_result_source verification)
                             :attestor (:attestation_attestor verification)
                             :verdict (:llm_verdict verification)}))))))))))]
    (if (or (= :skipped result)
            ;; The nested helper returns :skipped when the integration database
            ;; is not configured.
            (= :skipped (some-> result :result)))
      (is true "Skipping Postgres integration test; ALIDA_TEST_DATABASE_URL is not set.")
      (do
        (is (= (:trusted-hash result) (:candidate-hash result)))
        (is (= "trusted:pre-production" (:source result)))
        (is (= "pre-production" (:attestor result)))
        (is (= "pass" (:verdict result)))))))

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
                         sys (test-system test-index)
                         embedded-texts (atom [])]
                     (with-redefs [embed/embed-batch (fn [_ _ texts]
                                                       (swap! embedded-texts conj (vec texts))
                                                       (mapv (fn [_] (zero-vector 1536)) texts))
                                   verify/complete passing-verification]
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

(deftest ^:integration crawl-index-stores-diff-against-live-run
  (let [result (with-temp-database
                 (fn [db-config ds]
                   (db/migrate! {:database db-config})
                   (let [file (temp-file ".html"
                                         "<html lang=\"en\"><head><title>Support</title></head>
                                          <body><h1>Support</h1><p>First version of the support article.</p></body></html>")
                         test-index (assoc index-cfg
                                           :sources [{:id "fixtures"
                                                      :type "local"
                                                      :path (.getPath file)}])
                         sys (test-system test-index)]
                     (with-redefs [embed/embed-batch (fn [_ _ texts]
                                                       (mapv (fn [_] (zero-vector 1536)) texts))
                                   verify/complete passing-verification]
                       (let [first-summary (crawl/crawl-index! sys ds test-index)]
                         (db/update-run-status! ds (:run_id first-summary) "complete" {:verification_verdict "pass"})
                         (db/activate-run! ds (:run_id first-summary))
                         (spit file "<html lang=\"en\"><head><title>Support</title></head>
                                     <body><h1>Support</h1><p>Second version with updated content.</p></body></html>")
                         (let [second-summary (crawl/crawl-index! sys ds test-index)
                               run-diff (db/get-run-diff ds (:run_id second-summary))]
                           {:first-summary first-summary
                            :second-summary second-summary
                            :run-diff run-diff
                            :report (db/get-report ds (:run_id second-summary))}))))))]
    (if (= :skipped result)
      (is true "Skipping Postgres integration test; ALIDA_TEST_DATABASE_URL is not set.")
      (testing "candidate diff is stored against the live run"
        (is (= (get-in result [:first-summary :run_id])
               (get-in result [:run-diff :previous_run_id])))
        (is (= {:previous_document_count 1
                :current_document_count 1
                :added_count 0
                :removed_count 0
                :changed_count 1
                :moved_count 0}
               (jsonb-value (get-in result [:run-diff :summary]))))
        (is (= 1 (count (jsonb-value (get-in result [:run-diff :changed_urls])))))
        (is (str/includes? (get-in result [:report :full_report]) "Changed URLs"))))))

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
                         sys (test-system base-index)
                         embedded-texts (atom [])]
                     (with-redefs [embed/embed-batch (fn [_ _ texts]
                                                       (swap! embedded-texts conj (vec texts))
                                                       (mapv (fn [_] (zero-vector 1536)) texts))
                                   verify/complete passing-verification]
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
                           noop-run (db/create-run! ds index-cfg "hash-1" {:embedding_disabled true
                                                                           :embedding_provider "noop"})
                           run-1 (db/create-run! ds index-cfg "hash-1")
                           run-2 (db/create-run! ds index-cfg "hash-1")]
                       (db/update-run-status! ds (:id unverified-run) "complete")
                       (db/update-run-status! ds (:id caution-run) "complete" {:verification_verdict "caution"})
                       (db/update-run-status! ds (:id caution-override-run) "complete" {:verification_verdict "caution"})
                       (db/update-run-status! ds (:id failed-run) "complete" {:verification_verdict "fail"})
                       (db/update-run-status! ds (:id noop-run) "complete" {:verification_verdict "pass"})
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
                          :noop-activation (try
                                             (db/activate-run! ds (:id noop-run))
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
        (is (= :embeddings-disabled
               (get-in result [:noop-activation :reason])))
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

(deftest ^:integration prune-removes-only-attestations-referenced-by-pruned-runs
  (let [result (with-temp-database
                 (fn [db-config ds]
                   (db/migrate! {:database db-config})
                   (let [prunable (db/create-run! ds index-cfg "hash-1")
                         retained (db/create-run! ds index-cfg "hash-1")]
                     (doseq [[run input-hash] [[prunable "prunable-input"]
                                               [retained "retained-input"]]]
                       (db/save-verification!
                        ds
                        (:id run)
                        {:provider "openai"
                         :model "gpt-test"
                         :deterministic_verdict "pass"
                         :llm_verdict "pass"
                         :final_verdict "pass"
                         :verification_input_hash input-hash
                         :llm_result_source "provider"
                         :attestation_attestor "candidate"})
                       (db/save-verification-attestation!
                        ds
                        {:verification_input_hash input-hash
                         :attestor "candidate"
                         :provider "openai"
                         :model "gpt-test"
                         :verification_input_version "2"
                         :llm_verdict "pass"}))
                     (db/save-verification-attestation!
                      ds
                      {:verification_input_hash "unrelated-orphan"
                       :attestor "candidate"
                       :provider "openai"
                       :model "gpt-test"
                       :verification_input_version "2"
                       :llm_verdict "pass"})
                     (db/update-run-status! ds (:id prunable) "error")
                     (db/update-run-status! ds (:id retained) "error")
                     (jdbc/execute! ds
                                    ["UPDATE alida_runs
                                      SET started_at = now() - interval '90 days'
                                      WHERE id = ?"
                                     (:id prunable)])
                     (let [pruned (db/prune-runs! ds
                                                  {:older-than (.minus (java.time.Instant/now)
                                                                       (java.time.Duration/ofDays 30))
                                                   :index-names ["docs"]})]
                       {:pruned pruned
                        :attestations (jdbc/execute!
                                       ds
                                       ["SELECT verification_input_hash
                                         FROM alida_verification_attestations
                                         ORDER BY verification_input_hash"]
                                       db/jdbc-opts)}))))]
    (if (= :skipped result)
      (is true "Skipping Postgres integration test; ALIDA_TEST_DATABASE_URL is not set.")
      (do
        (is (= 1 (get-in result [:pruned :pruned_count])))
        (is (= 1 (get-in result [:pruned :pruned_attestation_count])))
        (is (= [{:verification_input_hash "retained-input"}
                {:verification_input_hash "unrelated-orphan"}]
               (:attestations result)))))))

(deftest ^:integration prune-disabled-embeddings-removes-terminal-disabled-runs
  (let [result (with-temp-database
                 (fn [db-config ds]
                   (db/migrate! {:database db-config})
                   (let [disabled-terminal (db/create-run! ds index-cfg "hash-1" {:embedding_disabled true
                                                                                   :embedding_provider "noop"})
                         disabled-active (db/create-run! ds index-cfg "hash-1" {:embedding_disabled true
                                                                                 :embedding_provider "noop"})
                         regular-terminal (db/create-run! ds index-cfg "hash-1")]
                     (doseq [run [disabled-terminal disabled-active regular-terminal]]
                       (insert-searchable-chunk! ds (:id run) (str "Content " (:id run))))
                     (db/save-report! ds (:id disabled-terminal) {:slack_summary "summary"
                                                                  :full_report "full"})
                     (db/update-run-status! ds (:id disabled-terminal) "complete" {:verification_verdict "pass"})
                     (db/update-run-status! ds (:id disabled-active) "crawling")
                     (db/update-run-status! ds (:id regular-terminal) "complete" {:verification_verdict "pass"})
                     (let [pruned (db/prune-runs! ds {:disabled-embeddings true})
                           partition-name (pgvector/run-partition-name 1536 (:id disabled-terminal))]
                       {:pruned pruned
                        :disabled-terminal (db/get-run ds (:id disabled-terminal))
                        :disabled-active (db/get-run ds (:id disabled-active))
                        :regular-terminal (db/get-run ds (:id regular-terminal))
                        :disabled-terminal-partition (:partition
                                                      (jdbc/execute-one!
                                                       ds
                                                       ["SELECT to_regclass(?)::text AS partition" partition-name]
                                                       db/jdbc-opts))
                        :disabled-terminal-report (db/get-report ds (:id disabled-terminal))
                        :events (:n (jdbc/execute-one!
                                     ds
                                     ["SELECT count(*) AS n
                                       FROM alida_events
                                       WHERE event_type = 'run-pruned'
                                         AND details->>'run_id' = ?"
                                      (str (:id disabled-terminal))]
                                     db/jdbc-opts))}))))]
    (if (= :skipped result)
      (is true "Skipping Postgres integration test; ALIDA_TEST_DATABASE_URL is not set.")
      (testing "disabled-embedding pruning only removes terminal disabled runs"
        (is (= 1 (get-in result [:pruned :pruned_count])))
        (is (nil? (:disabled-terminal result)))
        (is (= "crawling" (get-in result [:disabled-active :lifecycle_status])))
        (is (= "complete" (get-in result [:regular-terminal :lifecycle_status])))
        (is (nil? (:disabled-terminal-partition result)))
        (is (nil? (:disabled-terminal-report result)))
        (is (= 1 (:events result)))))))

(deftest ^:integration prune-can-remove-runs-referenced-by-diff-previous-run
  (let [result (with-temp-database
                 (fn [db-config ds]
                   (db/migrate! {:database db-config})
                   (let [referenced-run (db/create-run! ds index-cfg "hash-1")
                         diff-run (db/create-run! ds index-cfg "hash-1")
                         live-run (db/create-run! ds index-cfg "hash-1")
                         previous-live-run (db/create-run! ds index-cfg "hash-1")]
                     (db/update-run-status! ds (:id referenced-run) "superseded")
                     (db/update-run-status! ds (:id diff-run) "complete")
                     (db/update-run-status! ds (:id live-run) "complete" {:verification_verdict "pass"})
                     (db/activate-run! ds (:id live-run))
                     (db/update-run-status! ds (:id previous-live-run) "complete" {:verification_verdict "pass"})
                     (db/activate-run! ds (:id previous-live-run))
                     (db/save-run-diff! ds
                                        (:id diff-run)
                                        (:id referenced-run)
                                        {:summary {:changed_count 1}})
                     (jdbc/execute! ds
                                    ["UPDATE alida_runs
                                      SET started_at = now() - interval '90 days'
                                      WHERE id = ?"
                                     (:id referenced-run)])
                     (let [pruned (db/prune-runs! ds
                                                  {:older-than (.minus (java.time.Instant/now)
                                                                       (java.time.Duration/ofDays 30))})]
                       {:pruned pruned
                        :referenced-run (db/get-run ds (:id referenced-run))
                        :diff-row (db/get-run-diff ds (:id diff-run))}))))]
    (if (= :skipped result)
      (is true "Skipping Postgres integration test; ALIDA_TEST_DATABASE_URL is not set.")
      (testing "previous_run_id is nulled when the referenced run is pruned"
        (is (= 1 (get-in result [:pruned :pruned_count])))
        (is (nil? (:referenced-run result)))
        (is (nil? (get-in result [:diff-row :previous_run_id])))))))

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
