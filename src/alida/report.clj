(ns alida.report
  (:require [clojure.string :as str]))

(defn- value
  [x]
  (or x 0))

(defn- verdict
  [summary]
  (or (:verification_verdict summary) "-"))

(defn- deterministic-verdict
  [summary]
  (or (get-in summary [:deterministic_verification :deterministic_verdict]) "-"))

(defn- llm-verdict
  [summary]
  (or (get-in summary [:verification :llm_verdict]) "-"))

(defn- diff-count
  [summary k]
  (get-in summary [:diff :summary k] 0))

(defn- ms
  [phase-stats k]
  (value (get phase-stats k)))

(defn slack-summary
  [{:keys [run_id index_name document_count chunk_count error_count embedding_stats phase_stats] :as summary}]
  (format "%s run %s: documents=%s, chunks=%s, errors=%s, added=%s, removed=%s, changed=%s, moved=%s, reused=%s, embedded=%s, crawl_ms=%s, deterministic=%s, llm=%s, verdict=%s"
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
          (deterministic-verdict summary)
          (llm-verdict summary)
          (verdict summary)))

(defn- slack-escape
  [value]
  (-> (str value)
      (str/replace "&" "&amp;")
      (str/replace "<" "&lt;")
      (str/replace ">" "&gt;")))

(defn- truncate
  [value max-length]
  (let [value (str value)]
    (if (<= (count value) max-length)
      value
      (str (subs value 0 (- max-length 3)) "..."))))

(defn- field
  [label value]
  {:type "mrkdwn"
   :text (str "*" label "*\n" (slack-escape value))})

(defn- command
  [& parts]
  (str "`" (str/join " " parts) "`"))

(defn- short-run-id
  [run-id]
  (subs (str run-id) 0 8))

(defn- verdict-emoji
  [value]
  (case value
    "pass" "✅"
    "caution" "⚠️"
    "fail" "❌"
    "ℹ️"))

(defn- verdict-label
  [value]
  (case value
    "pass" "passed"
    "caution" "needs review"
    "fail" "failed"
    "unknown"))

(defn- action-line
  [{:keys [run_id diff] :as summary}]
  (let [first-run? (nil? (:previous_run_id diff))
        final-verdict (verdict summary)]
    (cond
      (and first-run? (= "pass" final-verdict))
      "First run: auto-activation is disabled. Inspect the report before activating a real embedding run."

      (= "caution" final-verdict)
      (str "Review required. Inspect with "
           (command "alida-vector" "report" run_id)
           ", then activate with "
           (command "alida-vector" "activate" run_id "--allow-caution")
           " or reject with "
           (command "alida-vector" "reject" run_id)
           ".")

      (= "fail" final-verdict)
      (str "Verification failed. Inspect with "
           (command "alida-vector" "report" run_id)
           " and reject with "
           (command "alida-vector" "reject" run_id)
           ".")

      :else
      "Inspect the full report if anything looks unexpected.")))

(defn- action-commands
  [{:keys [run_id] :as summary}]
  (let [final-verdict (verdict summary)]
    (cond-> [(str "Report: " (command "alida-vector" "report" run_id))]
      (= "caution" final-verdict)
      (conj (str "Activate caution: " (command "alida-vector" "activate" run_id "--allow-caution")))

      (= "fail" final-verdict)
      (conj (str "Reject: " (command "alida-vector" "reject" run_id))))))

(defn- change-summary
  [summary]
  (format "+%s / -%s / ~%s / moved %s"
          (diff-count summary :added_count)
          (diff-count summary :removed_count)
          (diff-count summary :changed_count)
          (diff-count summary :moved_count)))

(defn- content-summary
  [document-count chunk-count error-count]
  (format "%s docs / %s chunks / %s errors"
          (value document-count)
          (value chunk-count)
          (value error-count)))

(defn- embedding-summary
  [embedding-stats]
  (format "%s reused / %s new / %s requests"
          (value (:reused_chunk_count embedding-stats))
          (value (:embedded_chunk_count embedding-stats))
          (value (:embedding_request_count embedding-stats))))

(defn- summary-fields
  [summary document-count chunk-count error-count embedding-stats]
  [(field "Content" (content-summary document-count chunk-count error-count))
   (field "Changes" (change-summary summary))
   (field "Embeddings" (embedding-summary embedding-stats))])

(def max-slack-change-entries 50)
(def max-slack-section-text-length 2800)
(def max-slack-url-length 110)

(defn- display-url
  [url]
  (slack-escape (truncate url max-slack-url-length)))

(defn- source-label
  [entry]
  (if-let [source-id (:source_id entry)]
    (str "`" (slack-escape source-id) "` ")
    ""))

(defn- slack-added-line
  [entry]
  (str "• ✅ " (source-label entry) (display-url (:canonical_url entry))))

(defn- slack-removed-line
  [entry]
  (str "• 🗑️ " (source-label entry) (display-url (:canonical_url entry))))

(defn- slack-changed-line
  [entry]
  (str "• ✏️ " (source-label entry) (display-url (:canonical_url entry))))

(defn- slack-moved-line
  [entry]
  (str "• 🔀 " (source-label entry)
       (display-url (:previous_canonical_url entry))
       " → "
       (display-url (:current_canonical_url entry))))

(defn- append-change-group
  [{:keys [remaining] :as state} {:keys [title entries line-fn]}]
  (if (or (not (seq entries)) (zero? remaining))
    (update state :hidden-count + (count entries))
    (let [visible (take remaining entries)
          hidden (- (count entries) (count visible))]
      (-> state
          (update :lines into (cons (str "*" title "*")
                                    (map line-fn visible)))
          (update :hidden-count + hidden)
          (update :remaining - (count visible))))))

(defn- change-groups
  [{:keys [diff]}]
  [{:title "Added"
    :entries (:added_urls diff)
    :line-fn slack-added-line}
   {:title "Removed"
    :entries (:removed_urls diff)
    :line-fn slack-removed-line}
   {:title "Changed"
    :entries (:changed_urls diff)
    :line-fn slack-changed-line}
   {:title "Moved"
    :entries (:moved_urls diff)
    :line-fn slack-moved-line}])

(defn- slack-change-lines
  [summary]
  (let [{:keys [lines hidden-count]}
        (reduce append-change-group
                {:lines ["*Actual changes*"]
                 :remaining max-slack-change-entries
                 :hidden-count 0}
                (change-groups summary))]
    (when (< 1 (count lines))
      (cond-> lines
        (pos? hidden-count)
        (conj (format "• … %s more in the full report" hidden-count))))))

(defn- append-line-to-text-block
  [blocks line]
  (let [current (peek blocks)
        candidate (if (seq current)
                    (str current "\n" line)
                    line)]
    (if (<= (count candidate) max-slack-section-text-length)
      (conj (pop blocks) candidate)
      (conj blocks line))))

(defn- slack-change-detail-text
  [summary]
  (when-let [lines (seq (slack-change-lines summary))]
    (reduce append-line-to-text-block [""] lines)))

(defn- slack-change-detail-blocks
  [summary]
  (mapv (fn [text]
          {:type "section"
           :text {:type "mrkdwn"
                  :text text}})
        (slack-change-detail-text summary)))

(defn slack-blocks
  [{:keys [run_id index_name lifecycle_status document_count chunk_count error_count
           embedding_stats phase_stats] :as summary}]
  (let [final-verdict (verdict summary)]
    (vec
     (mapcat (fn [block-or-blocks]
               (cond
                 (nil? block-or-blocks) []
                 (sequential? block-or-blocks) block-or-blocks
                 :else [block-or-blocks]))
             [{:type "header"
               :text {:type "plain_text"
                      :emoji true
                      :text (truncate (format "%s Alida Vector crawl %s"
                                              (verdict-emoji final-verdict)
                                              (verdict-label final-verdict))
                                      150)}}
              {:type "section"
               :fields [(field "Index" index_name)
                        (field "Run" (short-run-id run_id))
                        (field "Status" (or lifecycle_status "-"))
                        (field "Verdict" final-verdict)]}
              {:type "section"
               :fields (summary-fields summary document_count chunk_count error_count embedding_stats)}
              (slack-change-detail-blocks summary)
              {:type "section"
               :fields [(field "Crawl time" (str (ms phase_stats :crawl_duration_ms) " ms"))
                        (field "Embedding time" (str (ms phase_stats :embedding_duration_ms) " ms"))]}
              {:type "section"
               :text {:type "mrkdwn"
                      :text (str "*Action*\n"
                                 (slack-escape (action-line summary))
                                 "\n"
                                 (str/join "\n" (map #(str "- " %) (action-commands summary))))}}]))))

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

(defn- deterministic-finding-line
  [entry]
  (str "- " (:check entry) " " (:verdict entry) ": " (:message entry)))

(defn- finding-line
  [entry]
  (str "- " entry))

(defn full-report
  [{:keys [run_id index_name lifecycle_status source_count document_count chunk_count error_count
           embedding_stats phase_stats sources diff deterministic_verification verification]
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
             ["Deterministic Gate"
              (str "verdict: " (deterministic-verdict summary))])
            (section "Deterministic Findings"
                     (map deterministic-finding-line
                          (:deterministic_findings deterministic_verification)))
            (str/join
             \newline
             ["LLM Verification"
              (str "verdict: " (llm-verdict summary))
              (str "reasoning: " (or (:reasoning verification) "-"))])
            (section "LLM Security Findings"
                     (map finding-line (:llm_security_findings verification)))
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
   :slack_blocks (slack-blocks summary)
   :full_report (full-report summary)})
