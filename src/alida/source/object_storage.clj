(ns alida.source.object-storage
  (:require [alida.source :as source]
            [clojure.java.io :as io]
            [clojure.string :as str])
  (:import [java.io InputStream]
           [java.net URI]))

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

(declare body-string canonical-url content-type object-included?)

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

(defn external-anomaly?
  [value]
  (and (map? value)
       (contains? value :cognitect.anomalies/category)))

(defn throw-request-anomaly!
  [service-label error-type source-cfg op result]
  (when (external-anomaly? result)
    (throw (ex-info (str service-label " " (name op) " failed")
                    (assoc (json-safe-value result)
                           :type error-type
                           :source-id (:id source-cfg)
                           :operation op)))))

(defn fetch-anomaly
  [error-type source-cfg op item result]
  (source/anomaly (or (:cognitect.anomalies/category result)
                      :cognitect.anomalies/fault)
                  (assoc (json-safe-value result)
                         :type error-type
                         :source-id (:id source-cfg)
                         :operation op
                         :canonical-url (:canonical_url item)
                         :bucket (:bucket item)
                         :key (:key item))))

(defn object-item
  [source-cfg scheme key attrs]
  (merge {:source_id (:id source-cfg)
          :source_type (:type source-cfg)
          :canonical_url (canonical-url scheme (:bucket source-cfg) key)
          :bucket (:bucket source-cfg)
          :key key}
         attrs))

(defn page-objects
  [source-cfg scheme objects key-fn attrs-fn]
  (->> objects
       (keep (fn [object]
               (let [key (key-fn object)]
                 (when (and (seq key)
                            (not (str/ends-with? key "/"))
                            (object-included? source-cfg key))
                   (object-item source-cfg scheme key (attrs-fn object key))))))))

(defn max-pages
  [source-cfg]
  (or (:max_pages source-cfg) default-max-pages))

(defn discover-paged
  [source-cfg {:keys [list-page page-objects next-token continue?
                      service-label request-error-type op]}]
  (loop [items []
         page-token nil]
    (let [remaining (- (max-pages source-cfg) (count items))]
      (if (not (pos? remaining))
        items
        (let [response (list-page page-token remaining)
              _ (throw-request-anomaly! service-label request-error-type source-cfg op response)
              page-items (vec (take remaining (page-objects response)))
              items (into items page-items)
              token (next-token response)]
          (if (and (continue? response token)
                   (< (count items) (max-pages source-cfg)))
            (recur items token)
            items))))))

(defn fetched-document
  [source-cfg discovered-item response {:keys [op fetch-error-type body-fn content-type-fn]}]
  (if (external-anomaly? response)
    (fetch-anomaly fetch-error-type source-cfg op discovered-item response)
    (assoc discovered-item
           :body (body-string (body-fn response))
           :content_type (content-type (:key discovered-item) (content-type-fn response))
           :title (or (:title discovered-item) (:key discovered-item)))))

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
  (.toASCIIString (URI. scheme bucket (str "/" key) nil)))

(def regex-special-chars
  #{\. \( \) \+ \| \^ \$ \@ \% \& \{ \} \[ \] \\})

(defn- append-quoted-char
  [^StringBuilder builder ch]
  (if (contains? regex-special-chars ch)
    (.append builder (str "\\" ch))
    (.append builder ch)))

(defn- glob-regex
  [glob]
  (let [builder (StringBuilder.)
        chars (vec (str glob))
        length (count chars)]
    (loop [i 0]
      (when (< i length)
        (let [ch (nth chars i)
              next-ch (when (< (inc i) length) (nth chars (inc i)))]
          (cond
            (and (= \* ch) (= \* next-ch))
            (do
              (.append builder ".*")
              (recur (+ i 2)))

            (= \* ch)
            (do
              (.append builder "[^/]*")
              (recur (inc i)))

            (= \? ch)
            (do
              (.append builder "[^/]")
              (recur (inc i)))

            :else
            (do
              (append-quoted-char builder ch)
              (recur (inc i)))))))
    (re-pattern (str "^" builder "$"))))

(defn- glob-variants
  [glob]
  (let [parts (str/split glob #"\*\*/" -1)]
    (if (= 1 (count parts))
      [glob]
      (->> (rest parts)
           (reduce (fn [prefixes part]
                     (mapcat (fn [prefix]
                               [(str prefix "**/" part)
                                (str prefix part)])
                             prefixes))
                   [(first parts)])
           distinct
           vec))))

(defn glob-matches?
  [glob key]
  (let [key (str key)]
    (some #(when (re-matches (glob-regex %) key) true)
          (glob-variants glob))))

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
