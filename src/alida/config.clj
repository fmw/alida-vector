(ns alida.config
  (:require [alida.config.schema :as schema]
            [alida.env :as env]
            [alida.lang :as lang]
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

(def required-verification-keys
  {"openai" [:model :api_key]
   "azure-openai" [:endpoint :deployment_name :api_key]
   "vertex-ai" [:project :location :model]})

(defn- verification-enabled?
  [verification]
  (not= false (:enabled verification)))

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
                       :key k})))
    index))

(defn- validate-required-verification-keys!
  [config]
  (let [verification (:verification config)
        provider (:provider verification)
        required-keys (required-verification-keys (:provider verification))]
    (when (verification-enabled? verification)
      (when-not provider
        (throw (ex-info "Invalid verification config: provider is required when verification is enabled"
                        {:type :alida.config/missing-verification-provider-config
                         :key :provider})))
      (doseq [k required-keys
              :when (nil? (get verification k))]
        (throw (ex-info (str "Invalid verification config: provider "
                             (:provider verification)
                             " requires "
                             (name k))
                        {:type :alida.config/missing-verification-provider-config
                         :provider (:provider verification)
                         :key k})))))
  config)

(defn- validate-verification-options!
  [config]
  (let [max-prompt-tokens (get-in config [:verification :max_prompt_tokens])]
    (when (and (some? max-prompt-tokens) (not (pos-int? max-prompt-tokens)))
      (throw (ex-info "Invalid verification config: max_prompt_tokens must be positive"
                      {:type :alida.config/invalid-verification-provider-config
                       :key :max_prompt_tokens
                       :value max-prompt-tokens}))))
  config)

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
                       :value v})))
    (doseq [k [:retry_jitter_ms :inter_batch_delay_ms]
            :let [v (get embedding k)]
            :when (and (some? v) (not (nat-int? v)))]
      (throw (ex-info (str "Invalid embedding config for index " (:name index)
                           ": " (name k) " must be zero or positive")
                      {:type :alida.config/invalid-embedding-provider-config
                       :index (:name index)
                       :key k
                       :value v})))
    index))

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

(defn- validate-locales!
  [index path locales]
  (doseq [locale locales]
    (try
      (lang/require-supported-locale! locale)
      (catch clojure.lang.ExceptionInfo e
        (throw (ex-info (str "Invalid language config for index " (:name index)
                             ": unsupported locale " locale)
                        (assoc (ex-data e)
                               :type :alida.config/unsupported-language-locale
                               :index (:name index)
                               :path path)))))))

(defn- validate-fallback!
  [index path allowed fallback]
  (when fallback
    (let [normalized-fallback (lang/require-supported-locale! fallback)
          normalized-allowed (set (map lang/normalize-locale allowed))]
      (when (and (seq normalized-allowed)
                 (not (contains? normalized-allowed normalized-fallback)))
        (throw (ex-info (str "Invalid language config for index " (:name index)
                             ": fallback " fallback " is not in allowed locales")
                        {:type :alida.config/language-fallback-not-allowed
                         :index (:name index)
                         :path path
                         :fallback normalized-fallback
                         :allowed normalized-allowed}))))))

(defn- validate-source-allowed-subset!
  [index source index-allowed source-allowed]
  (let [normalized-index-allowed (set (map lang/normalize-locale index-allowed))
        normalized-source-allowed (set (map lang/normalize-locale source-allowed))]
    (when (and (seq normalized-index-allowed)
               (seq normalized-source-allowed)
               (not (every? normalized-index-allowed normalized-source-allowed)))
      (throw (ex-info (str "Invalid language config for index " (:name index)
                           ": source " (:id source)
                           " allows locales outside the index allowed locales")
                      {:type :alida.config/source-language-not-in-index-languages
                       :index (:name index)
                       :source (:id source)
                       :index-allowed normalized-index-allowed
                       :source-allowed normalized-source-allowed})))))

(defn- validate-configured-locale!
  [index source allowed locale]
  (let [normalized-locale (lang/require-supported-locale! locale)
        normalized-allowed (set (map lang/normalize-locale allowed))]
    (when (and (seq normalized-allowed)
               (not (contains? normalized-allowed normalized-locale)))
      (throw (ex-info (str "Invalid language config for index " (:name index)
                           ": source " (:id source)
                           " configured locale is not allowed")
                      {:type :alida.config/configured-language-not-allowed
                       :index (:name index)
                       :source (:id source)
                       :locale normalized-locale
                       :allowed normalized-allowed})))))

(defn- validate-language-config!
  [index]
  (let [index-languages (:languages index)]
    (validate-locales! index [:languages :allowed] (:allowed index-languages))
    (validate-fallback! index [:languages :fallback] (:allowed index-languages) (:fallback index-languages))
    (doseq [source (:sources index)
            :let [source-language (:language source)]]
      (validate-locales! index [:sources (:id source) :language :allowed] (:allowed source-language))
      (validate-source-allowed-subset! index source (:allowed index-languages) (:allowed source-language))
      (validate-fallback! index
                          [:sources (:id source) :language :fallback]
                          (or (:allowed source-language) (:allowed index-languages))
                          (:fallback source-language))
      (when (= "configured" (:mode source-language))
        (when-not (:locale source-language)
          (throw (ex-info (str "Invalid language config for index " (:name index)
                               ": source " (:id source)
                               " uses configured language mode without locale")
                          {:type :alida.config/missing-configured-language-locale
                           :index (:name index)
                           :source (:id source)})))
        (validate-locales! index [:sources (:id source) :language :locale] [(:locale source-language)])
        (validate-configured-locale! index
                                     source
                                     (or (:allowed source-language) (:allowed index-languages))
                                     (:locale source-language)))))
  index)

(defn- validate-source-concurrency!
  [index]
  (doseq [source (:sources index)
          :let [max-concurrency (:max_concurrency source)]
          :when (and (some? max-concurrency) (not (pos-int? max-concurrency)))]
    (throw (ex-info (str "Invalid source config for index " (:name index)
                         ": source " (:id source)
                         " max_concurrency must be positive")
                    {:type :alida.config/invalid-source-concurrency
                     :index (:name index)
                     :source (:id source)
                     :value max-concurrency})))
  index)

(defn- validate-source-delay!
  [index]
  (doseq [source (:sources index)
          :let [delay-ms (:inter_request_delay_ms source)]
          :when (and (some? delay-ms) (neg-int? delay-ms))]
    (throw (ex-info (str "Invalid source config for index " (:name index)
                         ": source " (:id source)
                         " inter_request_delay_ms must be zero or positive")
                    {:type :alida.config/invalid-source-delay
                     :index (:name index)
                     :source (:id source)
                     :value delay-ms})))
  index)

(defn- validate-source-sitemap-depth!
  [index]
  (doseq [source (:sources index)
          :let [max-depth (:max_sitemap_depth source)]
          :when (and (some? max-depth) (not (pos-int? max-depth)))]
    (throw (ex-info (str "Invalid source config for index " (:name index)
                         ": source " (:id source)
                         " max_sitemap_depth must be positive")
                    {:type :alida.config/invalid-source-sitemap-depth
                     :index (:name index)
                     :source (:id source)
                     :value max-depth})))
  index)

(defn- validate-source-browser-restart!
  [index]
  (doseq [source (:sources index)
          k [:browser_restart_after_pages
             :browser_restart_after_failures
             :progress_log_every_pages]
          :let [value (get source k)]
          :when (and (some? value) (neg-int? value))]
    (throw (ex-info (str "Invalid source config for index " (:name index)
                         ": source " (:id source)
                         " " (name k) " must be zero or positive")
                    {:type :alida.config/invalid-source-browser-restart
                     :index (:name index)
                     :source (:id source)
                     :key k
                     :value value})))
  index)

(defn- validate-deterministic-thresholds!
  [config]
  (let [thresholds (get-in config [:verification :deterministic_thresholds])]
    (doseq [k [:max_removed_percentage
              :max_changed_percentage
              :max_item_failure_percentage
              :max_empty_or_short_document_percentage]
            :let [v (get thresholds k)]
            :when (and (some? v) (not (<= 0.0 v 1.0)))]
      (throw (ex-info (str "Invalid deterministic threshold " (name k)
                           ": percentage thresholds must be fractions between 0.0 and 1.0")
                      {:type :alida.config/invalid-deterministic-threshold
                       :key k
                       :value v})))
    (when-let [v (:max_removed_absolute thresholds)]
      (when (neg-int? v)
        (throw (ex-info "Invalid deterministic threshold max_removed_absolute: must be zero or greater"
                        {:type :alida.config/invalid-deterministic-threshold
                         :key :max_removed_absolute
                         :value v})))))
  config)

(defn- validate-indexes!
  [config]
  (doseq [index (:indexes config)]
    (validate-required-embedding-keys! index)
    (validate-positive-embedding-options! index)
    (validate-language-config! index)
    (validate-source-concurrency! index)
    (validate-source-delay! index)
    (validate-source-sitemap-depth! index)
    (validate-source-browser-restart! index)
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
                   validate-deterministic-thresholds!
                   validate-required-verification-keys!
                   validate-verification-options!
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
