(ns alida.vector.pgvector
  (:require [next.jdbc :as jdbc])
  (:import [java.util UUID]))

(def supported-dimensions
  #{1536 3072})

(defn supported-dimension?
  [embedding-dimensions]
  (contains? supported-dimensions embedding-dimensions))

(defn require-supported-dimension!
  [embedding-dimensions]
  (when-not (supported-dimension? embedding-dimensions)
    (throw (ex-info (str "Unsupported pgvector dimensions: " embedding-dimensions)
                    {:embedding-dimensions embedding-dimensions
                     :supported-dimensions supported-dimensions})))
  embedding-dimensions)

(defn dimension-table-name
  [embedding-dimensions]
  (require-supported-dimension! embedding-dimensions)
  (str "alida_chunks_" embedding-dimensions))

(defn live-view-name
  [embedding-dimensions]
  (require-supported-dimension! embedding-dimensions)
  (str "alida_live_chunks_" embedding-dimensions))

(defn run-partition-name
  [embedding-dimensions run-id]
  (let [uuid (if (uuid? run-id) run-id (UUID/fromString (str run-id)))]
    (str (dimension-table-name embedding-dimensions)
         "_run_"
         (-> (str uuid)
             (.replace "-" "")))))

(defn- run-index-name
  [embedding-dimensions run-id]
  (str (run-partition-name embedding-dimensions run-id) "_hnsw_idx"))

(defn create-run-partition!
  "Create the run's chunk partition table only. The HNSW index is created
   separately, after chunks are bulk-loaded (see create-run-index!), because
   building an HNSW graph incrementally per-insert is far slower than building
   it once over a populated table."
  [connectable embedding-dimensions run-id]
  (let [table-name (dimension-table-name embedding-dimensions)
        partition-name (run-partition-name embedding-dimensions run-id)
        uuid (if (uuid? run-id) run-id (UUID/fromString (str run-id)))]
    (jdbc/execute!
     connectable
     [(format
       "CREATE TABLE IF NOT EXISTS %s
        PARTITION OF %s FOR VALUES IN ('%s')"
       partition-name
       table-name
       uuid)])
    {:partition partition-name}))

(defn create-run-index!
  "Create the HNSW index on a run's (already-populated) chunk partition."
  [connectable embedding-dimensions run-id]
  (let [partition-name (run-partition-name embedding-dimensions run-id)
        index-name (run-index-name embedding-dimensions run-id)]
    (jdbc/execute!
     connectable
     [(format
       "CREATE INDEX IF NOT EXISTS %s
        ON %s USING hnsw (embedding vector_cosine_ops)"
       index-name
       partition-name)])
    {:index index-name}))

(defn ensure-run-partition!
  "Create both the run partition and its HNSW index. Prefer create-run-partition!
   before bulk load and create-run-index! after; this combined form remains for
   tests and callers that do not bulk-load."
  [connectable embedding-dimensions run-id]
  (merge (create-run-partition! connectable embedding-dimensions run-id)
         (create-run-index! connectable embedding-dimensions run-id)))
