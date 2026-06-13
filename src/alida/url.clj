(ns alida.url
  "Shared URL helpers for source connectors: origin/host extraction, resolution
   and normalization, allow/deny filtering, and JSM identifier extraction.

   Connectors should reuse these rather than carrying private copies."
  (:require [clojure.string :as str])
  (:import [java.net URI]))

(defn host
  "Lower-cased host of url, or nil when url is not a parseable absolute URI."
  [url]
  (try
    (some-> (URI. url) .getHost str/lower-case)
    (catch Exception _
      nil)))

(defn origin
  "scheme://host[:port] for url, or nil when scheme/host are missing."
  [url]
  (try
    (let [uri (URI. url)
          scheme (.getScheme uri)
          host (.getHost uri)
          port (.getPort uri)]
      (when (and scheme host)
        (str scheme "://" host (when (not= -1 port) (str ":" port)))))
    (catch Exception _
      nil)))

(defn http-host
  "Lower-cased host of url, but only for http/https URLs. Used for politeness
   gating where non-web schemes should fall back to a per-source key."
  [url]
  (try
    (let [uri (URI. url)
          scheme (some-> (.getScheme uri) str/lower-case)
          host (some-> (.getHost uri) str/lower-case)]
      (when (and (#{"http" "https"} scheme) (seq host))
        host))
    (catch Exception _
      nil)))

(defn normalize
  "Resolve href against base-url, normalize the path, and drop the fragment.
   Returns the resulting absolute URL string, or nil when resolution fails."
  [base-url href]
  (try
    (let [base (URI. base-url)
          resolved (.normalize (.resolve base href))
          normalized (URI. (.getScheme resolved)
                           (.getUserInfo resolved)
                           (.getHost resolved)
                           (.getPort resolved)
                           (.getPath resolved)
                           (.getQuery resolved)
                           nil)]
      (str normalized))
    (catch Exception _
      nil)))

(defn allowed?
  "True when url passes a normalized allow/deny config map with keys
   :allowed-prefixes, :denied-urls, :denied-prefixes. An empty/nil allowed-prefixes
   list permits any url. denied-urls is matched exactly; denied-prefixes by prefix."
  [{:keys [allowed-prefixes denied-urls denied-prefixes]} url]
  (and (seq url)
       (or (not (seq allowed-prefixes))
           (some #(str/starts-with? url %) allowed-prefixes))
       (not (contains? (set denied-urls) url))
       (not-any? #(str/starts-with? url %) denied-prefixes)))

(defn source-allow-config
  "Build an allowed?-compatible map from raw snake_case source-cfg keys, using
   the supplied allowed prefixes (callers differ on how prefixes are derived)."
  [source-cfg allowed-prefixes]
  {:allowed-prefixes allowed-prefixes
   :denied-urls (:denied_urls source-cfg)
   :denied-prefixes (:denied_url_prefixes source-cfg)})

(def ^:private default-article-id-patterns
  [#"/article/(\d+)"
   #"/articles/view/(\d+)"
   #"/kb/view/(\d+)"
   #"/pages/(\d+)"
   #"/plugins/servlet/servicedesk/customer/confluence/shim/spaces/[^/]+/pages/(\d+)"
   #"/wiki/spaces/[^/]+/pages/(\d+)"])

(defn article-id
  "Extract a numeric JSM/Confluence article id from a url. Tries the default
   pattern set, or a caller-supplied seq of regexes whose first group is the id.
   Restricting to digits avoids matching paths such as `resumedraft.action`."
  ([url] (article-id url default-article-id-patterns))
  ([url patterns]
   (some #(second (re-find % (or url ""))) patterns)))

(defn path-id
  "Extract the first path segment after `/segment/` (e.g. /topic/<id>, /portal/<id>)."
  [segment url]
  (second (re-find (re-pattern (str "/" segment "/([^/?#]+)")) (or url ""))))
