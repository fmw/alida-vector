(ns alida.env
  (:require [clojure.string :as str]))

(def env-reference-pattern #"\$\{([A-Za-z_][A-Za-z0-9_]*)\}")

(def secret-key-fragments
  ["api-key"
   "apikey"
   "authorization"
   "credentials"
   "password"
   "secret"
   "token"
   "webhook"])

(defn interpolate-string
  [s]
  (str/replace
   s
   env-reference-pattern
   (fn [[_ var-name]]
     (or (System/getenv var-name)
         (throw (ex-info (str "Environment variable " var-name " is required by config")
                         {:var-name var-name}))))))

(defn interpolate
  [value]
  (cond
    (string? value)
    (interpolate-string value)

    (map? value)
    (into (empty value)
          (map (fn [[k v]] [k (interpolate v)]))
          value)

    (vector? value)
    (mapv interpolate value)

    (sequential? value)
    (doall (map interpolate value))

    :else
    value))

(defn secret-key?
  [k]
  (let [s (-> (name k)
              (str/replace #"_" "-")
              (str/lower-case))]
    (boolean (some #(str/includes? s %) secret-key-fragments))))

(defn redact
  [value]
  (cond
    (map? value)
    (into (empty value)
          (map (fn [[k v]]
                 [k (if (secret-key? k) "<redacted>" (redact v))]))
          value)

    (vector? value)
    (mapv redact value)

    (sequential? value)
    (doall (map redact value))

    :else
    value))
