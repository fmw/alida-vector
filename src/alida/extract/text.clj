(ns alida.extract.text
  (:require [alida.extract.html :as html]
            [alida.text :as text]
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

(defn- named-value
  [m k]
  (when (map? m)
    (let [k (name k)]
      (or (get m k)
          (get m (keyword k))))))

(defn- path-value
  [value path]
  (reduce
   (fn [current segment]
     (cond
       (nil? current)
       (reduced nil)

       (map? current)
       (named-value current segment)

       (and (sequential? current) (integer? segment))
       (nth current segment nil)

       :else
       (reduced nil)))
   value
   path))

(defn- matching-html-field?
  [cfg value]
  (= (str (named-value value (or (:field_type_key cfg) "type")))
     (str (or (:field_type_value cfg) "content_text"))))

(defn- html-field-value
  [cfg value]
  (let [html-field (or (:html_field cfg) "content")]
    (named-value value html-field)))

(defn- html-field-values*
  [cfg value]
  (lazy-seq
   (cond
     (map? value)
     (concat
      (when (matching-html-field? cfg value)
        (let [html (html-field-value cfg value)]
          (when (string? html)
            [html])))
      (mapcat #(html-field-values* cfg %) (vals value)))

     (sequential? value)
     (mapcat #(html-field-values* cfg %) value)

     :else
     nil)))

(defn- html-fragment-blocks
  [source-cfg canonical-url html-body]
  (:blocks (html/extract source-cfg
                         {:canonical_url canonical-url
                          :content_type "text/html"
                          :body html-body})))

(defn- html-field-blocks
  [source-cfg canonical-url json-value]
  (->> (html-field-values* (:json_extract source-cfg) json-value)
       (mapcat #(html-fragment-blocks source-cfg canonical-url %))
       vec))

(defn- basename
  [s]
  (when s
    (last (str/split (str s) #"/"))))

(defn- mapping-value
  [m k]
  (or (get m k)
      (get m (keyword k))
      (get m (name k))))

(defn- locale-from-filename
  [source-cfg filename]
  (let [{:keys [pattern mappings]} (get-in source-cfg [:json_extract :locale_from_filename])]
    (when (and pattern (seq mappings))
      (when-let [match (re-find (re-pattern pattern) (or (basename filename) ""))]
        (let [token (if (vector? match) (second match) match)]
          (mapping-value mappings token))))))

(defn- selected-html-json-extraction
  [source-cfg {:keys [body canonical_url title]}]
  (try
    (let [json-value (json/read-str (or body "null"))
          extracted-title (when-let [path (seq (get-in source-cfg [:json_extract :title_path]))]
                            (some-> (path-value json-value path) str text/normalize-text not-empty))
          html-locale (locale-from-filename source-cfg title)]
      {:title (or extracted-title title)
       :html_locale html-locale
       :blocks (html-field-blocks source-cfg canonical_url json-value)})
    (catch Exception _
      {:title title
       :blocks (paragraph-blocks body)})))

(defn- json-extraction
  [source-cfg document]
  (case (or (get-in source-cfg [:json_extract :mode]) "all")
    "html-fields" (selected-html-json-extraction source-cfg document)
    "all" {:title (:title document)
           :blocks (json-blocks (:body document))}
    {:title (:title document)
     :blocks (json-blocks (:body document))}))

(defn- blocks
  [source-cfg content-type document]
  (let [content-type (str/lower-case (or content-type ""))]
    (cond
      (str/starts-with? content-type "text/markdown")
      {:title (:title document)
       :blocks (markdown-blocks (:body document))}

      (str/starts-with? content-type "application/json")
      (json-extraction source-cfg document)

      :else
      {:title (:title document)
       :blocks (paragraph-blocks (:body document))})))

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
  (let [{:keys [blocks title html_locale]} (blocks source-cfg
                                                  content_type
                                                  {:body body
                                                   :canonical_url canonical_url
                                                   :title title})
        blocks (->> blocks
                    (map #(clean-block source-cfg %))
                    (remove (comp str/blank? :text))
                    vec)
        normalized-content (text/normalize-text (str/join "\n\n" (map :text blocks)))]
    (cond-> {:canonical_url canonical_url
             :title title
             :content_type content_type
             :raw_content_hash (text/sha-256 (or body ""))
             :normalized_content normalized-content
             :normalized_content_hash (text/sha-256 normalized-content)
             :blocks blocks}
      html_locale (assoc :html_locale html_locale))))
