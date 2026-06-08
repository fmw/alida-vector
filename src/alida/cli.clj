(ns alida.cli
  (:require [alida.db :as db]
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

(defmethod execute "report"
  [command sys options arguments]
  (require-arg arguments "run-id")
  (not-implemented command sys options arguments))

(defmethod execute "activate"
  [command sys options arguments]
  (require-arg arguments "run-id")
  (not-implemented command sys options arguments))

(defmethod execute "reject"
  [command sys options arguments]
  (require-arg arguments "run-id")
  (not-implemented command sys options arguments))

(defmethod execute "rollback"
  [command sys options arguments]
  (require-arg arguments "index-name")
  (not-implemented command sys options arguments))

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
