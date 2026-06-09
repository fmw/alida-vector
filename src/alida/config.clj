(ns alida.config
  (:require [alida.config.schema :as schema]
            [alida.env :as env]
            [alida.vector.pgvector :as pgvector]
            [clj-yaml.core :as yaml]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [malli.core :as m]
            [malli.error :as me])
  (:import [java.math BigInteger]
           [java.security MessageDigest]))

(defn- sha-256
  [s]
  (let [digest (.digest (MessageDigest/getInstance "SHA-256")
                        (.getBytes s "UTF-8"))]
    (format "%064x" (BigInteger. 1 digest))))

(defn- parse-yaml-file
  [path]
  (with-open [reader (io/reader path)]
    (yaml/parse-stream reader :keywords true)))

(defn- explain-message
  [config]
  (->> (m/explain schema/Config config)
       me/humanize
       env/redact
       pr-str
       (str "Invalid Alida config: ")))

(defn- validate-schema!
  [config]
  (when-not (m/validate schema/Config config)
    (throw (ex-info (explain-message config)
                    {:type :alida.config/invalid
                     :errors (env/redact (me/humanize (m/explain schema/Config config)))})))
  config)

(defn- normalize-storage
  [config]
  (if (:database config)
    config
    (if-let [metadata (get-in config [:storage :metadata])]
      (assoc config :database (dissoc metadata :type))
      config)))

(defn- validate-storage!
  [config]
  (when-not (or (:database config) (get-in config [:storage :metadata]))
    (throw (ex-info "Invalid Alida config: configure storage.metadata or database"
                    {:type :alida.config/missing-storage})))
  (when-let [vectors (get-in config [:storage :vectors])]
    (when-not (= "pgvector" (:type vectors))
      (throw (ex-info (str "Unsupported vector storage type: " (:type vectors))
                      {:type :alida.config/unsupported-vector-storage
                       :vector-storage-type (:type vectors)}))))
  config)

(defn- vector-storage-type
  [config]
  (or (get-in config [:storage :vectors :type])
      "pgvector"))

(defn- validate-vector-dimensions!
  [config]
  (when (= "pgvector" (vector-storage-type config))
    (doseq [index (:indexes config)
            :let [dimensions (get-in index [:embedding :embedding_dimensions])]
            :when (not (pgvector/supported-dimension? dimensions))]
      (throw (ex-info (str "Unsupported pgvector dimensions for index " (:name index)
                           ": " dimensions
                           ". Supported dimensions: "
                           (str/join ", " (sort pgvector/supported-dimensions)))
                      {:type :alida.config/unsupported-pgvector-dimensions
                       :index (:name index)
                       :embedding-dimensions dimensions
                       :supported-dimensions pgvector/supported-dimensions}))))
  config)

(def required-embedding-keys
  {"openai" [:model :api_key]
   "azure-openai" [:endpoint :deployment_name :api_key]
   "vertex-ai" [:project :location :model]})

(defn- validate-required-embedding-keys!
  [index]
  (let [embedding (:embedding index)
        required-keys (required-embedding-keys (:provider embedding))]
    (doseq [k required-keys
            :when (nil? (get embedding k))]
      (throw (ex-info (str "Invalid embedding config for index " (:name index)
                           ": provider " (:provider embedding)
                           " requires " (name k))
                      {:type :alida.config/missing-embedding-provider-config
                       :index (:name index)
                       :provider (:provider embedding)
                       :key k}))))
  index)

(defn- validate-positive-embedding-options!
  [index]
  (let [embedding (:embedding index)]
    (doseq [k [:max_batch_size :max_retries :retry_initial_ms]
            :let [v (get embedding k)]
            :when (and (some? v) (not (pos-int? v)))]
      (throw (ex-info (str "Invalid embedding config for index " (:name index)
                           ": " (name k) " must be positive")
                      {:type :alida.config/invalid-embedding-provider-config
                       :index (:name index)
                       :key k
                       :value v}))))
  index)

(defn- validate-chunking!
  [index]
  (let [{:keys [max_input_tokens max_tokens safety_multiplier]} (:chunking index)
        effective-limit (* max_tokens safety_multiplier)]
    (when (> effective-limit max_input_tokens)
      (throw (ex-info (str "Invalid chunking config for index " (:name index)
                           ": max_tokens * safety_multiplier must be <= max_input_tokens")
                      {:type :alida.config/invalid-chunking
                       :index (:name index)
                       :max_input_tokens max_input_tokens
                       :max_tokens max_tokens
                       :safety_multiplier safety_multiplier}))))
  index)

(defn- validate-indexes!
  [config]
  (doseq [index (:indexes config)]
    (validate-required-embedding-keys! index)
    (validate-positive-embedding-options! index)
    (validate-chunking! index))
  config)

(defn structural-config
  "Return the config shape used for hashing. Resolved secret values are never included."
  [raw-config]
  (env/redact raw-config))

(defn structural-config-hash
  [raw-config]
  (sha-256 (pr-str (structural-config raw-config))))

(defn load-config
  [path]
  (let [raw (parse-yaml-file path)
        config (-> raw
                   env/interpolate
                   normalize-storage
                   validate-schema!
                   validate-storage!
                   validate-vector-dimensions!
                   validate-indexes!)]
    (assoc config
           :alida.config/path (str path)
           :alida.config/structural-hash (structural-config-hash raw))))

(defn selected-indexes
  [config index-name]
  (let [indexes (:indexes config)]
    (if (str/blank? index-name)
      indexes
      (let [matches (filter #(= index-name (:name %)) indexes)]
        (when-not (seq matches)
          (throw (ex-info (str "Unknown index: " index-name)
                          {:type :alida.config/unknown-index
                           :index index-name})))
        matches))))
