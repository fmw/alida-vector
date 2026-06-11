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

(defn- index-dimensions
  [index-cfg]
  (get-in index-cfg [:embedding :embedding_dimensions]))

(defn- embedding-fingerprint
  [index-cfg]
  (embed/fingerprint (:embedding index-cfg)))

(defn- compatible-run?
  [index-cfg run-row]
  (and (= (index-dimensions index-cfg) (:embedding_dimensions run-row))
       (= (embedding-fingerprint index-cfg) (:embedding_fingerprint run-row))))

(defn- require-compatible-run!
  [index-cfg run-row]
  (when-not (compatible-run? index-cfg run-row)
    (throw (ex-info (str "Search embedding config does not match run embedding space: "
                         (:id run-row))
                    {:type :alida.search/embedding-space-mismatch
                     :index-name (:name index-cfg)
                     :run-id (:id run-row)
                     :configured-dimensions (index-dimensions index-cfg)
                     :run-dimensions (:embedding_dimensions run-row)
                     :configured-fingerprint (embedding-fingerprint index-cfg)
                     :run-fingerprint (:embedding_fingerprint run-row)})))
  run-row)

(defn- live-run
  [ds index-cfg]
  (or (db/get-live-run ds (:name index-cfg))
      (throw (ex-info (str "Index has no live run: " (:name index-cfg))
                      {:type :alida.search/no-live-run
                       :index-name (:name index-cfg)}))))

(defn- search-index-live
  [sys ds query opts index-cfg]
  (let [run-row (require-compatible-run! index-cfg (live-run ds index-cfg))
        embedding (query-embedding sys index-cfg query)]
    (db/search-live-chunks ds
                           (:embedding_dimensions run-row)
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
        _ (require-compatible-run! index-cfg run-row)
        embedding (query-embedding sys index-cfg query)]
    (db/search-run-chunks ds
                          (:embedding_dimensions run-row)
                          (:id run-row)
                          embedding
                          {:limit (limit opts)})))
