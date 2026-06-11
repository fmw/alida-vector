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

(defn ensure-run-partition!
  [connectable embedding-dimensions run-id]
  (let [table-name (dimension-table-name embedding-dimensions)
        partition-name (run-partition-name embedding-dimensions run-id)
        index-name (str partition-name "_hnsw_idx")
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
