(ns alida.crawl
  (:require [alida.chunk :as chunk]
            [alida.db.postgres :as db]
            [alida.embed :as embed]
            [alida.extract.html :as html]
            [alida.lang :as lang]
            [alida.report :as report]
            [alida.run :as run]
            [alida.source :as source]
            [alida.vector.pgvector :as pgvector]
            [clojure.string :as str]
            [com.climate.claypoole :as cp]
            [next.jdbc :as jdbc]))

(def default-source-concurrency 20)

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

(defn- now-ns
  []
  (System/nanoTime))

(defn- elapsed-ms
  [started-ns]
  (long (/ (- (now-ns) started-ns) 1000000)))

(defn- aggregate-stats
  [stats]
  (reduce (partial merge-with +) {} stats))

(defn- retained-document
  [document]
  (dissoc document :blocks :normalized_content))

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
    (let [extract-started (now-ns)
          document (extract-document source-cfg fetched)
          extract-duration-ms (elapsed-ms extract-started)]
      (if (source/anomaly? document)
        {:error (error-details document {:source_id (:id source-cfg)
                                         :canonical_url (:canonical_url fetched)})
         :crawl_stats {:extract_duration_ms extract-duration-ms}}
        (let [language-started (now-ns)
              document (lang/annotate-document index-cfg source-cfg document)
              language-duration-ms (elapsed-ms language-started)
              chunk-started (now-ns)
              chunks (chunk/section-aware document (:chunking index-cfg))
              chunk-duration-ms (elapsed-ms chunk-started)
              crawl-stats {:extract_duration_ms extract-duration-ms
                           :language_duration_ms language-duration-ms
                           :chunk_duration_ms chunk-duration-ms}]
          (if (seq chunks)
            {:document (retained-document document)
             :chunks chunks
             :crawl_stats crawl-stats}
            {:error {:type :alida.crawl/empty-document
                     :source_id (:id source-cfg)
                     :canonical_url (:canonical_url fetched)
                     :title (:title document)
                     :locale (:locale document)
                     :normalized_content_hash (:normalized_content_hash document)}
             :crawl_stats crawl-stats}))))
    (catch Exception e
      {:error (error-details e {:source_id (:id source-cfg)
                                :canonical_url (:canonical_url fetched)})})))

(defn- process-discovered
  [sys index-cfg source-cfg discovered-item]
  (if (source/anomaly? discovered-item)
    {:error (error-details discovered-item {:source_id (:id source-cfg)})}
    (let [fetch-started (now-ns)]
      (try
        (let [fetched (source/fetch sys source-cfg discovered-item)
              fetch-duration-ms (elapsed-ms fetch-started)]
        (if (source/anomaly? fetched)
          {:error (error-details fetched {:source_id (:id source-cfg)
                                          :canonical_url (:canonical_url discovered-item)})
           :crawl_stats {:fetch_duration_ms fetch-duration-ms}}
          (update (process-fetched index-cfg source-cfg fetched)
                  :crawl_stats
                  #(merge-with + {:fetch_duration_ms fetch-duration-ms} (or % {})))))
        (catch Exception e
          {:error (error-details e {:source_id (:id source-cfg)
                                    :canonical_url (:canonical_url discovered-item)})
           :crawl_stats {:fetch_duration_ms (elapsed-ms fetch-started)}})))))

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

(defn- source-concurrency
  [source-cfg]
  (or (:max_concurrency source-cfg)
      default-source-concurrency))

(defn- process-discovered-items
  [sys index-cfg source-cfg discovered-items]
  (let [concurrency (source-concurrency source-cfg)]
    (if (<= concurrency 1)
      (mapv #(process-discovered sys index-cfg source-cfg %) discovered-items)
      (let [pool (cp/threadpool concurrency :name "alida-crawl")]
        (try
          (vec (cp/pmap pool
                        #(process-discovered sys index-cfg source-cfg %)
                        discovered-items))
          (finally
            (cp/shutdown! pool)))))))

(defn process-source
  [sys index-cfg source-cfg]
  (let [source-started (now-ns)
        discover-started (now-ns)
        discovered (source/discover sys source-cfg)
        discover-duration-ms (elapsed-ms discover-started)
        unique-discovered (dedupe-discovered discovered)
        processing-started (now-ns)
        results (process-discovered-items sys index-cfg source-cfg unique-discovered)
        processing-duration-ms (elapsed-ms processing-started)
        documents (filterv :document results)
        errors (mapv :error (filter :error results))
        item-stats (aggregate-stats (map :crawl_stats results))
        crawl-stats (merge-with +
                                {:source_duration_ms (elapsed-ms source-started)
                                 :discover_duration_ms discover-duration-ms
                                 :processing_duration_ms processing-duration-ms
                                 :max_concurrency (source-concurrency source-cfg)}
                                item-stats)]
    {:source_cfg source-cfg
     :discovered_count (count discovered)
     :unique_discovered_count (count unique-discovered)
     :document_count (count documents)
     :chunk_count (reduce + 0 (map (comp count :chunks) documents))
     :error_count (count errors)
     :crawl_stats crawl-stats
     :documents documents
     :errors errors}))

(defn- embedding-dimensions
  [index-cfg]
  (get-in index-cfg [:embedding :embedding_dimensions]))

(defn- all-chunks
  [source-results]
  (vec (for [source-result source-results
             document-result (:documents source-result)
             chunk (:chunks document-result)]
         chunk)))

(defn- source-embedding-stats
  [source-result reusable-hashes embedded-hashes]
  (let [chunk-hashes (vec (for [document-result (:documents source-result)
                                chunk (:chunks document-result)]
                            (:content_hash chunk)))
        reused-count (count (filter reusable-hashes chunk-hashes))
        embedded-count (count (filter embedded-hashes chunk-hashes))]
    {:chunk_count (count chunk-hashes)
     :reused_chunk_count reused-count
     :embedded_chunk_count embedded-count
     :embedding_request_count (count (set (filter embedded-hashes chunk-hashes)))}))

(defn- attach-embeddings
  [sys ds index-cfg source-results]
  (let [started (now-ns)
        chunks (all-chunks source-results)
        fingerprint (embed/fingerprint (:embedding index-cfg))
        reuse-started (now-ns)
        reusable-by-hash (db/reusable-embeddings ds
                                                 (embedding-dimensions index-cfg)
                                                 (:name index-cfg)
                                                 fingerprint
                                                 (map :content_hash chunks))
        reuse-lookup-duration-ms (elapsed-ms reuse-started)
        reusable-hashes (set (keys reusable-by-hash))
        content-by-hash (into {}
                              (map (juxt :content_hash :content))
                              chunks)
        missing-hashes (vec (remove reusable-hashes (keys content-by-hash)))
        provider-started (now-ns)
        new-embeddings (if (seq missing-hashes)
                         (embed/embed-batch sys
                                            (:embedding index-cfg)
                                            (mapv content-by-hash missing-hashes))
                         [])
        provider-duration-ms (elapsed-ms provider-started)
        embedded-by-hash (zipmap missing-hashes new-embeddings)
        embedding-by-hash (merge reusable-by-hash embedded-by-hash)
        embedded-hashes (set missing-hashes)
        source-results (mapv
                        (fn [source-result]
                          (-> source-result
                              (assoc :embedding_stats
                                     (source-embedding-stats source-result
                                                             reusable-hashes
                                                             embedded-hashes))
                              (update :documents
                                      (fn [documents]
                                        (mapv (fn [document-result]
                                                (update document-result
                                                        :chunks
                                                        (fn [document-chunks]
                                                          (mapv #(assoc % :embedding
                                                                        (get embedding-by-hash
                                                                             (:content_hash %)))
                                                                document-chunks))))
                                              documents)))))
                        source-results)
        stats {:chunk_count (count chunks)
               :reused_chunk_count (count (filter reusable-hashes (map :content_hash chunks)))
               :embedded_chunk_count (count (filter embedded-hashes (map :content_hash chunks)))
               :embedding_request_count (count missing-hashes)
               :reuse_lookup_duration_ms reuse-lookup-duration-ms
               :provider_duration_ms provider-duration-ms
               :duration_ms (elapsed-ms started)}]
    {:source-results source-results
     :stats stats}))

(defn- source-metadata
  [source-result]
  {:discovered_count (:discovered_count source-result)
   :chunk_count (:chunk_count source-result)
   :crawl_stats (:crawl_stats source-result)
   :embedding_stats (:embedding_stats source-result)
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
  [run source-results embedding-stats phase-stats]
  {:run_id (:id run)
   :index_name (:index_name run)
   :lifecycle_status (:lifecycle_status run)
   :verification_verdict (:verification_verdict run)
   :source_count (count source-results)
   :document_count (reduce + 0 (map :document_count source-results))
   :chunk_count (reduce + 0 (map :chunk_count source-results))
   :error_count (reduce + 0 (map :error_count source-results))
   :embedding_stats embedding-stats
   :phase_stats phase-stats
   :sources (mapv #(select-keys % [:source_cfg
                                   :discovered_count
                                   :document_count
                                   :chunk_count
                                   :error_count
                                   :crawl_stats
                                   :embedding_stats])
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
            run (db/create-run! ds
                                index-cfg
                                structural-config-hash
                                {:embedding_fingerprint (embed/fingerprint (:embedding index-cfg))})]
        (try
          (db/update-run-status! ds (:id run) "crawling")
          (let [crawl-started (now-ns)
                source-results (mapv #(process-source sys index-cfg %) (:sources index-cfg))
                crawl-duration-ms (elapsed-ms crawl-started)
                crawl-stats (dissoc (aggregate-stats (map :crawl_stats source-results))
                                    :max_concurrency)]
            (db/update-run-status! ds (:id run) "embedding")
            (pgvector/ensure-run-partition! ds (embedding-dimensions index-cfg) (:id run))
            (let [{:keys [source-results stats]} (attach-embeddings sys ds index-cfg source-results)
                  persist-started (now-ns)]
              (persist-results! ds run index-cfg structural-config-hash source-results)
              (let [persist-duration-ms (elapsed-ms persist-started)
                    phase-stats (merge crawl-stats
                                       {:crawl_duration_ms crawl-duration-ms
                                        :embedding_duration_ms (:duration_ms stats)
                                        :persist_duration_ms persist-duration-ms})
                    completed (db/update-run-status!
                               ds
                               (:id run)
                               "complete"
                               {:metadata {:embedding_stats stats
                                           :phase_stats phase-stats}})
                    summary (crawl-summary completed source-results stats phase-stats)]
                (db/save-report! ds (:id run) (report/build summary))
                summary)))
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
  (db/reconcile-orphaned-runs! ds)
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
