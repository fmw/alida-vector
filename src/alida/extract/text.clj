(ns alida.extract.text
  (:require [alida.text :as text]
            [clojure.data.json :as json]
            [clojure.string :as str]))

(defn- paragraph-blocks
  [body]
  (->> (str/split (or body "") #"\n\s*\n")
       (map text/normalize-text)
       (remove str/blank?)
       (mapv #(hash-map :type :paragraph
                        :text %
                        :heading_path []))))

(defn- markdown-heading
  [line]
  (when-let [[_ hashes content] (re-matches #"\s{0,3}(#{1,6})\s+(.+?)\s*#*\s*" line)]
    {:level (count hashes)
     :text (text/normalize-text content)}))

(defn- update-heading-path
  [heading-path level content]
  (conj (vec (take (dec level) heading-path)) content))

(defn- flush-paragraph
  [blocks lines heading-path]
  (let [content (text/normalize-text (str/join "\n" lines))]
    (cond-> blocks
      (seq content) (conj {:type :paragraph
                           :text content
                           :heading_path heading-path}))))

(defn- markdown-blocks
  [body]
  (let [{:keys [blocks heading-path paragraph-lines]}
        (reduce
         (fn [{:keys [blocks heading-path paragraph-lines]} line]
           (if-let [{:keys [level text]} (markdown-heading line)]
             (let [blocks (flush-paragraph blocks paragraph-lines heading-path)
                   heading-path (update-heading-path heading-path level text)]
               {:blocks (conj blocks {:type :heading
                                      :text text
                                      :heading_path heading-path})
                :heading-path heading-path
                :paragraph-lines []})
             (if (str/blank? line)
               {:blocks (flush-paragraph blocks paragraph-lines heading-path)
                :heading-path heading-path
                :paragraph-lines []}
               {:blocks blocks
                :heading-path heading-path
                :paragraph-lines (conj paragraph-lines line)})))
         {:blocks []
          :heading-path []
          :paragraph-lines []}
         (str/split-lines (or body "")))]
    (flush-paragraph blocks paragraph-lines heading-path)))

(defn- scalar-json?
  [value]
  (or (nil? value)
      (string? value)
      (number? value)
      (boolean? value)))

(defn- json-scalar-text
  [value]
  (cond
    (nil? value) "null"
    (string? value) value
    :else (str value)))

(declare json-blocks*)

(defn- json-entry-blocks
  [heading-path k value]
  (let [label (str k)]
    (if (scalar-json? value)
      [{:type :paragraph
        :text (text/normalize-text (str label ": " (json-scalar-text value)))
        :heading_path heading-path}]
      (let [heading-path (conj heading-path label)]
        (into [{:type :heading
                :text label
                :heading_path heading-path}]
              (json-blocks* heading-path value))))))

(defn- json-blocks*
  [heading-path value]
  (cond
    (map? value)
    (mapcat (fn [[k v]] (json-entry-blocks heading-path k v)) value)

    (sequential? value)
    (mapcat (fn [idx v] (json-entry-blocks heading-path (str idx) v))
            (range)
            value)

    :else
    [{:type :paragraph
      :text (text/normalize-text (json-scalar-text value))
      :heading_path heading-path}]))

(defn- json-blocks
  [body]
  (try
    (->> (json/read-str (or body "null"))
         (json-blocks* [])
         (remove (comp str/blank? :text))
         vec)
    (catch Exception _
      (paragraph-blocks body))))

(defn- blocks
  [content-type body]
  (let [content-type (str/lower-case (or content-type ""))]
    (cond
      (str/starts-with? content-type "text/markdown")
      (markdown-blocks body)

      (str/starts-with? content-type "application/json")
      (json-blocks body)

      :else
      (paragraph-blocks body))))

(defn- strip-boilerplate
  [s strings]
  (reduce (fn [value boilerplate]
            (str/replace value boilerplate " "))
          s
          strings))

(defn- clean-block
  [source-cfg block]
  (update block :text #(text/normalize-text (strip-boilerplate % (:strip_text source-cfg)))))

(defn extract
  [source-cfg {:keys [body canonical_url title content_type]}]
  (let [blocks (->> (blocks content_type body)
                    (map #(clean-block source-cfg %))
                    (remove (comp str/blank? :text))
                    vec)
        normalized-content (text/normalize-text (str/join "\n\n" (map :text blocks)))]
    {:canonical_url canonical_url
     :title title
     :content_type content_type
     :raw_content_hash (text/sha-256 (or body ""))
     :normalized_content normalized-content
     :normalized_content_hash (text/sha-256 normalized-content)
     :blocks blocks}))
