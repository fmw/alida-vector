(ns alida.cli
  (:require [alida.crawl :as crawl]
            [alida.db.postgres :as db]
            [alida.search :as search]
            [alida.system :as system]
            [clojure.string :as str]
            [clojure.tools.cli :as tools.cli]
            [integrant.core :as ig])
  (:import [java.time Duration Instant]))

(def command-names
  #{"activate"
    "crawl"
    "help"
    "migrate"
    "prune"
    "reject"
    "report"
    "rollback"
    "runs"
    "search"
    "search-run"})

(defn- parse-duration
  [value]
  (let [value (str/trim value)]
    (or
     (try
       (Duration/parse value)
       (catch Exception _ nil))
     (when-let [[_ amount unit] (re-matches #"(?i)^(\d+)\s*(d|day|days|h|hour|hours|m|min|minute|minutes|s|sec|second|seconds)$" value)]
       (let [amount (parse-long amount)]
         (case (str/lower-case unit)
           ("d" "day" "days") (Duration/ofDays amount)
           ("h" "hour" "hours") (Duration/ofHours amount)
           ("m" "min" "minute" "minutes") (Duration/ofMinutes amount)
           ("s" "sec" "second" "seconds") (Duration/ofSeconds amount))))
     (throw (ex-info (str "Invalid duration: " value)
                     {:type :alida.cli/invalid-duration
                      :value value})))))

(def option-specs
  [["-c" "--config PATH" "YAML config file"]
   ["-i" "--index NAME" "Limit command to one index"]
   ["-n" "--limit N" "Maximum rows to return for list commands"
    :parse-fn parse-long]
   [nil "--json" "Print machine-readable JSON when supported"]
   [nil "--allow-caution" "For activate: allow activating a verified caution run"]
   [nil "--disabled-embeddings" "For prune: prune runs created with disabled/noop embeddings"]
   [nil "--keep-last N" "For prune: keep the last N runs per index"
    :parse-fn parse-long]
   [nil "--older-than DURATION" "For prune: prune runs older than this duration"
    :parse-fn parse-duration]
   ["-h" "--help"]])

(defn usage
  []
  (str/join
   \newline
   ["Usage:"
    "  alida-vector <command> [options] [arguments]"
    ""
    "Commands:"
    "  migrate                 Run schema migrations"
    "  crawl                   Crawl configured indexes"
    "  runs                    List runs"
    "  report <run-id>         Print the stored report for a run"
    "  activate <run-id>       Make a verified pass run live"
    "  reject <run-id>         Mark a run as rejected"
    "  rollback <index-name>   Restore the previous live run for an index"
    "  prune                   Manually prune old non-live run data"
    "  search <query>          Search current live indexes"
    "  search-run <run-id> <query>"
    ""
    "Options:"
    (:summary (tools.cli/parse-opts [] option-specs))]))

(defn- parse
  [argv]
  (let [[command & args] argv
        {:keys [options arguments errors]} (tools.cli/parse-opts args option-specs)]
    (cond
      (or (:help options) (nil? command) (= "help" command))
      {:command "help" :options options :arguments arguments}

      (not (contains? command-names command))
      {:errors [(str "Unknown command: " command)]}

      (seq errors)
      {:errors errors}

      :else
      {:command command :options options :arguments arguments})))

(defn- requires-config?
  [command]
  (not (#{"help"} command)))

(defn- require-arg
  [arguments description]
  (or (first arguments)
      (throw (ex-info (str "Missing required argument: " description)
                      {:description description}))))

(defn- not-implemented
  [command _sys _options _arguments]
  {:exit-code 2
   :message (str "Command '" command "' is wired but not implemented yet.")})

(defn- with-datasource
  [sys f]
  (with-open [ds (db/datasource (:database (:alida/config sys)))]
    (f ds)))

(defn- format-run
  [run]
  (format "%s  %-28s  %-10s  %-7s  %s"
          (:id run)
          (:index_name run)
          (:lifecycle_status run)
          (or (:verification_verdict run) "-")
          (:started_at run)))

(defn- format-runs
  [runs]
  (if (seq runs)
    (str/join \newline (map format-run runs))
    "No runs found."))

(defn- format-crawled-run
  [{:keys [run_id index_name document_count chunk_count error_count verification_verdict embedding_stats phase_stats]}]
  (format "%s  %s  documents=%s  chunks=%s  crawl_ms=%s  fetch_ms=%s  extract_ms=%s  chunk_ms=%s  reused=%s  embedded=%s  requests=%s  reuse_ms=%s  provider_ms=%s  errors=%s  verdict=%s"
          run_id
          index_name
          document_count
          chunk_count
          (or (:crawl_duration_ms phase_stats) 0)
          (or (:fetch_duration_ms phase_stats) 0)
          (or (:extract_duration_ms phase_stats) 0)
          (or (:chunk_duration_ms phase_stats) 0)
          (or (:reused_chunk_count embedding_stats) 0)
          (or (:embedded_chunk_count embedding_stats) 0)
          (or (:embedding_request_count embedding_stats) 0)
          (or (:reuse_lookup_duration_ms embedding_stats) 0)
          (or (:provider_duration_ms embedding_stats) 0)
          error_count
          (or verification_verdict "-")))

(defn- format-failed-index
  [{:keys [index_name message]}]
  (format "%s  failed: %s" index_name message))

(defn- query-string
  [arguments]
  (str/join " " arguments))

(defn- format-search-row
  [{:keys [score index_name source_id canonical_url title locale content]}]
  (format "%.4f  %-28s  %-16s  %-5s  %s%s\n%s"
          (double (or score 0.0))
          index_name
          source_id
          (or locale "-")
          canonical_url
          (if (str/blank? title) "" (str "  " title))
          (str/replace (or content "") #"\s+" " ")))

(defn- format-search-results
  [rows]
  (if (seq rows)
    (str/join "\n\n" (map format-search-row rows))
    "No results found."))

(defn- older-than-cutoff
  [^Duration duration]
  (when duration
    (.minus (Instant/now) duration)))

(defn- format-pruned-run
  [{:keys [id index_name lifecycle_status partition]}]
  (format "%s  %-28s  %-10s  %s"
          id
          index_name
          lifecycle_status
          partition))

(defn- format-prune-result
  [{:keys [pruned]}]
  (if (seq pruned)
    (str/join
     \newline
     (cons (format "Pruned %s runs." (count pruned))
           (map format-pruned-run pruned)))
    "Pruned 0 runs."))

(defn- format-crawl-result
  [{:keys [succeeded failed]}]
  (str/join
   \newline
   (concat
    [(format "Crawl finished: %s succeeded, %s failed."
             (count succeeded)
             (count failed))]
    (map format-crawled-run succeeded)
    (map format-failed-index failed))))

(defmulti execute
  (fn [command _sys _options _arguments] command))

(defmethod execute "help"
  [_ _ _ _]
  {:exit-code 0 :message (usage)})

(defmethod execute "migrate"
  [_ sys _options _arguments]
  (db/migrate! (:alida/config sys))
  {:exit-code 0
   :message "Migrations complete."})

(defmethod execute "crawl"
  [_ sys options _arguments]
  (let [result (with-datasource
                 sys
                 #(crawl/crawl! sys % {:index-name (:index options)}))]
    {:exit-code (if (seq (:failed result)) 1 0)
     :message (format-crawl-result result)}))

(defmethod execute "runs"
  [_ sys options _arguments]
  (let [runs (with-datasource
               sys
               #(db/list-runs % {:index_name (:index options)
                                 :limit (:limit options)}))]
    {:exit-code 0
     :message (format-runs runs)}))

(defmethod execute "report"
  [_ sys _options arguments]
  (let [run-id (require-arg arguments "run-id")
        report (with-datasource sys #(db/get-report % run-id))]
    (if report
      {:exit-code 0
       :message (:full_report report)}
      {:exit-code 1
       :message (str "No report found for run " run-id ".")})))

(defmethod execute "activate"
  [_ sys options arguments]
  (let [run-id (require-arg arguments "run-id")
        run (with-datasource sys #(db/activate-run! %
                                                    run-id
                                                    {:allow-caution? (:allow-caution options)}))]
    {:exit-code 0
     :message (str "Activated run " (:id run) ".")}))

(defmethod execute "reject"
  [_ sys _options arguments]
  (let [run-id (require-arg arguments "run-id")
        run (with-datasource sys #(db/reject-run! % run-id))]
    {:exit-code 0
     :message (str "Rejected run " (:id run) ".")}))

(defmethod execute "rollback"
  [_ sys _options arguments]
  (let [index-name (require-arg arguments "index-name")]
    (with-datasource sys #(db/rollback-index! % index-name))
    {:exit-code 0
     :message (str "Rolled back index " index-name ".")}))

(defmethod execute "prune"
  [_ sys options _arguments]
  (let [result (with-datasource
                 sys
                 #(db/prune-runs! %
                                  {:keep-last (:keep-last options)
                                   :older-than (older-than-cutoff (:older-than options))
                                   :disabled-embeddings (:disabled-embeddings options)}))]
    {:exit-code 0
     :message (format-prune-result result)}))

(defmethod execute "search"
  [_ sys options arguments]
  (require-arg arguments "query")
  (let [query (query-string arguments)
        results (with-datasource
                  sys
                  #(search/search-live sys
                                       %
                                       query
                                       {:index-name (:index options)
                                        :limit (:limit options)}))]
    {:exit-code 0
     :message (format-search-results results)}))

(defmethod execute "search-run"
  [_ sys options arguments]
  (let [run-id (require-arg arguments "run-id")
        query-args (rest arguments)]
    (require-arg query-args "query")
    (let [query (query-string query-args)
          results (with-datasource
                    sys
                    #(search/search-run sys
                                        %
                                        run-id
                                        query
                                        {:limit (:limit options)}))]
      {:exit-code 0
       :message (format-search-results results)})))

(defmethod execute :default
  [command sys options arguments]
  (not-implemented command sys options arguments))

(defn run
  [argv]
  (try
    (let [{:keys [command options arguments errors]} (parse argv)]
      (cond
        (seq errors)
        {:exit-code 2
         :message (str (str/join \newline errors) "\n\n" (usage))}

        (requires-config? command)
        (let [config-path (or (:config options) "alida.yml")
              ig-config (system/system-config config-path)]
          (try
            (let [sys (ig/init ig-config)]
              (try
                (execute command sys options arguments)
                (finally
                  (ig/halt! sys))))
            (catch Exception e
              {:exit-code 1
               :message (or (ex-message e) (str e))})))

        :else
        (execute command nil options arguments)))
    (catch Exception e
      {:exit-code 1
       :message (or (ex-message e) (str e))})))
