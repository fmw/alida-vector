(ns alida.db.postgres
  (:require [alida.vector.pgvector :as pgvector]
            [clojure.data.json :as json]
            [clojure.string :as str]
            [migratus.core :as migratus]
            [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs])
  (:import [com.zaxxer.hikari HikariConfig HikariDataSource]
           [java.sql Connection Timestamp]
           [java.nio ByteBuffer]
           [java.security MessageDigest]
           [org.postgresql.util PGobject]))

(def lifecycle-statuses
  #{"activated" "complete" "crawling" "created" "embedding" "error" "rejected" "superseded" "verifying"})

(def verification-verdicts
  #{"caution" "fail" "pass"})

(def default-reuse-candidate-run-limit 5)

(def non-terminal-statuses
  #{"created" "crawling" "embedding" "verifying"})

(def pruneable-lifecycle-statuses
  #{"complete" "error" "rejected" "superseded"})

(def default-stale-run-timeout-minutes 360)

(def jdbc-opts
  {:builder-fn rs/as-unqualified-lower-maps})

(defn- jsonb
  [value]
  (doto (PGobject.)
    (.setType "jsonb")
    (.setValue (if (string? value) value (json/write-str value)))))

(defn- jsonb-value
  [value]
  (cond
    (instance? PGobject value)
    (json/read-str (.getValue ^PGobject value) :key-fn keyword)

    (string? value)
    (json/read-str value :key-fn keyword)

    :else value))

(defn- decode-attestation
  [row]
  (some-> row
          (update :llm_findings jsonb-value)
          (update :llm_security_findings jsonb-value)
          (update :raw_response jsonb-value)))

(defn- require-lifecycle-status!
  [status]
  (when-not (contains? lifecycle-statuses status)
    (throw (ex-info (str "Invalid lifecycle status: " status)
                    {:status status
                     :valid lifecycle-statuses})))
  status)

(defn- require-verdict!
  [verdict]
  (when-not (contains? verification-verdicts verdict)
    (throw (ex-info (str "Invalid verification verdict: " verdict)
                    {:verdict verdict
                     :valid verification-verdicts})))
  verdict)

(defn- run-id
  [value]
  (cond
    (uuid? value) value
    (some? value) (java.util.UUID/fromString (str value))
    :else nil))

(defn- connection?
  [value]
  (instance? Connection value))

(defn- with-connection
  [connectable f]
  (if (connection? connectable)
    (f connectable)
    (with-open [conn (jdbc/get-connection connectable)]
      (f conn))))

(defn- text-array
  [^Connection conn values]
  (.createArrayOf conn "text" (into-array String values)))

(defn- require-connection!
  [connectable]
  (when-not (connection? connectable)
    (throw (ex-info "Session-level advisory locks require a checked-out JDBC Connection"
                    {:type :alida.db.postgres/connection-required})))
  connectable)

(defn advisory-lock-key
  "Return a stable signed 64-bit PostgreSQL advisory lock key for an index name."
  [index-name]
  (let [digest (.digest (MessageDigest/getInstance "SHA-256")
                        (.getBytes (str "alida:index:" index-name) "UTF-8"))]
    (.getLong (ByteBuffer/wrap digest))))

(def default-max-pool-size 10)

(defn datasource
  [{:keys [jdbc_url user username password max_pool_size]}]
  (let [cfg (HikariConfig.)]
    (.setJdbcUrl cfg jdbc_url)
    (when (or username user)
      (.setUsername cfg (or username user)))
    (when password
      (.setPassword cfg password))
    ;; Must comfortably exceed per-run concurrency: a crawl holds one connection
    ;; for its advisory lock plus a transaction connection plus reuse/diff queries,
    ;; and concurrent index crawls multiply that.
    (.setMaximumPoolSize cfg (or max_pool_size default-max-pool-size))
    (.setPoolName cfg "alida-vector")
    (HikariDataSource. cfg)))

(defn migratus-config
  [ds]
  {:store :database
   :migration-dir "migrations"
   :db {:datasource ds}})

(defn migrate!
  [config]
  (with-open [ds (datasource (:database config))]
    (migratus/migrate (migratus-config ds))))

(defn rollback-migration!
  [config]
  (with-open [ds (datasource (:database config))]
    (migratus/rollback (migratus-config ds))))

(defn record-event!
  ([connectable event]
   (record-event! connectable event jdbc-opts))
  ([connectable {:keys [run_id index_name event_type actor details]} opts]
   (jdbc/execute-one!
    connectable
    ["INSERT INTO alida_events (run_id, index_name, event_type, actor, details)
      VALUES (?, ?, ?, ?, ?)
      RETURNING *"
     (run-id run_id)
     index_name
     event_type
     (or actor "alida-vector")
     (jsonb (or details {}))]
    opts)))

(defn ensure-index!
  [connectable {:keys [name embedding]}]
  (jdbc/execute-one!
   connectable
   ["INSERT INTO alida_indexes (name, embedding_dimensions)
     VALUES (?, ?)
     ON CONFLICT (name) DO UPDATE
     SET embedding_dimensions = EXCLUDED.embedding_dimensions,
         updated_at = now()
     RETURNING *"
    name
    (:embedding_dimensions embedding)]
   jdbc-opts))

(defn create-run!
  ([connectable index-cfg structural-config-hash]
   (create-run! connectable index-cfg structural-config-hash {}))
  ([connectable index-cfg structural-config-hash metadata]
  (jdbc/with-transaction [tx connectable]
    (ensure-index! tx index-cfg)
    (let [run (jdbc/execute-one!
               tx
               ["INSERT INTO alida_runs
                 (index_name, lifecycle_status, embedding_dimensions, structural_config_hash, metadata)
                 VALUES (?, ?, ?, ?, ?)
                 RETURNING *"
                (:name index-cfg)
                "created"
                (get-in index-cfg [:embedding :embedding_dimensions])
                structural-config-hash
                (jsonb metadata)]
               jdbc-opts)]
      (record-event! tx {:run_id (:id run)
                         :index_name (:index_name run)
                         :event_type "run-created"
                         :details {:lifecycle_status (:lifecycle_status run)}})
      run))))

(defn upsert-source!
  [connectable run source-cfg structural-config-hash {:keys [document_count error_count metadata]}]
  (jdbc/execute-one!
   connectable
   ["INSERT INTO alida_sources
       (run_id, source_id, source_type, structural_config_hash, document_count, error_count, metadata)
     VALUES (?, ?, ?, ?, ?, ?, ?)
     ON CONFLICT (run_id, source_id) DO UPDATE
     SET document_count = EXCLUDED.document_count,
         error_count = EXCLUDED.error_count,
         metadata = EXCLUDED.metadata
     RETURNING *"
    (:id run)
    (:id source-cfg)
    (:type source-cfg)
    structural-config-hash
    (or document_count 0)
    (or error_count 0)
    (jsonb (or metadata {}))]
   jdbc-opts))

(defn- document-params
  [run source-cfg document]
  [(:id run)
   (:id source-cfg)
   (:external_id document)
   (:canonical_url document)
   (:title document)
   (:locale document)
   (:normalized_content_hash document)
   (:raw_content_hash document)
   (jsonb {:content_type (:content_type document)
           :html_locale (:html_locale document)
           :language_source (:language_source document)
           :language_confidence (:language_confidence document)})])

(defn insert-document!
  [connectable run source-cfg document]
  (jdbc/execute-one!
   connectable
   (into ["INSERT INTO alida_documents
            (run_id, source_id, external_id, canonical_url, title, locale,
             normalized_content_hash, raw_content_hash, metadata)
          VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
          RETURNING *"]
         (document-params run source-cfg document))
   jdbc-opts))

;; ~7200 docs/insert keeps total bound parameters (9 each) under Postgres' 65535
;; protocol limit with comfortable headroom.
(def ^:private document-insert-batch-size 5000)

(defn insert-documents!
  "Insert all documents for a source in batched multi-row INSERTs (rather than one
   round trip per document) and return the inserted rows in input order. Postgres
   preserves VALUES order in RETURNING, so the rows line up with the input."
  [connectable run source-cfg documents]
  (let [row-placeholder "(?, ?, ?, ?, ?, ?, ?, ?, ?)"]
    (vec
     (mapcat
      (fn [batch]
        (let [sql (str "INSERT INTO alida_documents
                          (run_id, source_id, external_id, canonical_url, title, locale,
                           normalized_content_hash, raw_content_hash, metadata)
                        VALUES "
                       (str/join ", " (repeat (count batch) row-placeholder))
                       " RETURNING *")
              params (mapcat #(document-params run source-cfg %) batch)]
          (jdbc/execute! connectable (into [sql] params) jdbc-opts)))
      (partition-all document-insert-batch-size documents)))))

(defn list-run-documents
  [connectable value]
  (jdbc/execute!
   connectable
   ["SELECT source_id, canonical_url, title, locale, normalized_content_hash, raw_content_hash, metadata
     FROM alida_documents
     WHERE run_id = ?
     ORDER BY source_id, canonical_url"
    (run-id value)]
   jdbc-opts))

(defn save-run-diff!
  [connectable value previous-run-id {:keys [summary added_urls removed_urls changed_urls moved_urls
                                             heuristic_security_findings]}]
  (jdbc/execute-one!
   connectable
   ["INSERT INTO alida_run_diffs
       (run_id, previous_run_id, summary, added_urls, removed_urls, changed_urls, moved_urls,
        heuristic_security_findings)
     VALUES (?, ?, ?, ?, ?, ?, ?, ?)
     ON CONFLICT (run_id) DO UPDATE
     SET previous_run_id = EXCLUDED.previous_run_id,
         summary = EXCLUDED.summary,
         added_urls = EXCLUDED.added_urls,
         removed_urls = EXCLUDED.removed_urls,
         changed_urls = EXCLUDED.changed_urls,
         moved_urls = EXCLUDED.moved_urls,
         heuristic_security_findings = EXCLUDED.heuristic_security_findings,
         created_at = now()
     RETURNING *"
    (run-id value)
    (run-id previous-run-id)
    (jsonb (or summary {}))
    (jsonb (or added_urls []))
    (jsonb (or removed_urls []))
    (jsonb (or changed_urls []))
    (jsonb (or moved_urls []))
    (jsonb (or heuristic_security_findings []))]
   jdbc-opts))

(defn get-run-diff
  [connectable value]
  (jdbc/execute-one!
   connectable
   ["SELECT * FROM alida_run_diffs WHERE run_id = ?" (run-id value)]
   jdbc-opts))

(defn save-deterministic-verification!
  [connectable value {:keys [provider model deterministic_verdict deterministic_findings]}]
  (require-verdict! deterministic_verdict)
  (jdbc/execute-one!
   connectable
   ["INSERT INTO alida_verifications
       (run_id, provider, model, deterministic_verdict, deterministic_findings, raw_response)
     VALUES (?, ?, ?, ?, ?, ?)
     ON CONFLICT (run_id) DO UPDATE
     SET provider = EXCLUDED.provider,
         model = EXCLUDED.model,
         deterministic_verdict = EXCLUDED.deterministic_verdict,
         deterministic_findings = EXCLUDED.deterministic_findings,
         created_at = now()
     RETURNING *"
    (run-id value)
    (or provider "deterministic")
    (or model "deterministic-gate")
    deterministic_verdict
    (jsonb (or deterministic_findings []))
    (jsonb {})]
   jdbc-opts))

(defn save-verification!
  [connectable value {:keys [provider model deterministic_verdict deterministic_findings llm_verdict
                             final_verdict reasoning llm_security_findings raw_response
                             verification_input_hash llm_result_source attestation_attestor]}]
  (require-verdict! deterministic_verdict)
  (when llm_verdict
    (require-verdict! llm_verdict))
  (when final_verdict
    (require-verdict! final_verdict))
  (jdbc/execute-one!
   connectable
    ["INSERT INTO alida_verifications
       (run_id, provider, model, deterministic_verdict, deterministic_findings, llm_verdict,
        final_verdict, reasoning, llm_security_findings, raw_response, verification_input_hash,
        llm_result_source, attestation_attestor)
     VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
     ON CONFLICT (run_id) DO UPDATE
     SET provider = EXCLUDED.provider,
         model = EXCLUDED.model,
         deterministic_verdict = EXCLUDED.deterministic_verdict,
         deterministic_findings = EXCLUDED.deterministic_findings,
         llm_verdict = EXCLUDED.llm_verdict,
         final_verdict = EXCLUDED.final_verdict,
         reasoning = EXCLUDED.reasoning,
         llm_security_findings = EXCLUDED.llm_security_findings,
         raw_response = EXCLUDED.raw_response,
         verification_input_hash = EXCLUDED.verification_input_hash,
         llm_result_source = EXCLUDED.llm_result_source,
         attestation_attestor = EXCLUDED.attestation_attestor,
         created_at = now()
     RETURNING *"
    (run-id value)
    provider
    model
    deterministic_verdict
    (jsonb (or deterministic_findings []))
    llm_verdict
    final_verdict
    reasoning
    (jsonb (or llm_security_findings []))
    (jsonb (or raw_response {}))
    verification_input_hash
    llm_result_source
    attestation_attestor]
   jdbc-opts))

(defn get-verification
  [connectable value]
  (jdbc/execute-one!
   connectable
   ["SELECT * FROM alida_verifications WHERE run_id = ?" (run-id value)]
   jdbc-opts))

(defn find-verification-attestation
  [connectable verification-input-hash attestors]
  (when (seq attestors)
    (with-connection
      connectable
      (fn [conn]
        (let [attestors-array (text-array conn attestors)]
          (decode-attestation
           (jdbc/execute-one!
            conn
            ["SELECT *
              FROM alida_verification_attestations
              WHERE verification_input_hash = ?
                AND attestor = ANY(?)
              ORDER BY array_position(?, attestor), created_at DESC
              LIMIT 1"
             verification-input-hash
             attestors-array
             attestors-array]
            jdbc-opts)))))))

(defn save-verification-attestation!
  [connectable {:keys [verification_input_hash attestor provider model prompt_policy_version
                       deterministic_gate_version verification_input_version llm_verdict reasoning
                       llm_findings llm_security_findings raw_response]}]
  (require-verdict! llm_verdict)
  (jdbc/execute-one!
   connectable
   ["INSERT INTO alida_verification_attestations
       (verification_input_hash, attestor, provider, model, prompt_policy_version,
        deterministic_gate_version, verification_input_version, llm_verdict, reasoning,
        llm_findings, llm_security_findings, raw_response)
     VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
     ON CONFLICT (verification_input_hash, attestor) DO UPDATE
     SET provider = EXCLUDED.provider,
         model = EXCLUDED.model,
         prompt_policy_version = EXCLUDED.prompt_policy_version,
         deterministic_gate_version = EXCLUDED.deterministic_gate_version,
         verification_input_version = EXCLUDED.verification_input_version,
         llm_verdict = EXCLUDED.llm_verdict,
         reasoning = EXCLUDED.reasoning,
         llm_findings = EXCLUDED.llm_findings,
         llm_security_findings = EXCLUDED.llm_security_findings,
         raw_response = EXCLUDED.raw_response,
         created_at = now(),
         last_used_at = now()
     RETURNING *"
    verification_input_hash
    attestor
    provider
    model
    (or prompt_policy_version "")
    (or deterministic_gate_version "")
    verification_input_version
    llm_verdict
    reasoning
    (jsonb (or llm_findings []))
    (jsonb (or llm_security_findings []))
    (jsonb (or raw_response {}))]
   jdbc-opts))

(defn touch-verification-attestation!
  [connectable verification-input-hash attestor]
  (jdbc/execute-one!
   connectable
   ["UPDATE alida_verification_attestations
     SET last_used_at = now()
     WHERE verification_input_hash = ? AND attestor = ?
     RETURNING verification_input_hash, attestor, last_used_at"
    verification-input-hash
    attestor]
   jdbc-opts))

(defn- vector-literal
  [embedding]
  (if (string? embedding)
    embedding
    (str "[" (str/join "," embedding) "]")))

(defn- search-limit
  [limit]
  (or limit 10))

(defn- timestamp
  [instant]
  (when instant
    (Timestamp/from instant)))

(defn- require-prune-criteria!
  [{:keys [keep-last older-than disabled-embeddings]}]
  (when-not (or (some? keep-last) (some? older-than) disabled-embeddings)
    (throw (ex-info "Prune requires --keep-last, --older-than, or --disabled-embeddings"
                    {:type :alida.db.postgres/prune-requires-criteria})))
  (when (and (some? keep-last) (neg-int? keep-last))
    (throw (ex-info "Prune --keep-last must be zero or greater"
                    {:type :alida.db.postgres/invalid-prune-keep-last
                     :keep-last keep-last}))))

(defn insert-chunks!
  [connectable embedding-dimensions run source-cfg document-row chunks]
  (let [table-name (pgvector/dimension-table-name embedding-dimensions)
        sql (format
             "INSERT INTO %s
                (run_id, source_id, document_id, chunk_index, chunk_count, content_hash, content,
                 embedding, estimated_tokens, heading_path, metadata)
              VALUES (?, ?, ?, ?, ?, ?, ?, ?::vector, ?, ?, ?)"
             table-name)
        param-rows (mapv (fn [{:keys [chunk_index chunk_count content_hash content embedding estimated_tokens heading_path metadata]}]
                           [(:id run)
                            (:id source-cfg)
                            (:id document-row)
                            chunk_index
                            chunk_count
                            content_hash
                            content
                            (vector-literal embedding)
                            estimated_tokens
                            (jsonb (or heading_path []))
                            (jsonb (or metadata {}))])
                         chunks)]
    (when (seq param-rows)
      (jdbc/execute-batch! connectable sql param-rows jdbc-opts))))

(defn- droppable-run-partition!
  [connectable embedding-dimensions value]
  (let [partition-name (pgvector/run-partition-name embedding-dimensions value)]
    (jdbc/execute! connectable [(format "DROP TABLE IF EXISTS %s" partition-name)])
    partition-name))

(declare with-index-lock!)

(defn reusable-embeddings
  ([connectable embedding-dimensions index-name embedding-fingerprint content-hashes]
   (reusable-embeddings connectable
                        embedding-dimensions
                        index-name
                        embedding-fingerprint
                        content-hashes
                        {}))
  ([connectable embedding-dimensions index-name embedding-fingerprint content-hashes {:keys [candidate-run-limit]}]
   (let [content-hashes (vec (remove str/blank? (distinct content-hashes)))]
     (if (seq content-hashes)
       (with-connection
         connectable
         (fn [conn]
           (let [table-name (pgvector/dimension-table-name embedding-dimensions)
                 candidate-run-limit (or candidate-run-limit default-reuse-candidate-run-limit)
                 rows (jdbc/execute!
                       conn
                       [(format
                         "WITH candidate_runs AS (
                           SELECT id, started_at
                           FROM alida_runs
                           WHERE index_name = ?
                             AND embedding_dimensions = ?
                             AND metadata->>'embedding_fingerprint' = ?
                             AND lifecycle_status IN ('complete', 'activated', 'superseded')
                           ORDER BY started_at DESC
                           LIMIT ?
                         )
                         SELECT DISTINCT ON (c.content_hash)
                           c.content_hash,
                           c.embedding::text AS embedding
                         FROM %s c
                         JOIN candidate_runs r ON r.id = c.run_id
                         WHERE c.content_hash = ANY(?)
                         ORDER BY c.content_hash, r.started_at DESC"
                         table-name)
                        index-name
                        embedding-dimensions
                        embedding-fingerprint
                        candidate-run-limit
                        (text-array conn content-hashes)]
                       jdbc-opts)]
             (into {} (map (juxt :content_hash :embedding)) rows))))
       {}))))

(defn get-run
  [connectable value]
  (jdbc/execute-one!
   connectable
   ["SELECT *,
            metadata->>'embedding_fingerprint' AS embedding_fingerprint,
            metadata->>'embedding_provider' AS embedding_provider,
            COALESCE((metadata->>'embedding_disabled')::boolean, false) AS embedding_disabled
     FROM alida_runs
     WHERE id = ?"
    (run-id value)]
   jdbc-opts))

(defn get-live-run
  [connectable index-name]
  (jdbc/execute-one!
   connectable
   ["SELECT r.*,
            r.metadata->>'embedding_fingerprint' AS embedding_fingerprint,
            r.metadata->>'embedding_provider' AS embedding_provider,
            COALESCE((r.metadata->>'embedding_disabled')::boolean, false) AS embedding_disabled
     FROM alida_indexes i
     JOIN alida_runs r ON r.id = i.live_run_id
     WHERE i.name = ?"
    index-name]
   jdbc-opts))

(defn update-run-status!
  ([connectable value lifecycle-status]
   (update-run-status! connectable value lifecycle-status nil))
  ([connectable value lifecycle-status {:keys [error_summary verification_verdict metadata]}]
   (require-lifecycle-status! lifecycle-status)
   (when verification_verdict
     (require-verdict! verification_verdict))
   (jdbc/with-transaction [tx connectable]
     (let [run (jdbc/execute-one!
                tx
                ["UPDATE alida_runs
                  SET lifecycle_status = ?,
                      verification_verdict = COALESCE(?, verification_verdict),
                      error_summary = COALESCE(?, error_summary),
                      metadata = metadata || COALESCE(?::jsonb, '{}'::jsonb),
                      finished_at = CASE
                        WHEN ? IN ('complete', 'error') AND finished_at IS NULL THEN now()
                        ELSE finished_at
                      END,
                      activated_at = CASE
                        WHEN ? = 'activated' THEN now()
                        ELSE activated_at
                      END,
                      rejected_at = CASE
                        WHEN ? = 'rejected' THEN now()
                        ELSE rejected_at
                      END
                  WHERE id = ?
                  RETURNING *"
                 lifecycle-status
                 verification_verdict
                 error_summary
                 (when metadata (jsonb metadata))
                 lifecycle-status
                 lifecycle-status
                 lifecycle-status
                 (run-id value)]
                jdbc-opts)]
       (when-not run
         (throw (ex-info (str "Unknown run: " value) {:run-id value})))
       (record-event! tx {:run_id (:id run)
                          :index_name (:index_name run)
                          :event_type "run-status-updated"
                          :details {:lifecycle_status lifecycle-status
                                    :verification_verdict verification_verdict
                                    :error_summary error_summary}})
       run))))

(defn- require-activatable-run!
  [run {:keys [allow-caution?]}]
  (when-not (= "complete" (:lifecycle_status run))
    (throw (ex-info (str "Run is not activatable: " (:id run))
                    {:type :alida.db.postgres/run-not-activatable
                     :run-id (:id run)
                     :lifecycle-status (:lifecycle_status run)
                     :verification-verdict (:verification_verdict run)
                     :reason :not-complete})))
  (when (:embedding_disabled run)
    (throw (ex-info (str "Run was created with disabled embeddings and cannot be activated: " (:id run))
                    {:type :alida.db.postgres/run-not-activatable
                     :run-id (:id run)
                     :lifecycle-status (:lifecycle_status run)
                     :verification-verdict (:verification_verdict run)
                     :reason :embeddings-disabled})))
  (case (:verification_verdict run)
    "pass" nil
    "caution" (when-not allow-caution?
                (throw (ex-info (str "Run is not activatable without allow-caution: " (:id run))
                                {:type :alida.db.postgres/run-not-activatable
                                 :run-id (:id run)
                                 :lifecycle-status (:lifecycle_status run)
                                 :verification-verdict (:verification_verdict run)
                                 :reason :caution-requires-override})))
    nil (throw (ex-info (str "Run is not verified: " (:id run))
                        {:type :alida.db.postgres/run-not-activatable
                         :run-id (:id run)
                         :lifecycle-status (:lifecycle_status run)
                         :verification-verdict nil
                         :reason :not-verified}))
    (throw (ex-info (str "Run is not activatable: " (:id run))
                    {:type :alida.db.postgres/run-not-activatable
                     :run-id (:id run)
                     :lifecycle-status (:lifecycle_status run)
                     :verification-verdict (:verification_verdict run)
                     :reason :verification-not-pass})))
  run)

(defn- run-index-pointer
  [tx value]
  (jdbc/execute-one!
   tx
   ["SELECT name, live_run_id, previous_live_run_id
     FROM alida_indexes
     WHERE live_run_id = ? OR previous_live_run_id = ?
     FOR UPDATE"
    (run-id value)
    (run-id value)]
   jdbc-opts))

(defn list-runs
  ([connectable] (list-runs connectable {}))
  ([connectable {:keys [index_name limit]}]
   (let [limit (or limit 50)]
     (jdbc/execute!
      connectable
      (if index_name
        ["SELECT id, index_name, lifecycle_status, verification_verdict,
                 embedding_dimensions, started_at, finished_at, activated_at, rejected_at
          FROM alida_runs
          WHERE index_name = ?
          ORDER BY started_at DESC
          LIMIT ?"
         index_name
         limit]
        ["SELECT id, index_name, lifecycle_status, verification_verdict,
                 embedding_dimensions, started_at, finished_at, activated_at, rejected_at
          FROM alida_runs
          ORDER BY started_at DESC
          LIMIT ?"
         limit])
      jdbc-opts))))

(defn save-report!
  [connectable value {:keys [slack_summary full_report]}]
  (jdbc/execute-one!
   connectable
   ["INSERT INTO alida_reports (run_id, slack_summary, full_report)
     VALUES (?, ?, ?)
     ON CONFLICT (run_id) DO UPDATE
     SET slack_summary = EXCLUDED.slack_summary,
         full_report = EXCLUDED.full_report,
         created_at = now()
     RETURNING *"
    (run-id value)
    slack_summary
    full_report]
   jdbc-opts))

(defn get-report
  [connectable value]
  (jdbc/execute-one!
   connectable
   ["SELECT * FROM alida_reports WHERE run_id = ?" (run-id value)]
   jdbc-opts))

(defn- restrict-prune-candidates
  [candidates index-names]
  (let [selected (set index-names)]
    (if (nil? index-names)
      candidates
      (filterv #(contains? selected (:index_name %)) candidates))))

(defn prune-candidate-runs
  [connectable {:keys [keep-last older-than disabled-embeddings index-names]}]
  (require-prune-criteria! {:keep-last keep-last
                            :older-than older-than
                            :disabled-embeddings disabled-embeddings})
  (let [older-than (timestamp older-than)]
    (restrict-prune-candidates
     (with-connection
       connectable
       (fn [conn]
         (let [index-names-array (when (some? index-names)
                                   (text-array conn index-names))]
           (jdbc/execute!
            conn
            ["WITH ranked AS (
        SELECT r.*,
               row_number() OVER (
                 PARTITION BY r.index_name
                 ORDER BY r.started_at DESC, r.id DESC
               ) AS index_rank,
               i.live_run_id,
               i.previous_live_run_id
        FROM alida_runs r
        JOIN alida_indexes i ON i.name = r.index_name
      )
      SELECT id, index_name, lifecycle_status, embedding_dimensions, started_at, finished_at
      FROM ranked
      WHERE (?::text[] IS NULL OR index_name = ANY(?::text[]))
        AND id IS DISTINCT FROM live_run_id
        AND id IS DISTINCT FROM previous_live_run_id
        AND lifecycle_status = ANY(?)
        AND (?::integer IS NULL OR index_rank > ?)
        AND (?::timestamptz IS NULL OR started_at < ?)
        AND (?::boolean = false
             OR COALESCE((metadata->>'embedding_disabled')::boolean, false) = true
             OR metadata->>'embedding_provider' = 'noop')
      ORDER BY index_name, started_at"
             index-names-array
             index-names-array
             (text-array conn pruneable-lifecycle-statuses)
             keep-last
             keep-last
             older-than
             older-than
             (boolean disabled-embeddings)]
            jdbc-opts))))
     index-names)))

(defn- prune-run!
  [tx opts run]
  (let [partition-name (droppable-run-partition!
                        tx
                        (:embedding_dimensions run)
                        (:id run))]
    (jdbc/execute-one! tx
                       ["DELETE FROM alida_runs WHERE id = ?" (:id run)]
                       jdbc-opts)
    (record-event! tx {:index_name (:index_name run)
                       :event_type "run-pruned"
                       :details {:run_id (:id run)
                                 :lifecycle_status (:lifecycle_status run)
                                 :embedding_dimensions (:embedding_dimensions run)
                                 :partition partition-name
                                 :criteria (select-keys opts
                                                        [:keep-last
                                                         :older-than
                                                         :disabled-embeddings
                                                         :index-names])}})
    (assoc run :partition partition-name)))

(defn prune-runs!
  [connectable opts]
  (require-prune-criteria! opts)
  (let [index-names (->> (prune-candidate-runs connectable opts)
                         (map :index_name)
                         distinct
                         sort
                         vec)
        pruned (mapcat
                (fn [index-name]
                  (with-index-lock!
                    connectable
                    index-name
                    #(jdbc/with-transaction [tx connectable]
                       (->> (prune-candidate-runs tx opts)
                            (filter (comp #{index-name} :index_name))
                            (mapv (partial prune-run! tx opts))))))
                index-names)]
    {:pruned pruned
     :pruned_count (count pruned)}))

(defn search-live-chunks
  [connectable embedding-dimensions query-embedding {:keys [index_names limit]}]
  (with-connection
    connectable
    (fn [conn]
      (let [view-name (pgvector/live-view-name embedding-dimensions)
            query-vector (vector-literal query-embedding)
            limit (search-limit limit)]
        (jdbc/execute!
         conn
         (if (seq index_names)
           [(format
             "SELECT index_name, run_id, document_id, source_id, canonical_url, title, locale,
                     content_hash, content, heading_path, metadata, estimated_tokens,
                     (embedding <=> ?::vector) AS distance,
                     (1 - (embedding <=> ?::vector)) AS score
              FROM %s
              WHERE index_name = ANY(?)
              ORDER BY embedding <=> ?::vector
              LIMIT ?"
             view-name)
            query-vector
            query-vector
            (text-array conn index_names)
            query-vector
            limit]
           [(format
             "SELECT index_name, run_id, document_id, source_id, canonical_url, title, locale,
                     content_hash, content, heading_path, metadata, estimated_tokens,
                     (embedding <=> ?::vector) AS distance,
                     (1 - (embedding <=> ?::vector)) AS score
              FROM %s
              ORDER BY embedding <=> ?::vector
              LIMIT ?"
             view-name)
            query-vector
            query-vector
            query-vector
            limit])
         jdbc-opts)))))

(defn search-run-chunks
  [connectable embedding-dimensions value query-embedding {:keys [limit]}]
  (let [table-name (pgvector/dimension-table-name embedding-dimensions)
        query-vector (vector-literal query-embedding)]
    (jdbc/execute!
     connectable
     [(format
       "SELECT r.index_name, c.run_id, c.document_id, c.source_id, d.canonical_url, d.title,
               d.locale, c.content_hash, c.content, c.heading_path, c.metadata,
               c.estimated_tokens, (c.embedding <=> ?::vector) AS distance,
               (1 - (c.embedding <=> ?::vector)) AS score
        FROM %s c
        JOIN alida_runs r ON r.id = c.run_id
        JOIN alida_documents d ON d.id = c.document_id
        WHERE c.run_id = ?
        ORDER BY c.embedding <=> ?::vector
        LIMIT ?"
       table-name)
      query-vector
      query-vector
      (run-id value)
      query-vector
      (search-limit limit)]
     jdbc-opts)))

(defn activate-run!
  ([connectable value]
   (activate-run! connectable value {}))
  ([connectable value opts]
   (jdbc/with-transaction [tx connectable]
     (let [run (get-run tx value)]
       (when-not run
         (throw (ex-info (str "Unknown run: " value) {:run-id value})))
       (require-activatable-run! run opts)
       (let [index-row (jdbc/execute-one!
                        tx
                        ["SELECT * FROM alida_indexes WHERE name = ? FOR UPDATE" (:index_name run)]
                        jdbc-opts)
             previous-live-id (:live_run_id index-row)]
         (jdbc/execute-one!
          tx
          ["UPDATE alida_indexes
            SET previous_live_run_id = live_run_id,
                live_run_id = ?,
                updated_at = now()
            WHERE name = ?
            RETURNING *"
           (:id run)
           (:index_name run)]
          jdbc-opts)
         (when previous-live-id
           (update-run-status! tx previous-live-id "superseded"))
         (let [activated (update-run-status! tx (:id run) "activated")]
           (record-event! tx {:run_id (:id run)
                              :index_name (:index_name run)
                              :event_type "run-activated"
                              :details {:previous_live_run_id previous-live-id
                                        :allow_caution (:allow-caution? opts)}})
           activated))))))

(defn reject-run!
  [connectable value]
  (jdbc/with-transaction [tx connectable]
    (let [run (get-run tx value)]
      (when-not run
        (throw (ex-info (str "Unknown run: " value) {:run-id value})))
      (when-let [index-row (run-index-pointer tx value)]
        (throw (ex-info (str "Cannot reject run currently referenced by index: " value)
                        {:type :alida.db.postgres/run-is-index-pointer
                         :run-id (:id run)
                         :index-name (:name index-row)
                         :pointer (cond
                                    (= (:id run) (:live_run_id index-row)) :live-run
                                    (= (:id run) (:previous_live_run_id index-row)) :previous-live-run)})))
      (update-run-status! tx value "rejected"))))

(defn rollback-index!
  [connectable index-name]
  (jdbc/with-transaction [tx connectable]
    (let [index-row (jdbc/execute-one!
                     tx
                     ["SELECT * FROM alida_indexes WHERE name = ? FOR UPDATE" index-name]
                     jdbc-opts)]
      (when-not index-row
        (throw (ex-info (str "Unknown index: " index-name) {:index-name index-name})))
      (when-not (:previous_live_run_id index-row)
        (throw (ex-info (str "Index has no previous live run: " index-name)
                        {:index-name index-name})))
      (jdbc/execute-one!
       tx
       ["UPDATE alida_indexes
         SET live_run_id = previous_live_run_id,
             previous_live_run_id = live_run_id,
             updated_at = now()
         WHERE name = ?
         RETURNING *"
        index-name]
       jdbc-opts)
      (update-run-status! tx (:previous_live_run_id index-row) "activated")
      (when (:live_run_id index-row)
        (update-run-status! tx (:live_run_id index-row) "superseded"))
      (record-event! tx {:run_id (:previous_live_run_id index-row)
                         :index_name index-name
                         :event_type "index-rolled-back"
                         :details {:old_live_run_id (:live_run_id index-row)
                                   :new_live_run_id (:previous_live_run_id index-row)}}))))

(defn try-index-lock!
  [connectable index-name]
  (:acquired
   (jdbc/execute-one!
    (require-connection! connectable)
    ["SELECT pg_try_advisory_lock(?) AS acquired" (advisory-lock-key index-name)]
    jdbc-opts)))

(defn unlock-index!
  [connectable index-name]
  (:released
   (jdbc/execute-one!
    (require-connection! connectable)
    ["SELECT pg_advisory_unlock(?) AS released" (advisory-lock-key index-name)]
    jdbc-opts)))

(defn with-index-lock!
  [connectable index-name f]
  (with-connection connectable
    (fn [conn]
      (if (try-index-lock! conn index-name)
        (try
          (f)
          (finally
            (unlock-index! conn index-name)))
        (throw (ex-info (str "Index is already locked: " index-name)
                        {:type :alida.db.postgres/index-locked
                         :index-name index-name
                         :lock-key (advisory-lock-key index-name)}))))))

(defn reconcile-orphaned-runs!
  ([connectable] (reconcile-orphaned-runs! connectable {}))
  ([connectable {:keys [stale-after-minutes]}]
   (let [stale-after-minutes (or stale-after-minutes default-stale-run-timeout-minutes)
         candidates (jdbc/execute!
                     connectable
                     ["SELECT *
                              , started_at < now() - (? * interval '1 minute') AS stale
                       FROM alida_runs
                       WHERE lifecycle_status IN ('created', 'crawling', 'embedding', 'verifying')
                       ORDER BY started_at"
                      stale-after-minutes]
                     jdbc-opts)]
     (reduce
      (fn [reconciled run]
        (let [stale? (:stale run)]
          (try
            (with-index-lock!
              connectable
              (:index_name run)
              #(if (or stale? (not= "created" (:lifecycle_status run)))
                 (conj reconciled
                       (update-run-status!
                        connectable
                        (:id run)
                        "error"
                        {:error_summary "Marked as orphaned after startup reconciliation"}))
                 reconciled))
            (catch Exception e
              (if (= :alida.db.postgres/index-locked (:type (ex-data e)))
                reconciled
                (throw e))))))
      []
      candidates))))
