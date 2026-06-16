(ns alida.source.object-storage
  (:require [clojure.java.io :as io]
            [clojure.string :as str])
  (:import [java.io InputStream]
           [java.nio.file FileSystems Paths]))

(def default-max-pages 1000)

(def extension-content-types
  {"html" "text/html"
   "htm" "text/html"
   "txt" "text/plain"
   "md" "text/markdown"
   "markdown" "text/markdown"
   "json" "application/json"})

(def generic-content-types
  #{"application/octet-stream"
    "binary/octet-stream"})

(defn json-safe-value
  [value]
  (cond
    (or (nil? value)
        (string? value)
        (number? value)
        (boolean? value)
        (keyword? value)
        (inst? value))
    value

    (instance? Throwable value)
    {:class (.getName (class value))
     :message (ex-message value)}

    (map? value)
    (into {} (map (fn [[k v]] [k (json-safe-value v)]) value))

    (sequential? value)
    (mapv json-safe-value value)

    (set? value)
    (mapv json-safe-value value)

    :else
    (str value)))

(defn extension
  [path]
  (some-> (re-find #"\.([^.]+)$" (str path))
          second
          str/lower-case))

(defn content-type
  [key object-content-type]
  (or (when-let [value (not-empty (str/trim (str object-content-type)))]
        (let [base-type (str/trim (first (str/split (str/lower-case value) #";" 2)))]
          (when-not (contains? generic-content-types base-type)
            value)))
      (get extension-content-types (extension key))
      "application/octet-stream"))

(defn canonical-url
  [scheme bucket key]
  (str scheme "://" bucket "/" key))

(defn- path-matcher
  [glob]
  (.getPathMatcher (FileSystems/getDefault) (str "glob:" glob)))

(defn glob-matches?
  [glob key]
  (.matches (path-matcher glob) (Paths/get key (make-array String 0))))

(defn object-included?
  [source-cfg key]
  (let [include-globs (:include_globs source-cfg)
        exclude-globs (:exclude_globs source-cfg)]
    (and (or (empty? include-globs)
             (some #(glob-matches? % key) include-globs))
         (not-any? #(glob-matches? % key) exclude-globs))))

(defn body-string
  [body]
  (cond
    (string? body) body
    (bytes? body) (String. ^bytes body "UTF-8")
    (instance? InputStream body) (with-open [reader (io/reader body :encoding "UTF-8")]
                                   (slurp reader))
    (nil? body) ""
    :else (str body)))
