(ns alida.search
  (:require [alida.db.postgres :as db]
            [alida.embed :as embed]
            [alida.run :as run]))

(def default-limit 10)

(defn- limit
  [opts]
  (or (:limit opts) default-limit))

(defn- query-embedding
  [sys index-cfg query]
  (first (embed/embed-batch sys (:embedding index-cfg) [query])))

(defn- search-index-live
  [sys ds query opts index-cfg]
  (let [embedding (query-embedding sys index-cfg query)
        dimensions (get-in index-cfg [:embedding :embedding_dimensions])]
    (db/search-live-chunks ds
                           dimensions
                           embedding
                           {:index_names [(:name index-cfg)]
                            :limit (limit opts)})))

(defn- score
  [row]
  (double (or (:score row) 0.0)))

(defn- top-results
  [rows opts]
  (->> rows
       (sort-by score >)
       (take (limit opts))
       vec))

(defn search-live
  [sys ds query opts]
  (let [indexes (run/selected-indexes sys (:index-name opts))]
    (top-results
     (mapcat #(search-index-live sys ds query opts %) indexes)
     opts)))

(defn- configured-index
  [sys index-name]
  (or (first (filter #(= index-name (:name %))
                     (get-in sys [:alida/config :indexes])))
      (throw (ex-info (str "Run index is not present in config: " index-name)
                      {:type :alida.search/missing-index-config
                       :index-name index-name}))))

(defn search-run
  [sys ds run-id query opts]
  (let [run-row (or (db/get-run ds run-id)
                    (throw (ex-info (str "Unknown run: " run-id)
                                    {:type :alida.search/unknown-run
                                     :run-id run-id})))
        index-cfg (configured-index sys (:index_name run-row))
        embedding (query-embedding sys index-cfg query)]
    (db/search-run-chunks ds
                          (:embedding_dimensions run-row)
                          (:id run-row)
                          embedding
                          {:limit (limit opts)})))
