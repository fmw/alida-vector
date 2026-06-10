(ns alida.cli
  (:require [alida.crawl :as crawl]
            [alida.db.postgres :as db]
            [alida.system :as system]
            [clojure.string :as str]
            [clojure.tools.cli :as tools.cli]
            [integrant.core :as ig]))

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

(def option-specs
  [["-c" "--config PATH" "YAML config file"]
   ["-i" "--index NAME" "Limit command to one index"]
   ["-n" "--limit N" "Maximum rows to return for list commands"
    :parse-fn parse-long]
   [nil "--json" "Print machine-readable JSON when supported"]
   [nil "--keep-last N" "For prune: keep the last N runs per index"
    :parse-fn parse-long]
   [nil "--older-than DURATION" "For prune: prune runs older than this duration"]
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
    "  activate <run-id>       Make a verified run live"
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
          verification_verdict))

(defn- format-failed-index
  [{:keys [index_name message]}]
  (format "%s  failed: %s" index_name message))

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
  [command sys options arguments]
  (require-arg arguments "run-id")
  (not-implemented command sys options arguments))

(defmethod execute "activate"
  [_ sys _options arguments]
  (let [run-id (require-arg arguments "run-id")
        run (with-datasource sys #(db/activate-run! % run-id))]
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

(defmethod execute "search"
  [command sys options arguments]
  (require-arg arguments "query")
  (not-implemented command sys options arguments))

(defmethod execute "search-run"
  [command sys options arguments]
  (require-arg arguments "run-id")
  (require-arg (rest arguments) "query")
  (not-implemented command sys options arguments))

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
