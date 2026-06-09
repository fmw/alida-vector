(ns alida.crawl
  (:require [alida.chunk :as chunk]
            [alida.db.postgres :as db]
            [alida.embed :as embed]
            [alida.extract.html :as html]
            [alida.lang :as lang]
            [alida.run :as run]
            [alida.source :as source]
            [alida.vector.pgvector :as pgvector]
            [clojure.string :as str]
            [next.jdbc :as jdbc]))

(defn- html-content?
  [content-type]
  (let [content-type (str/lower-case (or content-type ""))]
    (or (str/starts-with? content-type "text/html")
        (str/starts-with? content-type "application/xhtml+xml"))))

(defn- error-details
  [value details]
  (cond
    (source/anomaly? value)
    (merge details (:alida/error value))

    (instance? Throwable value)
    (merge details
           {:type :alida.crawl/exception
            :message (ex-message value)
            :data (ex-data value)})

    :else
    (merge details {:type :alida.crawl/error})))

(defn- extract-document
  [source-cfg fetched]
  (if (html-content? (:content_type fetched))
    (html/extract source-cfg fetched)
    (source/anomaly :cognitect.anomalies/unsupported
                    {:type :alida.crawl/unsupported-content-type
                     :source-id (:id source-cfg)
                     :canonical-url (:canonical_url fetched)
                     :content-type (:content_type fetched)})))

(defn- process-fetched
  [index-cfg source-cfg fetched]
  (try
    (let [document (extract-document source-cfg fetched)]
      (if (source/anomaly? document)
        {:error (error-details document {:source_id (:id source-cfg)
                                         :canonical_url (:canonical_url fetched)})}
        (let [document (lang/annotate-document index-cfg source-cfg document)
              chunks (chunk/section-aware document (:chunking index-cfg))]
          {:document document
           :chunks chunks})))
    (catch Exception e
      {:error (error-details e {:source_id (:id source-cfg)
                                :canonical_url (:canonical_url fetched)})})))

(defn- process-discovered
  [sys index-cfg source-cfg discovered-item]
  (if (source/anomaly? discovered-item)
    {:error (error-details discovered-item {:source_id (:id source-cfg)})}
    (try
      (let [fetched (source/fetch sys source-cfg discovered-item)]
        (if (source/anomaly? fetched)
          {:error (error-details fetched {:source_id (:id source-cfg)
                                          :canonical_url (:canonical_url discovered-item)})}
          (process-fetched index-cfg source-cfg fetched)))
      (catch Exception e
        {:error (error-details e {:source_id (:id source-cfg)
                                  :canonical_url (:canonical_url discovered-item)})}))))

(defn- dedupe-discovered
  [discovered]
  (:items
   (reduce
    (fn [{:keys [seen] :as result} item]
      (if (source/anomaly? item)
        (update result :items conj item)
        (let [url (:canonical_url item)]
          (if (and url (contains? seen url))
            result
            (-> result
                (update :seen conj url)
                (update :items conj item))))))
    {:seen #{}
     :items []}
    discovered)))

(defn process-source
  [sys index-cfg source-cfg]
  (let [discovered (source/discover sys source-cfg)
        unique-discovered (dedupe-discovered discovered)
        results (mapv #(process-discovered sys index-cfg source-cfg %) unique-discovered)
        documents (filterv :document results)
        errors (mapv :error (filter :error results))]
    {:source_cfg source-cfg
     :discovered_count (count discovered)
     :unique_discovered_count (count unique-discovered)
     :document_count (count documents)
     :chunk_count (reduce + 0 (map (comp count :chunks) documents))
     :error_count (count errors)
     :documents documents
     :errors errors}))

(defn- embedding-dimensions
  [index-cfg]
  (get-in index-cfg [:embedding :embedding_dimensions]))

(defn- attach-embeddings
  [sys index-cfg source-results]
  (let [chunks (vec (for [source-result source-results
                          document-result (:documents source-result)
                          chunk (:chunks document-result)]
                      chunk))
        embeddings (atom (when (seq chunks)
                           (embed/embed-batch sys
                                              (:embedding index-cfg)
                                              (mapv :content chunks))))
        next-embedding (fn []
                         (let [embedding (first @embeddings)]
                           (swap! embeddings rest)
                           embedding))]
    (mapv (fn [source-result]
            (update source-result
                    :documents
                    (fn [documents]
                      (mapv (fn [document-result]
                              (update document-result
                                      :chunks
                                      (fn [document-chunks]
                                        (mapv #(assoc % :embedding (next-embedding))
                                              document-chunks))))
                            documents))))
          source-results)))

(defn- source-metadata
  [source-result]
  {:discovered_count (:discovered_count source-result)
   :chunk_count (:chunk_count source-result)
   :errors (:errors source-result)})

(defn- persist-source!
  [tx run index-cfg structural-config-hash source-result]
  (let [source-cfg (:source_cfg source-result)
        dimensions (embedding-dimensions index-cfg)]
    (db/upsert-source! tx
                       run
                       source-cfg
                       structural-config-hash
                       {:document_count (:document_count source-result)
                        :error_count (:error_count source-result)
                        :metadata (source-metadata source-result)})
    (doseq [{:keys [document chunks]} (:documents source-result)]
      (let [document-row (db/insert-document! tx run source-cfg document)]
        (db/insert-chunks! tx dimensions run source-cfg document-row chunks)))))

(defn- persist-results!
  [ds run index-cfg structural-config-hash source-results]
  (jdbc/with-transaction [tx ds]
    (doseq [source-result source-results]
      (persist-source! tx run index-cfg structural-config-hash source-result))))

(defn- crawl-summary
  [run source-results]
  {:run_id (:id run)
   :index_name (:index_name run)
   :lifecycle_status (:lifecycle_status run)
   :verification_verdict (:verification_verdict run)
   :source_count (count source-results)
   :document_count (reduce + 0 (map :document_count source-results))
   :chunk_count (reduce + 0 (map :chunk_count source-results))
   :error_count (reduce + 0 (map :error_count source-results))
   :sources (mapv #(select-keys % [:source_cfg
                                   :discovered_count
                                   :document_count
                                   :chunk_count
                                   :error_count])
                  source-results)})

(defn- fail-run!
  [ds run e]
  (when run
    (db/update-run-status! ds
                           (:id run)
                           "error"
                           {:error_summary (or (ex-message e) (str e))}))
  e)

(defn crawl-index!
  [sys ds index-cfg]
  (db/with-index-lock!
    ds
    (:name index-cfg)
    (fn []
      (let [structural-config-hash (:alida.config/structural-hash (:alida/config sys))
            run (db/create-run! ds index-cfg structural-config-hash)]
        (try
          (db/update-run-status! ds (:id run) "crawling")
          (let [source-results (mapv #(process-source sys index-cfg %) (:sources index-cfg))]
            (db/update-run-status! ds (:id run) "embedding")
            (pgvector/ensure-run-partition! ds (embedding-dimensions index-cfg) (:id run))
            (let [source-results (attach-embeddings sys index-cfg source-results)]
              (persist-results! ds run index-cfg structural-config-hash source-results)
              (let [completed (db/update-run-status!
                              ds
                              (:id run)
                              "complete"
                              {:verification_verdict "caution"})]
                (crawl-summary completed source-results))))
          (catch Exception e
            (throw (ex-info (or (ex-message e) "Crawl failed")
                            (assoc (or (ex-data e) {})
                                   :type :alida.crawl/index-failed
                                   :run-id (:id run)
                                   :index-name (:name index-cfg))
                            (fail-run! ds run e)))))))))

(defn- failed-index
  [index-cfg e]
  {:index_name (:name index-cfg)
   :message (or (ex-message e) (str e))
   :data (ex-data e)})

(defn crawl!
  [sys ds {:keys [index-name]}]
  (let [indexes (run/selected-indexes sys index-name)]
    (reduce
     (fn [result index-cfg]
       (try
         (update result :succeeded conj (crawl-index! sys ds index-cfg))
         (catch Exception e
           (update result :failed conj (failed-index index-cfg e)))))
     {:succeeded []
      :failed []}
     indexes)))
