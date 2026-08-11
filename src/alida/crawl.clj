(ns alida.crawl
  (:require [alida.attestation :as attestation]
            [alida.chunk :as chunk]
            [alida.db.postgres :as db]
            [alida.diff :as diff]
            [alida.embed :as embed]
            [alida.extract.html :as html]
            [alida.extract.text :as extract-text]
            [alida.lang :as lang]
            [alida.notify.slack :as slack]
            [alida.report :as report]
            [alida.retry :as retry]
            [alida.run :as run]
            [alida.source :as source]
            [alida.vector.pgvector :as pgvector]
            [alida.verify :as verify]
            [clojure.string :as str]
            [com.brunobonacci.mulog :as u]
            [com.climate.claypoole :as cp]
            [next.jdbc :as jdbc])
  (:import [java.net URI]
           [java.time Duration Instant]))

(def default-source-concurrency 20)
(def default-inter-request-delay-ms 0)

(defn- html-content?
  [content-type]
  (let [content-type (str/lower-case (or content-type ""))]
    (or (str/starts-with? content-type "text/html")
        (str/starts-with? content-type "application/xhtml+xml"))))

(defn- text-content?
  [content-type]
  (let [content-type (str/lower-case (or content-type ""))]
    (or (str/starts-with? content-type "text/plain")
        (str/starts-with? content-type "text/markdown")
        (str/starts-with? content-type "application/json"))))

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
  (cond
    (html-content? (:content_type fetched))
    (cond-> (html/extract source-cfg
                          (source/html-extraction-options source-cfg)
                          fetched)
      (:external_id fetched) (assoc :external_id (:external_id fetched)))

    (text-content? (:content_type fetched))
    (cond-> (extract-text/extract source-cfg
                                  (source/html-extraction-options source-cfg)
                                  fetched)
      (:external_id fetched) (assoc :external_id (:external_id fetched)))

    :else
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
            {:skipped {:type :alida.crawl/empty-document
                       :source_id (:id source-cfg)
                       :canonical_url (:canonical_url fetched)
                       :title (:title document)
                       :locale (:locale document)
                       :normalized_content_hash (:normalized_content_hash document)}
             :empty_or_short_document true
             :crawl_stats crawl-stats}))))
    (catch Exception e
      {:error (error-details e {:source_id (:id source-cfg)
                                :canonical_url (:canonical_url fetched)})})))

(defn- process-discovered
  [sys index-cfg source-cfg gate-for-item discovered-item]
  (cond
    (source/skipped? discovered-item)
    {:skipped (merge {:source_id (:id source-cfg)}
                     (:alida/skipped discovered-item))}

    (source/anomaly? discovered-item)
    {:error (error-details discovered-item {:source_id (:id source-cfg)})}

    :else
    (try
      (when-let [fetch-gate (gate-for-item discovered-item)]
        (fetch-gate))
      (let [fetch-started (now-ns)]
        (try
          (let [fetched (source/fetch sys source-cfg discovered-item)
                fetch-duration-ms (elapsed-ms fetch-started)]
            (if (source/anomaly? fetched)
              {:error (error-details fetched {:source_id (:id source-cfg)
                                              :canonical_url (:canonical_url discovered-item)})
               :crawl_stats {:fetch_duration_ms fetch-duration-ms}}
              (if (source/skipped? fetched)
                {:skipped (merge {:source_id (:id source-cfg)
                                  :canonical_url (:canonical_url discovered-item)}
                                 (:alida/skipped fetched))
                 :crawl_stats {:fetch_duration_ms fetch-duration-ms}}
                (update (process-fetched index-cfg source-cfg fetched)
                        :crawl_stats
                        #(merge-with + {:fetch_duration_ms fetch-duration-ms} (or % {}))))))
          (catch Exception e
            {:error (error-details e {:source_id (:id source-cfg)
                                      :canonical_url (:canonical_url discovered-item)})
             :crawl_stats {:fetch_duration_ms (elapsed-ms fetch-started)}})))
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

(defn- source-concurrency
  [source-cfg]
  (or (:max_concurrency source-cfg)
      default-source-concurrency))

(defn- source-inter-request-delay-ms
  [source-cfg]
  (or (:inter_request_delay_ms source-cfg)
      default-inter-request-delay-ms))

(defn- clock-ms
  [sys]
  (if-let [clock-fn (:alida/clock-ms sys)]
    (clock-fn)
    (System/currentTimeMillis)))

(defn- wait-on-lock!
  [sys lock millis]
  (if-let [wait-fn (:alida/wait-on-lock sys)]
    (wait-fn lock millis)
    (.wait lock (long millis))))

(defn- request-gate
  [sys delay-ms]
  (when (pos? delay-ms)
    (let [lock (Object.)
          previous-start-ms (atom nil)]
      (fn []
        (locking lock
          (loop []
            (let [now-ms (clock-ms sys)
                  next-start-ms (some-> @previous-start-ms (+ delay-ms))
                  wait-ms (when next-start-ms
                            (- next-start-ms now-ms))]
              (if (and wait-ms (pos? wait-ms))
                (do
                  (wait-on-lock! sys lock wait-ms)
                  (recur))
                (reset! previous-start-ms now-ms)))))))))

(defn- uri-host
  [value]
  (try
    (let [uri (URI. value)
          scheme (some-> (.getScheme uri) str/lower-case)
          host (some-> (.getHost uri) str/lower-case)]
      (when (and (#{"http" "https"} scheme) (seq host))
        host))
    (catch Exception _
      nil)))

(defn- gate-key
  [source-cfg discovered-item]
  (or (some-> (:canonical_url discovered-item) uri-host)
      (str "source:" (:id source-cfg))))

(defn- request-gates
  [sys source-cfg delay-ms discovered-items]
  (if (pos? delay-ms)
    (let [keys (into #{}
                     (keep #(when-not (source/anomaly? %)
                              (gate-key source-cfg %)))
                     discovered-items)
          gates (zipmap keys (repeatedly #(request-gate sys delay-ms)))]
      #(get gates (gate-key source-cfg %)))
    (constantly nil)))

(defn- process-discovered-items
  [sys index-cfg source-cfg discovered-items]
  (let [concurrency (source-concurrency source-cfg)
        delay-ms (source-inter-request-delay-ms source-cfg)
        gate-for-item (request-gates sys source-cfg delay-ms discovered-items)]
    (if (<= concurrency 1)
      (mapv #(process-discovered sys index-cfg source-cfg gate-for-item %) discovered-items)
      (cp/with-shutdown! [pool (cp/threadpool concurrency :name "alida-crawl")]
        (vec (doall
              (cp/upmap pool
                        #(process-discovered sys index-cfg source-cfg gate-for-item %)
                        discovered-items)))))))

(defn- url-preference-score
  [source-cfg document-result]
  (let [url (get-in document-result [:document :canonical_url])]
    (count (filter #(and url (str/includes? url %))
                   (:dedupe_prefer_url_substrings source-cfg)))))

(defn- url-length
  [document-result]
  (count (or (get-in document-result [:document :canonical_url]) "")))

(defn- preferred-document
  [source-cfg existing candidate]
  (let [candidate-score (url-preference-score source-cfg candidate)
        existing-score (url-preference-score source-cfg existing)]
    (cond
      (> candidate-score existing-score)
      candidate

      (< candidate-score existing-score)
      existing

      (< (url-length candidate) (url-length existing))
      candidate

      :else
      existing)))

(defn- dedupe-documents-by-external-id
  [source-cfg documents]
  (->> documents
       (reduce (fn [result document-result]
                 (let [external-id (get-in document-result [:document :external_id])]
                   (if (seq external-id)
                     (if-let [existing (get-in result [:by-id external-id])]
                       (let [preferred (preferred-document source-cfg existing document-result)]
                         (-> result
                             (assoc-in [:by-id external-id] preferred)
                             (update :items
                                     (fn [items]
                                       (mapv #(if (= external-id
                                                     (get-in % [:document :external_id]))
                                                preferred
                                                %)
                                             items)))))
                       (-> result
                           (assoc-in [:by-id external-id] document-result)
                           (update :items conj document-result)))
                     (update result :items conj document-result))))
               {:by-id {}
                :items []})
       :items))

(defn- dedupe-documents-by-content
  [source-cfg documents]
  (if (:dedupe_content source-cfg)
    (->> documents
         (reduce (fn [by-hash document-result]
                   (let [content-hash (get-in document-result [:document :normalized_content_hash])]
                     (if-let [existing (get by-hash content-hash)]
                       (assoc by-hash
                              content-hash
                              (preferred-document source-cfg existing document-result))
                       (assoc by-hash content-hash document-result))))
                 (array-map))
         vals
         vec)
    documents))

(defn process-source
  [sys index-cfg source-cfg]
  (u/log ::source-start
         :index-name (:name index-cfg)
         :source-id (:id source-cfg)
         :source-type (:type source-cfg))
  (let [source-started (now-ns)
        discover-started (now-ns)
        discovered (source/discover sys source-cfg)
        discover-duration-ms (elapsed-ms discover-started)
        unique-discovered (dedupe-discovered discovered)
        _ (u/log ::source-discovered
                 :index-name (:name index-cfg)
                 :source-id (:id source-cfg)
                 :discovered-count (count discovered)
                 :unique-discovered-count (count unique-discovered)
                 :duration-ms discover-duration-ms)
        processing-started (now-ns)
        results (process-discovered-items sys index-cfg source-cfg unique-discovered)
        processing-duration-ms (elapsed-ms processing-started)
        processed-documents (filterv :document results)
        documents (->> processed-documents
                       (dedupe-documents-by-external-id source-cfg)
                       (dedupe-documents-by-content source-cfg))
        errors (mapv :error (filter :error results))
        skipped (mapv :skipped (filter :skipped results))
        empty-or-short-count (count (filter :empty_or_short_document results))
        item-stats (aggregate-stats (map :crawl_stats results))
        crawl-stats (merge-with +
                                {:source_duration_ms (elapsed-ms source-started)
                                 :discover_duration_ms discover-duration-ms
                                 :processing_duration_ms processing-duration-ms
                                 :max_concurrency (source-concurrency source-cfg)
                                 :inter_request_delay_ms (source-inter-request-delay-ms source-cfg)}
                                item-stats)]
    (u/log ::source-complete
           :index-name (:name index-cfg)
           :source-id (:id source-cfg)
           :documents (count documents)
           :chunks (reduce + 0 (map (comp count :chunks) documents))
           :errors (count errors)
           :skipped (count skipped)
           :duration-ms (:source_duration_ms crawl-stats))
    {:source_cfg source-cfg
     :discovered_count (count discovered)
     :unique_discovered_count (count unique-discovered)
     :processed_document_count (count processed-documents)
     :deduped_document_count (- (count processed-documents) (count documents))
     :document_count (count documents)
     :chunk_count (reduce + 0 (map (comp count :chunks) documents))
     :error_count (count errors)
     :skipped_count (count skipped)
     :empty_or_short_document_count empty-or-short-count
     :crawl_stats crawl-stats
     :documents documents
     :errors errors
     :skipped skipped}))

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
        _ (u/log ::embedding-start
                 :index-name (:name index-cfg)
                 :chunks (count chunks))
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
    (u/log ::embedding-complete
           :index-name (:name index-cfg)
           :chunks (:chunk_count stats)
           :reused (:reused_chunk_count stats)
           :embedded (:embedded_chunk_count stats)
           :requests (:embedding_request_count stats)
           :duration-ms (:duration_ms stats))
    {:source-results source-results
     :stats stats}))

(defn- source-metadata
  [source-result]
  {:discovered_count (:discovered_count source-result)
   :unique_discovered_count (:unique_discovered_count source-result)
   :processed_document_count (:processed_document_count source-result)
   :deduped_document_count (:deduped_document_count source-result)
   :chunk_count (:chunk_count source-result)
   :skipped_count (:skipped_count source-result)
   :crawl_stats (:crawl_stats source-result)
   :embedding_stats (:embedding_stats source-result)
   :errors (:errors source-result)
   :skipped (:skipped source-result)})

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
    (let [document-results (:documents source-result)
          document-rows (db/insert-documents! tx run source-cfg (map :document document-results))]
      (doseq [[document-row {:keys [chunks]}] (map vector document-rows document-results)]
        (db/insert-chunks! tx dimensions run source-cfg document-row chunks)))))

(defn- persist-results!
  [ds run index-cfg structural-config-hash source-results]
  (jdbc/with-transaction [tx ds]
    (doseq [source-result source-results]
      (persist-source! tx run index-cfg structural-config-hash source-result))))

(defn- crawl-summary
  [run config-path source-results embedding-stats phase-stats run-diff deterministic-verification verification]
  {:run_id (:id run)
   :index_name (:index_name run)
   :config_path config-path
   :lifecycle_status (:lifecycle_status run)
   :verification_verdict (:verification_verdict run)
   :diff run-diff
   :deterministic_verification deterministic-verification
   :verification verification
   :source_count (count source-results)
   :document_count (reduce + 0 (map :document_count source-results))
   :chunk_count (reduce + 0 (map :chunk_count source-results))
   :error_count (reduce + 0 (map :error_count source-results))
   :skipped_count (reduce + 0 (map :skipped_count source-results))
   :empty_or_short_document_count (reduce + 0 (map :empty_or_short_document_count source-results))
   :embedding_stats embedding-stats
   :phase_stats phase-stats
   :sources (mapv #(select-keys % [:source_cfg
                                   :discovered_count
                                   :document_count
                                   :chunk_count
                                   :error_count
                                   :skipped_count
                                   :empty_or_short_document_count
                                   :crawl_stats
                                   :embedding_stats])
                  source-results)})

(defn- document-key
  [document]
  [(:source_id document) (:canonical_url document)])

(defn- verification-chunk
  [chunk]
  (select-keys chunk [:chunk_index :chunk_count :heading_path :content :content_hash :estimated_tokens]))

(def max-verification-change-segments 100)
(def max-verification-change-characters 20000)

(defn- content-segments
  [chunks]
  (->> chunks
       (map :content)
       (remove nil?)
       (str/join "\n")
       (#(str/split % #"\n+"))
       (map str/trim)
       (remove str/blank?)
       vec))

(defn- ordered-multiset-difference
  [values subtracted-values]
  (:difference
   (reduce (fn [{:keys [remaining] :as result} value]
             (if (pos? (get remaining value 0))
               (update-in result [:remaining value] dec)
               (update result :difference conj value)))
           {:remaining (frequencies subtracted-values)
            :difference []}
           values)))

(defn- bounded-change-segments
  [segments]
  (loop [remaining (seq segments)
         included []
         character-count 0]
    (cond
      (nil? remaining)
      {:segments included}

      (<= max-verification-change-segments (count included))
      {:segments included
       :omitted_count (count remaining)}

      :else
      (let [segment (first remaining)
            available (- max-verification-change-characters character-count)]
        (cond
          (<= (count segment) available)
          (recur (next remaining)
                 (conj included segment)
                 (+ character-count (count segment)))

          (< 3 available)
          {:segments (conj included
                           (str (subs segment 0 (- available 3)) "..."))
           :omitted_count (count remaining)}

          :else
          {:segments included
           :omitted_count (count remaining)})))))

(defn- content-changes
  [previous-chunks current-chunks]
  (let [previous-segments (content-segments previous-chunks)
        current-segments (content-segments current-chunks)
        removed (bounded-change-segments
                 (ordered-multiset-difference previous-segments current-segments))
        added (bounded-change-segments
               (ordered-multiset-difference current-segments previous-segments))]
    (cond-> {:removed_segments (:segments removed)
             :added_segments (:segments added)}
      (pos? (:omitted_count removed 0))
      (assoc :removed_segments_omitted (:omitted_count removed))

      (pos? (:omitted_count added 0))
      (assoc :added_segments_omitted (:omitted_count added)))))

(defn- verification-document
  [{:keys [document chunks]}]
  (assoc (select-keys document [:source_id :canonical_url :title :locale :normalized_content_hash])
         :chunks (mapv verification-chunk chunks)))

(defn- current-diff-keys
  [run-diff]
  (let [added (map document-key (:added_urls run-diff))
        changed (map document-key (:changed_urls run-diff))
        moved (map (fn [entry] [(:source_id entry) (:current_canonical_url entry)])
                   (:moved_urls run-diff))]
    (set (concat added changed moved))))

(defn- verification-documents
  ([source-results run-diff]
   (verification-documents source-results run-diff {}))
  ([source-results run-diff previous-chunks-by-document]
   (let [wanted (current-diff-keys run-diff)]
     (->> source-results
          (mapcat (fn [{:keys [source_cfg documents]}]
                    ;; The in-memory document map carries :canonical_url but not
                    ;; :source_id (the extractors never set it; it is attached from
                    ;; source-cfg only at persist time). The diff keys, read back
                    ;; from the DB, do include source_id, so we must stamp it on
                    ;; here or document-key never matches and no changed/added page
                    ;; content reaches the verifier.
                    (map #(update % :document assoc :source_id (:id source_cfg))
                         documents)))
          (filter #(contains? wanted (document-key (:document %))))
          (mapv (fn [{:keys [document chunks] :as result}]
                  (cond-> (verification-document result)
                    (contains? previous-chunks-by-document (document-key document))
                    (assoc :content_changes
                           (content-changes
                            (get previous-chunks-by-document (document-key document))
                            chunks)))))))))

(defn- previous-change-chunks
  [ds run-diff]
  (let [document-keys (mapv document-key (:changed_urls run-diff))]
    (if (and (:previous_run_id run-diff) (seq document-keys))
      (if-let [previous-run (db/get-run ds (:previous_run_id run-diff))]
        (group-by document-key
                  (db/list-document-chunk-content ds
                                                  (:embedding_dimensions previous-run)
                                                  (:id previous-run)
                                                  document-keys))
        {})
      {})))

(defn- llm-verification-enabled?
  [verification-cfg]
  (not= false (:enabled verification-cfg)))

(defn- wait-between-verification-prompts!
  [sys verification-cfg]
  (let [delay-ms (or (:inter_prompt_delay_ms verification-cfg)
                     verify/default-inter-prompt-delay-ms)]
    (when (pos? delay-ms)
      (retry/sleep! sys delay-ms))))

(defn- maybe-synthesize-prose!
  [sys verification-cfg run results combined]
  (if (or (verify/prose-summary-current? combined)
          (not (verify/prose-synthesis-needed? combined results)))
    combined
    (do
      (wait-between-verification-prompts! sys verification-cfg)
      (u/log ::verification-prose-summary-start
             :index-name (:index_name run)
             :run-id (:id run)
             :batch-count (count results))
      (try
        (let [synthesis-result
              (verify/complete-with-retries
               sys
               verification-cfg
               {:system-prompt verify/prose-summary-system-prompt}
               (verify/build-prose-summary-prompt results))
              synthesized (verify/apply-prose-summary combined results synthesis-result)
              accepted? (verify/prose-summary-current? synthesized)]
          (u/log ::verification-prose-summary-complete
                 :index-name (:index_name run)
                 :run-id (:id run)
                 :accepted accepted?)
          synthesized)
        (catch Exception e
          (u/log ::verification-prose-summary-failed
                 :index-name (:index_name run)
                 :run-id (:id run)
                 :message (ex-message e)
                 :type (:type (ex-data e)))
          combined)))))

(defn- maybe-synthesize-cached-prose!
  [sys verification-cfg run result]
  (if (verify/prose-summary-current? result)
    result
    (try
      (if-let [raw-batches (seq (get-in result [:raw_response :batches]))]
        (let [results (mapv verify/parse-structured-verdict raw-batches)]
          (maybe-synthesize-prose! sys verification-cfg run results result))
        result)
      (catch Exception e
        (u/log ::verification-cached-prose-summary-skipped
               :index-name (:index_name run)
               :run-id (:id run)
               :message (ex-message e)
               :type (:type (ex-data e)))
        result))))

(defn- complete-llm-verification!
  [sys verification-cfg run prompts]
  (let [results (mapv (fn [index prompt]
                        (when (pos? index)
                          (wait-between-verification-prompts! sys verification-cfg))
                        (u/log ::verification-prompt-start
                               :index-name (:index_name run)
                               :run-id (:id run)
                               :prompt-number (inc index)
                               :prompt-count (count prompts))
                        (verify/complete-with-retries sys verification-cfg prompt))
                      (range)
                      prompts)
        combined (verify/combine-batch-results results)]
    (maybe-synthesize-prose! sys verification-cfg run results combined)))

(defn- resolve-llm-verification!
  [sys ds verification-cfg run prompts]
  (let [verification-input-hash (verify/verification-input-hash verification-cfg prompts)]
    (if-let [cached (attestation/find-result ds verification-cfg verification-input-hash)]
      (let [cached (if (= "cache" (:source cached))
                     (update cached
                             :llm-result
                             #(maybe-synthesize-cached-prose!
                               sys verification-cfg run %))
                     cached)]
        (u/log ::verification-attestation-reused
               :index-name (:index_name run)
               :run-id (:id run)
               :verification-input-hash verification-input-hash
               :llm-result-source (:source cached)
               :attestor (:attestor cached))
        (assoc cached :verification-input-hash verification-input-hash))
      (let [llm-result (complete-llm-verification! sys verification-cfg run prompts)
            local-attestor (when (attestation/enabled? verification-cfg)
                             (attestation/attestor verification-cfg))]
        {:llm-result llm-result
         :verification-input-hash verification-input-hash
         :source "provider"
         :attestor local-attestor}))))

(defn- notification-label
  [sys]
  (not-empty (str/trim (or (get-in sys [:alida/config :notifications :label]) ""))))

(defn- verify-run!
  [sys ds run run-diff deterministic-verification source-results]
  (let [verification-cfg (:verification (:alida/config sys))
        llm-details (when (llm-verification-enabled? verification-cfg)
                      (let [prompts (verify/build-prompts
                                     {:index_name (:index_name run)
                                      :deterministic_verification deterministic-verification
                                      :diff run-diff
                                      :documents (verification-documents
                                                  source-results
                                                  run-diff
                                                  (previous-change-chunks ds run-diff))
                                      :max_prompt_tokens (:max_prompt_tokens verification-cfg)})]
                        (u/log ::verification-start
                               :index-name (:index_name run)
                               :run-id (:id run)
                               :prompt-count (count prompts)
                               :deterministic-verdict (:deterministic_verdict deterministic-verification))
                        (resolve-llm-verification! sys ds verification-cfg run prompts)))
        llm-result (:llm-result llm-details)
        _ (if llm-result
            (u/log ::verification-complete
                   :index-name (:index_name run)
                   :run-id (:id run)
                   :llm-verdict (:verdict llm-result))
            (u/log ::verification-disabled
                   :index-name (:index_name run)
                   :run-id (:id run)))
        final-verdict (if llm-result
                        (verify/strictest-verdict
                         (:deterministic_verdict deterministic-verification)
                         (:verdict llm-result))
                        ;; With LLM verification disabled, the gate is incomplete:
                        ;; the deterministic checks ran but no LLM review did. Such
                        ;; a run must not earn an auto-activating "pass", so cap it
                        ;; at "caution" (still activatable manually with --allow-caution).
                        (verify/strictest-verdict
                         (:deterministic_verdict deterministic-verification)
                         "caution"))
        verification (merge
                      {:provider (if llm-result (:provider verification-cfg) "disabled")
                       :model (if llm-result (verify/verifier-model verification-cfg) "llm-verification-disabled")
                       :deterministic_verdict (:deterministic_verdict deterministic-verification)
                       :deterministic_findings (:deterministic_findings deterministic-verification)
                       :final_verdict final-verdict
                       :reasoning (if llm-result
                                    (:reasoning llm-result)
                                    "LLM verification was disabled by config.")
                       :raw_response (if llm-result
                                       (:raw_response llm-result)
                                       {:llm_verification_enabled false})}
                      (when llm-result
                        {:llm_verdict (:verdict llm-result)
                         :llm_security_findings (:security_findings llm-result)
                         :verification_input_hash (:verification-input-hash llm-details)
                         :llm_result_source (:source llm-details)
                         :attestation_attestor (:attestor llm-details)}))]
    (db/save-verification! ds (:id run) verification)
    ;; Persist the per-run reference before the reusable row. Pruning can then
    ;; either see the reference and retain the attestation, or run first and let
    ;; this write recreate it. This avoids an unreferenced window between the
    ;; two writes during a concurrent crawl and prune.
    (when (contains? #{"cache" "provider"} (:source llm-details))
      (attestation/save-result! ds
                                verification-cfg
                                (:verification-input-hash llm-details)
                                llm-result))
    verification))

(defn- fail-run!
  [ds run e]
  (when run
    (db/update-run-status! ds
                           (:id run)
                           "error"
                           {:error_summary (or (ex-message e) (str e))}))
  e)

(defn- compute-and-save-diff!
  [ds run]
  (let [previous-run (db/get-live-run ds (:index_name run))
        previous-documents (if previous-run
                             (db/list-run-documents ds (:id previous-run))
                             [])
        current-documents (db/list-run-documents ds (:id run))
        run-diff (diff/compute previous-documents current-documents)]
    (db/save-run-diff! ds (:id run) (:id previous-run) run-diff)
    (assoc run-diff :previous_run_id (:id previous-run))))

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
                                 {:embedding_fingerprint (embed/fingerprint (:embedding index-cfg))
                                  :embedding_provider (get-in index-cfg [:embedding :provider])
                                  :embedding_disabled (= "noop" (get-in index-cfg [:embedding :provider]))})]
        (u/log ::index-start
               :index-name (:name index-cfg)
               :run-id (:id run)
               :source-count (count (:sources index-cfg)))
        (try
          (u/log ::phase-start :index-name (:name index-cfg) :run-id (:id run) :phase "crawling")
          (db/update-run-status! ds (:id run) "crawling")
          (let [crawl-started (now-ns)
                source-results (mapv #(process-source sys index-cfg %) (:sources index-cfg))
                crawl-duration-ms (elapsed-ms crawl-started)
                crawl-stats (dissoc (aggregate-stats (map :crawl_stats source-results))
                                    :max_concurrency)]
            (u/log ::phase-start :index-name (:name index-cfg) :run-id (:id run) :phase "embedding")
            (db/update-run-status! ds (:id run) "embedding")
            (pgvector/create-run-partition! ds (embedding-dimensions index-cfg) (:id run))
            (let [{:keys [source-results stats]} (attach-embeddings sys ds index-cfg source-results)
                  persist-started (now-ns)]
              (u/log ::phase-start :index-name (:name index-cfg) :run-id (:id run) :phase "persisting")
              (persist-results! ds run index-cfg structural-config-hash source-results)
              ;; Build the HNSW index once the partition is fully loaded.
              (pgvector/create-run-index! ds (embedding-dimensions index-cfg) (:id run))
              (let [persist-duration-ms (elapsed-ms persist-started)
                    phase-stats-before-verification (merge crawl-stats
                                                           {:crawl_duration_ms crawl-duration-ms
                                                            :embedding_duration_ms (:duration_ms stats)
                                                            :persist_duration_ms persist-duration-ms})
                    verifying (db/update-run-status!
                               ds
                               (:id run)
                               "verifying"
                               {:metadata {:embedding_stats stats
                                           :phase_stats phase-stats-before-verification}})
                    _ (u/log ::phase-start
                             :index-name (:name index-cfg)
                             :run-id (:id run)
                             :phase "verifying")
                    run-diff (compute-and-save-diff! ds verifying)
                    config-path (get-in sys [:alida/config :alida.config/path])
                    partial-summary (crawl-summary verifying
                                                   config-path
                                                   source-results
                                                   stats
                                                   phase-stats-before-verification
                                                   run-diff
                                                   nil
                                                   nil)
                    deterministic-verification (verify/deterministic-gate
                                                (:verification (:alida/config sys))
                                                partial-summary
                                                run-diff)
                    _ (db/save-deterministic-verification!
                       ds
                       (:id run)
                       (assoc deterministic-verification
                              :provider "deterministic"
                              :model (or (get-in sys [:alida/config :verification :deterministic_gate_version])
                                         "deterministic-gate")))
                    verification-started (now-ns)
                    verification (verify-run! sys
                                              ds
                                              verifying
                                              run-diff
                                              deterministic-verification
                                              source-results)
                    phase-stats (assoc phase-stats-before-verification
                                       :verification_duration_ms (elapsed-ms verification-started))
                    completed (db/update-run-status!
                               ds
                               (:id run)
                               "complete"
                               {:verification_verdict (:final_verdict verification)
                                :metadata {:embedding_stats stats
                                           :phase_stats phase-stats}})
                    action (run/decide-action index-cfg
                                              {:final_verdict (:final_verdict verification)
                                               :first_run (nil? (:previous_run_id run-diff))})
                    final-run (if (= :activate action)
                                (db/activate-run! ds (:id completed))
                                completed)
                    summary (crawl-summary final-run
                                           config-path
                                           source-results
                                           stats
                                           phase-stats
                                           run-diff
                                           deterministic-verification
                                           verification)
                    summary (assoc summary :notification_label (notification-label sys))
                    built-report (report/build summary)
                    _ (db/save-report! ds (:id run) built-report)
                    _ (u/log ::notification-start
                             :index-name (:name index-cfg)
                             :run-id (:id run))
                    notification (slack/post-report! sys built-report)]
                (u/log ::index-complete
                       :index-name (:name index-cfg)
                       :run-id (:id run)
                       :verdict (:verification_verdict final-run)
                       :notification-sent (:sent notification))
                (assoc summary :notification notification))))
          (catch Exception e
            (let [failure-data (assoc (or (ex-data e) {})
                                      :type :alida.crawl/index-failed
                                      :run-id (:id run)
                                      :index-name (:name index-cfg))
                  cause (fail-run! ds run e)
                  failure-text (report/failure-summary
                                {:run_id (:id run)
                                 :index_name (:name index-cfg)
                                 :message (or (ex-message e) "Crawl failed")
                                 :data (cond-> failure-data
                                         (notification-label sys)
                                         (assoc :notification_label (notification-label sys)))})
                  notification (slack/post-text! sys failure-text)]
              (u/log ::index-failed
                     :index-name (:name index-cfg)
                     :run-id (:id run)
                     :message (or (ex-message e) (str e))
                     :status (:status failure-data)
                     :error-type (:type failure-data)
                     :notification-sent (:sent notification))
              (throw (ex-info (or (ex-message e) "Crawl failed")
                              (assoc failure-data :notification notification)
                              cause)))))))))

(defn- failed-index
  [index-cfg e]
  {:index_name (:name index-cfg)
   :message (or (ex-message e) (str e))
   :data (ex-data e)})

(defn- prune-history!
  [ds indexes max-age-days]
  (let [index-names (mapv :name indexes)
        older-than (.minus (Instant/now) (Duration/ofDays max-age-days))]
    (u/log ::history-prune-start
           :index-names index-names
           :max-age-days max-age-days)
    (try
      (let [result (db/prune-runs! ds {:older-than older-than
                                       :index-names index-names})]
        (u/log ::history-prune-complete
               :index-names index-names
               :max-age-days max-age-days
               :pruned-count (:pruned_count result)
               :pruned-attestation-count (:pruned_attestation_count result))
        (assoc result :max_age_days max-age-days))
      (catch Exception e
        (let [message (or (ex-message e) (str e))]
          (u/log ::history-prune-failed
                 :index-names index-names
                 :max-age-days max-age-days
                 :message message)
          {:failed true
           :message message
           :max_age_days max-age-days})))))

(defn- apply-retention!
  [sys ds indexes result]
  (if-let [max-age-days (get-in sys [:alida/config :retention :max_age_days])]
    (if (seq (:failed result))
      (do
        (u/log ::history-prune-skipped
               :reason "crawl-failed"
               :failed-index-count (count (:failed result))
               :max-age-days max-age-days)
        (assoc result :pruning {:skipped true
                                :reason :crawl-failed
                                :max_age_days max-age-days}))
      (assoc result :pruning (prune-history! ds indexes max-age-days)))
    result))

(defn crawl!
  [sys ds {:keys [index-name]}]
  (db/reconcile-orphaned-runs! ds)
  (let [indexes (run/selected-indexes sys index-name)]
    (apply-retention!
     sys
     ds
     indexes
     (reduce
      (fn [result index-cfg]
        (try
          (update result :succeeded conj (crawl-index! sys ds index-cfg))
          (catch Exception e
            (update result :failed conj (failed-index index-cfg e)))))
      {:succeeded []
       :failed []}
      indexes))))
