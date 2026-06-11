(ns alida.report
  (:require [clojure.string :as str]))

(defn- value
  [x]
  (or x 0))

(defn- verdict
  [summary]
  (or (:verification_verdict summary) "-"))

(defn slack-summary
  [{:keys [run_id index_name document_count chunk_count error_count embedding_stats phase_stats] :as summary}]
  (format "%s run %s: documents=%s, chunks=%s, errors=%s, reused=%s, embedded=%s, crawl_ms=%s, verdict=%s"
          index_name
          run_id
          (value document_count)
          (value chunk_count)
          (value error_count)
          (value (:reused_chunk_count embedding_stats))
          (value (:embedded_chunk_count embedding_stats))
          (value (:crawl_duration_ms phase_stats))
          (verdict summary)))

(defn- source-line
  [{:keys [source_cfg document_count chunk_count error_count crawl_stats embedding_stats]}]
  (format "- %s (%s): documents=%s, chunks=%s, errors=%s, fetch_ms=%s, extract_ms=%s, chunk_ms=%s, reused=%s, embedded=%s"
          (:id source_cfg)
          (:type source_cfg)
          (value document_count)
          (value chunk_count)
          (value error_count)
          (value (:fetch_duration_ms crawl_stats))
          (value (:extract_duration_ms crawl_stats))
          (value (:chunk_duration_ms crawl_stats))
          (value (:reused_chunk_count embedding_stats))
          (value (:embedded_chunk_count embedding_stats))))

(defn- section
  [title lines]
  (when (seq lines)
    (str/join \newline (cons title lines))))

(defn full-report
  [{:keys [run_id index_name lifecycle_status source_count document_count chunk_count error_count
           embedding_stats phase_stats sources]
    :as summary}]
  (str/join
   "\n\n"
   (remove nil?
           [(str/join
             \newline
             [(str "Run: " run_id)
              (str "Index: " index_name)
              (str "Status: " lifecycle_status)
              (str "Verdict: " (verdict summary))
              (str "Sources: " (value source_count))
              (str "Documents: " (value document_count))
              (str "Chunks: " (value chunk_count))
              (str "Errors: " (value error_count))])
            (str/join
             \newline
             ["Timings"
              (str "crawl_ms: " (value (:crawl_duration_ms phase_stats)))
              (str "fetch_ms: " (value (:fetch_duration_ms phase_stats)))
              (str "extract_ms: " (value (:extract_duration_ms phase_stats)))
              (str "language_ms: " (value (:language_duration_ms phase_stats)))
              (str "chunk_ms: " (value (:chunk_duration_ms phase_stats)))
              (str "embedding_ms: " (value (:embedding_duration_ms phase_stats)))
              (str "persist_ms: " (value (:persist_duration_ms phase_stats)))])
            (str/join
             \newline
             ["Embedding"
              (str "reused_chunks: " (value (:reused_chunk_count embedding_stats)))
              (str "embedded_chunks: " (value (:embedded_chunk_count embedding_stats)))
              (str "embedding_requests: " (value (:embedding_request_count embedding_stats)))
              (str "reuse_lookup_ms: " (value (:reuse_lookup_duration_ms embedding_stats)))
              (str "provider_ms: " (value (:provider_duration_ms embedding_stats)))])
            (section "Sources" (map source-line sources))])))

(defn build
  [summary]
  {:slack_summary (slack-summary summary)
   :full_report (full-report summary)})
