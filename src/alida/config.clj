(ns alida.config
  (:require [alida.config.schema :as schema]
            [alida.env :as env]
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
  (run! validate-chunking! (:indexes config))
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
                   validate-schema!
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
