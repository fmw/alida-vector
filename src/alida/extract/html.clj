(ns alida.extract.html
  (:require [alida.text :as text]
            [alida.url :as url]
            [clojure.string :as str])
  (:import [org.jsoup Jsoup]
           [org.jsoup.nodes Element TextNode]
           [org.jsoup.select NodeTraversor NodeVisitor]))

(def block-tags
  #{"blockquote" "code" "dd" "dt" "figcaption" "h1" "h2" "h3" "h4" "h5" "h6"
    "li" "p" "pre" "td" "th"})

(def heading-tags
  #{"h1" "h2" "h3" "h4" "h5" "h6"})

(def default-remove-selectors
  ["script" "style" "noscript" "meta" "link" "svg" "canvas" "iframe"])

(def default-language-selectors
  ["html[lang]"
   "meta[property=og:locale]"
   "meta[name=language]"
   "meta[name=dc.language]"])

(def ^:private external-link-source-types
  #{"jira-service-management" "webdriver"})

(defn- preserve-external-links?
  [source-cfg]
  (and (contains? external-link-source-types (:type source-cfg))
       (not= false (:preserve_external_links source-cfg))))

(defn- internal-link-hosts
  [source-cfg canonical-url]
  (set
   (keep (fn [host]
           (some-> host str/trim not-empty str/lower-case))
         (concat [(url/host canonical-url)]
                 (:internal_link_hosts source-cfg)))))

(defn- inside-block?
  [^Element element]
  (loop [parent (.parent element)]
    (cond
      (nil? parent) false
      (contains? block-tags (.normalName parent)) true
      :else (recur (.parent parent)))))

(defn- external-link-markdown
  [internal-hosts ^Element element]
  (let [href (not-empty (.absUrl element "href"))
        link-text (some-> (.text element) text/normalize-text not-empty)
        host (url/http-host href)]
    (when (and href
               link-text
               host
               (not (contains? internal-hosts host)))
      (str "[" link-text "](" href ")"))))

(defn- replace-external-link!
  [^Element element markdown]
  (let [replacement (if (inside-block? element)
                      (TextNode. markdown)
                      (doto (Element. "p")
                        (.text markdown)))]
    (.replaceWith element replacement)))

(defn- preserve-external-links!
  [document source-cfg canonical-url]
  (let [internal-hosts (internal-link-hosts source-cfg canonical-url)]
    (doseq [element (vec (.select document "a[href]"))]
      (when-let [markdown (external-link-markdown internal-hosts element)]
        (replace-external-link! element markdown))))
  document)

(defn- heading-level
  [tag]
  (parse-long (subs tag 1)))

(defn- element-text
  [^Element element]
  (text/normalize-text (.text element)))

(defn- update-heading-path
  [heading-path tag content]
  (let [level (heading-level tag)
        prefix (->> heading-path
                    (take (dec level))
                    vec)]
    (conj prefix content)))

(defn- block-type
  [tag]
  (cond
    (contains? heading-tags tag) :heading
    (#{"li"} tag) :list-item
    (#{"td" "th"} tag) :table-cell
    (#{"pre" "code"} tag) :code
    :else :paragraph))

(defn- traverse-blocks
  [^Element root]
  (let [blocks (atom [])
        heading-path (atom [])
        emitted-block-depth (atom nil)]
    (NodeTraversor/traverse
     (reify NodeVisitor
       (head [_ node _depth]
         (when (instance? Element node)
           (let [element ^Element node
                 tag (.normalName element)]
             (when (and (contains? block-tags tag)
                        (not (some-> @emitted-block-depth (< _depth))))
               (let [content (element-text element)]
                 (when (seq content)
                   (when (contains? heading-tags tag)
                     (swap! heading-path update-heading-path tag content))
                   (reset! emitted-block-depth _depth)
                   (swap! blocks conj {:type (block-type tag)
                                       :text content
                                       :heading_path @heading-path})))))))
       (tail [_ _node _depth]
         (when (= @emitted-block-depth _depth)
           (reset! emitted-block-depth nil))))
     root)
    @blocks))

(defn- apply-remove-selectors!
  [document selectors]
  (doseq [selector selectors]
    (doseq [element (.select document selector)]
      (.remove element)))
  document)

(defn- element-language
  [^Element element]
  (some-> (or (not-empty (.attr element "lang"))
              (not-empty (.attr element "content"))
              (not-empty (.attr element "data-locale"))
              (not-empty (.attr element "data-language")))
          text/normalize-text))

(defn- html-locale
  [document selectors]
  (some (fn [selector]
          (some element-language (.select document selector)))
        selectors))

(defn- strip-boilerplate
  [s strings]
  (reduce (fn [value boilerplate]
            (str/replace value boilerplate " "))
          s
          strings))

(defn extract
  "Extract semantic text blocks from HTML.

  source-cfg may contain :remove_selectors and :strip_text entries."
  [source-cfg {:keys [body canonical_url title content_type]}]
  (let [document (Jsoup/parse (or body "") (or canonical_url ""))
        selectors (concat default-remove-selectors (:remove_selectors source-cfg))
        language-selectors (or (seq (get-in source-cfg [:language :html_selectors]))
                               default-language-selectors)
        document-html-locale (html-locale document language-selectors)]
    (when (preserve-external-links? source-cfg)
      (preserve-external-links! document source-cfg canonical_url))
    (apply-remove-selectors! document selectors)
    (let [document-title (or title
                             (some-> (.title document) text/normalize-text not-empty))
          blocks (->> (traverse-blocks (.body document))
                      (map (fn [block]
                             (update block :text strip-boilerplate (:strip_text source-cfg))))
                      (map (fn [block]
                             (update block :text text/normalize-text)))
                      (remove (comp str/blank? :text))
                      vec)
          normalized-content (text/normalize-text (str/join "\n\n" (map :text blocks)))]
      {:canonical_url canonical_url
       :title document-title
       :content_type content_type
       :html_locale document-html-locale
       :raw_content_hash (text/sha-256 (or body ""))
       :normalized_content normalized-content
       :normalized_content_hash (text/sha-256 normalized-content)
       :blocks blocks})))
