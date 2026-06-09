(ns alida.db.postgres
  (:require [clojure.data.json :as json]
            [migratus.core :as migratus]
            [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs])
  (:import [com.zaxxer.hikari HikariConfig HikariDataSource]
           [java.sql Connection]
           [java.nio ByteBuffer]
           [java.security MessageDigest]
           [org.postgresql.util PGobject]))

(def lifecycle-statuses
  #{"activated" "complete" "crawling" "created" "embedding" "error" "rejected" "superseded" "verifying"})

(def verification-verdicts
  #{"caution" "fail" "pass"})

(def non-terminal-statuses
  #{"created" "crawling" "embedding" "verifying"})

(def default-stale-run-timeout-minutes 360)

(def jdbc-opts
  {:builder-fn rs/as-unqualified-lower-maps})

(defn- jsonb
  [value]
  (doto (PGobject.)
    (.setType "jsonb")
    (.setValue (if (string? value) value (json/write-str value)))))

(defn- require-lifecycle-status!
  [status]
  (when-not (contains? lifecycle-statuses status)
    (throw (ex-info (str "Invalid lifecycle status: " status)
                    {:status status
                     :valid lifecycle-statuses})))
  status)

(defn- require-verdict!
  [verdict]
  (when-not (contains? verification-verdicts verdict)
    (throw (ex-info (str "Invalid verification verdict: " verdict)
                    {:verdict verdict
                     :valid verification-verdicts})))
  verdict)

(defn- run-id
  [value]
  (cond
    (uuid? value) value
    (some? value) (java.util.UUID/fromString (str value))
    :else nil))

(defn- connection?
  [value]
  (instance? Connection value))

(defn- with-connection
  [connectable f]
  (if (connection? connectable)
    (f connectable)
    (with-open [conn (jdbc/get-connection connectable)]
      (f conn))))

(defn- require-connection!
  [connectable]
  (when-not (connection? connectable)
    (throw (ex-info "Session-level advisory locks require a checked-out JDBC Connection"
                    {:type :alida.db.postgres/connection-required})))
  connectable)

(defn advisory-lock-key
  "Return a stable signed 64-bit PostgreSQL advisory lock key for an index name."
  [index-name]
  (let [digest (.digest (MessageDigest/getInstance "SHA-256")
                        (.getBytes (str "alida:index:" index-name) "UTF-8"))]
    (.getLong (ByteBuffer/wrap digest))))

(defn datasource
  [{:keys [jdbc_url user username password]}]
  (let [cfg (HikariConfig.)]
    (.setJdbcUrl cfg jdbc_url)
    (when (or username user)
      (.setUsername cfg (or username user)))
    (when password
      (.setPassword cfg password))
    (.setMaximumPoolSize cfg 5)
    (.setPoolName cfg "alida-vector")
    (HikariDataSource. cfg)))

(defn migratus-config
  [ds]
  {:store :database
   :migration-dir "migrations"
   :db {:datasource ds}})

(defn migrate!
  [config]
  (with-open [ds (datasource (:database config))]
    (migratus/migrate (migratus-config ds))))

(defn rollback-migration!
  [config]
  (with-open [ds (datasource (:database config))]
    (migratus/rollback (migratus-config ds))))

(defn record-event!
  ([connectable event]
   (record-event! connectable event jdbc-opts))
  ([connectable {:keys [run_id index_name event_type actor details]} opts]
   (jdbc/execute-one!
    connectable
    ["INSERT INTO alida_events (run_id, index_name, event_type, actor, details)
      VALUES (?, ?, ?, ?, ?)
      RETURNING *"
     (run-id run_id)
     index_name
     event_type
     (or actor "alida-vector")
     (jsonb (or details {}))]
    opts)))

(defn ensure-index!
  [connectable {:keys [name embedding]}]
  (jdbc/execute-one!
   connectable
   ["INSERT INTO alida_indexes (name, embedding_dimensions)
     VALUES (?, ?)
     ON CONFLICT (name) DO UPDATE
     SET embedding_dimensions = EXCLUDED.embedding_dimensions,
         updated_at = now()
     RETURNING *"
    name
    (:embedding_dimensions embedding)]
   jdbc-opts))

(defn create-run!
  [connectable index-cfg structural-config-hash]
  (jdbc/with-transaction [tx connectable]
    (ensure-index! tx index-cfg)
    (let [run (jdbc/execute-one!
               tx
               ["INSERT INTO alida_runs
                 (index_name, lifecycle_status, embedding_dimensions, structural_config_hash)
                 VALUES (?, ?, ?, ?)
                 RETURNING *"
                (:name index-cfg)
                "created"
                (get-in index-cfg [:embedding :embedding_dimensions])
                structural-config-hash]
               jdbc-opts)]
      (record-event! tx {:run_id (:id run)
                         :index_name (:index_name run)
                         :event_type "run-created"
                         :details {:lifecycle_status (:lifecycle_status run)}})
      run)))

(defn get-run
  [connectable value]
  (jdbc/execute-one!
   connectable
   ["SELECT * FROM alida_runs WHERE id = ?" (run-id value)]
   jdbc-opts))

(defn update-run-status!
  ([connectable value lifecycle-status]
   (update-run-status! connectable value lifecycle-status nil))
  ([connectable value lifecycle-status {:keys [error_summary verification_verdict]}]
   (require-lifecycle-status! lifecycle-status)
   (when verification_verdict
     (require-verdict! verification_verdict))
   (jdbc/with-transaction [tx connectable]
     (let [run (jdbc/execute-one!
                tx
                ["UPDATE alida_runs
                  SET lifecycle_status = ?,
                      verification_verdict = COALESCE(?, verification_verdict),
                      error_summary = COALESCE(?, error_summary),
                      finished_at = CASE
                        WHEN ? IN ('complete', 'error') AND finished_at IS NULL THEN now()
                        ELSE finished_at
                      END,
                      activated_at = CASE
                        WHEN ? = 'activated' THEN now()
                        ELSE activated_at
                      END,
                      rejected_at = CASE
                        WHEN ? = 'rejected' THEN now()
                        ELSE rejected_at
                      END
                  WHERE id = ?
                  RETURNING *"
                 lifecycle-status
                 verification_verdict
                 error_summary
                 lifecycle-status
                 lifecycle-status
                 lifecycle-status
                 (run-id value)]
                jdbc-opts)]
       (when-not run
         (throw (ex-info (str "Unknown run: " value) {:run-id value})))
       (record-event! tx {:run_id (:id run)
                          :index_name (:index_name run)
                          :event_type "run-status-updated"
                          :details {:lifecycle_status lifecycle-status
                                    :verification_verdict verification_verdict
                                    :error_summary error_summary}})
       run))))

(defn- require-activatable-run!
  [run]
  (when-not (= "complete" (:lifecycle_status run))
    (throw (ex-info (str "Run is not activatable: " (:id run))
                    {:type :alida.db.postgres/run-not-activatable
                     :run-id (:id run)
                     :lifecycle-status (:lifecycle_status run)
                     :verification-verdict (:verification_verdict run)
                     :reason :not-complete})))
  (when-not (= "pass" (:verification_verdict run))
    (throw (ex-info (str "Run is not activatable: " (:id run))
                    {:type :alida.db.postgres/run-not-activatable
                     :run-id (:id run)
                     :lifecycle-status (:lifecycle_status run)
                     :verification-verdict (:verification_verdict run)
                     :reason :verification-not-pass})))
  run)

(defn- run-index-pointer
  [tx value]
  (jdbc/execute-one!
   tx
   ["SELECT name, live_run_id, previous_live_run_id
     FROM alida_indexes
     WHERE live_run_id = ? OR previous_live_run_id = ?
     FOR UPDATE"
    (run-id value)
    (run-id value)]
   jdbc-opts))

(defn list-runs
  ([connectable] (list-runs connectable {}))
  ([connectable {:keys [index_name limit]}]
   (let [limit (or limit 50)]
     (jdbc/execute!
      connectable
      (if index_name
        ["SELECT id, index_name, lifecycle_status, verification_verdict,
                 embedding_dimensions, started_at, finished_at, activated_at, rejected_at
          FROM alida_runs
          WHERE index_name = ?
          ORDER BY started_at DESC
          LIMIT ?"
         index_name
         limit]
        ["SELECT id, index_name, lifecycle_status, verification_verdict,
                 embedding_dimensions, started_at, finished_at, activated_at, rejected_at
          FROM alida_runs
          ORDER BY started_at DESC
          LIMIT ?"
         limit])
      jdbc-opts))))

(defn activate-run!
  [connectable value]
  (jdbc/with-transaction [tx connectable]
    (let [run (get-run tx value)]
      (when-not run
        (throw (ex-info (str "Unknown run: " value) {:run-id value})))
      (require-activatable-run! run)
      (let [index-row (jdbc/execute-one!
                       tx
                       ["SELECT * FROM alida_indexes WHERE name = ? FOR UPDATE" (:index_name run)]
                       jdbc-opts)
            previous-live-id (:live_run_id index-row)]
        (jdbc/execute-one!
         tx
         ["UPDATE alida_indexes
           SET previous_live_run_id = live_run_id,
               live_run_id = ?,
               updated_at = now()
           WHERE name = ?
           RETURNING *"
          (:id run)
          (:index_name run)]
         jdbc-opts)
        (when previous-live-id
          (update-run-status! tx previous-live-id "superseded"))
        (let [activated (update-run-status! tx (:id run) "activated")]
          (record-event! tx {:run_id (:id run)
                             :index_name (:index_name run)
                             :event_type "run-activated"
                             :details {:previous_live_run_id previous-live-id}})
          activated)))))

(defn reject-run!
  [connectable value]
  (jdbc/with-transaction [tx connectable]
    (let [run (get-run tx value)]
      (when-not run
        (throw (ex-info (str "Unknown run: " value) {:run-id value})))
      (when-let [index-row (run-index-pointer tx value)]
        (throw (ex-info (str "Cannot reject run currently referenced by index: " value)
                        {:type :alida.db.postgres/run-is-index-pointer
                         :run-id (:id run)
                         :index-name (:name index-row)
                         :pointer (cond
                                    (= (:id run) (:live_run_id index-row)) :live-run
                                    (= (:id run) (:previous_live_run_id index-row)) :previous-live-run)})))
      (update-run-status! tx value "rejected"))))

(defn rollback-index!
  [connectable index-name]
  (jdbc/with-transaction [tx connectable]
    (let [index-row (jdbc/execute-one!
                     tx
                     ["SELECT * FROM alida_indexes WHERE name = ? FOR UPDATE" index-name]
                     jdbc-opts)]
      (when-not index-row
        (throw (ex-info (str "Unknown index: " index-name) {:index-name index-name})))
      (when-not (:previous_live_run_id index-row)
        (throw (ex-info (str "Index has no previous live run: " index-name)
                        {:index-name index-name})))
      (jdbc/execute-one!
       tx
       ["UPDATE alida_indexes
         SET live_run_id = previous_live_run_id,
             previous_live_run_id = live_run_id,
             updated_at = now()
         WHERE name = ?
         RETURNING *"
        index-name]
       jdbc-opts)
      (update-run-status! tx (:previous_live_run_id index-row) "activated")
      (when (:live_run_id index-row)
        (update-run-status! tx (:live_run_id index-row) "superseded"))
      (record-event! tx {:run_id (:previous_live_run_id index-row)
                         :index_name index-name
                         :event_type "index-rolled-back"
                         :details {:old_live_run_id (:live_run_id index-row)
                                   :new_live_run_id (:previous_live_run_id index-row)}}))))

(defn try-index-lock!
  [connectable index-name]
  (:acquired
   (jdbc/execute-one!
    (require-connection! connectable)
    ["SELECT pg_try_advisory_lock(?) AS acquired" (advisory-lock-key index-name)]
    jdbc-opts)))

(defn unlock-index!
  [connectable index-name]
  (:released
   (jdbc/execute-one!
    (require-connection! connectable)
    ["SELECT pg_advisory_unlock(?) AS released" (advisory-lock-key index-name)]
    jdbc-opts)))

(defn with-index-lock!
  [connectable index-name f]
  (with-connection connectable
    (fn [conn]
      (if (try-index-lock! conn index-name)
        (try
          (f)
          (finally
            (unlock-index! conn index-name)))
        (throw (ex-info (str "Index is already locked: " index-name)
                        {:type :alida.db.postgres/index-locked
                         :index-name index-name
                         :lock-key (advisory-lock-key index-name)}))))))

(defn reconcile-orphaned-runs!
  ([connectable] (reconcile-orphaned-runs! connectable {}))
  ([connectable {:keys [stale-after-minutes]}]
   (let [stale-after-minutes (or stale-after-minutes default-stale-run-timeout-minutes)
         candidates (jdbc/execute!
                     connectable
                     ["SELECT *
                       FROM alida_runs
                       WHERE lifecycle_status IN ('created', 'crawling', 'embedding', 'verifying')
                         AND started_at < now() - (? * interval '1 minute')
                       ORDER BY started_at"
                      stale-after-minutes]
                     jdbc-opts)]
     (reduce
      (fn [reconciled run]
        (try
          (with-index-lock!
            connectable
            (:index_name run)
            #(conj reconciled
                   (update-run-status!
                    connectable
                    (:id run)
                    "error"
                    {:error_summary "Marked as orphaned after startup reconciliation"})))
          (catch Exception e
            (if (= :alida.db.postgres/index-locked (:type (ex-data e)))
              reconciled
              (throw e)))))
      []
      candidates))))
