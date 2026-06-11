(ns alida.report
  (:require [clojure.string :as str]))

(defn- value
  [x]
  (or x 0))

(defn- verdict
  [summary]
  (or (:verification_verdict summary) "-"))

(defn- diff-count
  [summary k]
  (get-in summary [:diff :summary k] 0))

(defn slack-summary
  [{:keys [run_id index_name document_count chunk_count error_count embedding_stats phase_stats] :as summary}]
  (format "%s run %s: documents=%s, chunks=%s, errors=%s, added=%s, removed=%s, changed=%s, moved=%s, reused=%s, embedded=%s, crawl_ms=%s, verdict=%s"
          index_name
          run_id
          (value document_count)
          (value chunk_count)
          (value error_count)
          (diff-count summary :added_count)
          (diff-count summary :removed_count)
          (diff-count summary :changed_count)
          (diff-count summary :moved_count)
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

(defn- url-line
  [entry]
  (str "- " (:source_id entry) " " (:canonical_url entry)))

(defn- changed-line
  [entry]
  (str "- " (:source_id entry) " " (:canonical_url entry)
       " "
       (:previous_normalized_content_hash entry)
       " -> "
       (:current_normalized_content_hash entry)))

(defn- moved-line
  [entry]
  (str "- " (:source_id entry) " "
       (:previous_canonical_url entry)
       " -> "
       (:current_canonical_url entry)))

(defn full-report
  [{:keys [run_id index_name lifecycle_status source_count document_count chunk_count error_count
           embedding_stats phase_stats sources diff]
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
             ["Diff"
              (str "previous_run_id: " (or (:previous_run_id diff) "-"))
              (str "added: " (diff-count summary :added_count))
              (str "removed: " (diff-count summary :removed_count))
              (str "changed: " (diff-count summary :changed_count))
              (str "moved: " (diff-count summary :moved_count))])
            (section "Added URLs" (map url-line (:added_urls diff)))
            (section "Removed URLs" (map url-line (:removed_urls diff)))
            (section "Changed URLs" (map changed-line (:changed_urls diff)))
            (section "Moved URLs" (map moved-line (:moved_urls diff)))
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
