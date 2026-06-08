(ns alida.db
  (:require [migratus.core :as migratus]
            [next.jdbc :as jdbc])
  (:import [com.zaxxer.hikari HikariConfig HikariDataSource]
           [java.util UUID]))

(def lifecycle-statuses
  #{"activated" "complete" "crawling" "created" "embedding" "error" "rejected" "superseded" "verifying"})

(def verification-verdicts
  #{"caution" "fail" "pass"})

(defn datasource
  [{:keys [jdbc_url user username password]}]
  (let [cfg (HikariConfig.)]
    (.setJdbcUrl cfg jdbc_url)
    (when (or username user)
      (.setUsername cfg (or username user)))
    (when password
      (.setPassword cfg password))
    (.setMaximumPoolSize cfg 5)
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

(defn dimension-table-name
  [embedding-dimensions]
  (when-not (pos-int? embedding-dimensions)
    (throw (ex-info "Embedding dimensions must be a positive integer"
                    {:embedding-dimensions embedding-dimensions})))
  (str "alida_chunks_" embedding-dimensions))

(defn live-view-name
  [embedding-dimensions]
  (str "alida_live_chunks_" embedding-dimensions))

(defn run-partition-name
  [embedding-dimensions run-id]
  (let [uuid (if (uuid? run-id) run-id (UUID/fromString (str run-id)))]
    (str (dimension-table-name embedding-dimensions)
         "_run_"
         (-> (str uuid)
             (.replace "-" "")))))

(defn ensure-dimension-table!
  "Create the dimension-specific chunk parent table and live view.

  This DDL is app-managed because pgvector column dimensions come from YAML config.
  Identifiers are generated only from validated integer dimensions."
  [connectable embedding-dimensions]
  (let [table-name (dimension-table-name embedding-dimensions)
        view-name (live-view-name embedding-dimensions)]
    (jdbc/execute!
     connectable
     [(format
       "CREATE TABLE IF NOT EXISTS %s (
          id uuid DEFAULT gen_random_uuid(),
          run_id uuid NOT NULL REFERENCES alida_runs(id) ON DELETE CASCADE,
          source_id text NOT NULL,
          document_id uuid NOT NULL REFERENCES alida_documents(id) ON DELETE CASCADE,
          chunk_index integer NOT NULL,
          chunk_count integer NOT NULL,
          content text NOT NULL,
          embedding vector(%d) NOT NULL,
          estimated_tokens integer NOT NULL,
          heading_path jsonb NOT NULL DEFAULT '[]'::jsonb,
          metadata jsonb NOT NULL DEFAULT '{}'::jsonb,
          created_at timestamptz NOT NULL DEFAULT now(),
          PRIMARY KEY (run_id, id),
          UNIQUE (run_id, document_id, chunk_index)
        ) PARTITION BY LIST (run_id)"
       table-name
       embedding-dimensions)])
    (jdbc/execute!
     connectable
     [(format
       "CREATE OR REPLACE VIEW %s AS
        SELECT
          i.name AS index_name,
          c.run_id,
          c.document_id,
          c.source_id,
          c.content,
          c.embedding,
          c.metadata,
          c.heading_path,
          c.estimated_tokens
        FROM %s c
        JOIN alida_indexes i ON i.live_run_id = c.run_id"
       view-name
       table-name)])
    {:table table-name
     :live-view view-name}))

(defn ensure-run-partition!
  [connectable embedding-dimensions run-id]
  (let [table-name (dimension-table-name embedding-dimensions)
        partition-name (run-partition-name embedding-dimensions run-id)
        index-name (str partition-name "_embedding_hnsw_idx")
        uuid (if (uuid? run-id) run-id (UUID/fromString (str run-id)))]
    (jdbc/execute!
     connectable
     [(format
       "CREATE TABLE IF NOT EXISTS %s
        PARTITION OF %s FOR VALUES IN ('%s')"
       partition-name
       table-name
       uuid)])
    (jdbc/execute!
     connectable
     [(format
       "CREATE INDEX IF NOT EXISTS %s
        ON %s USING hnsw (embedding vector_cosine_ops)"
       index-name
       partition-name)])
    {:partition partition-name
     :index index-name}))
