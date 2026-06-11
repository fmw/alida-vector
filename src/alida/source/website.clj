(ns alida.source.website
  (:require [alida.source :as source]
            [clojure.string :as str])
  (:import [org.jsoup Jsoup]
           [org.jsoup.parser Parser]))

(def default-max-sitemap-depth 10)

(defn- sitemap-urls
  [source-cfg]
  (or (seq (:sitemap_urls source-cfg))
      (when-let [url (:sitemap_url source-cfg)]
        [url])))

(defn- max-sitemap-depth
  [source-cfg]
  (or (:max_sitemap_depth source-cfg)
      default-max-sitemap-depth))

(defn- url-allowed?
  [source-cfg url]
  (let [allowed-prefixes (:allowed_url_prefixes source-cfg)
        denied-urls (set (:denied_urls source-cfg))
        denied-prefixes (:denied_url_prefixes source-cfg)]
    (and (or (not (seq allowed-prefixes))
             (some #(str/starts-with? url %) allowed-prefixes))
         (not (contains? denied-urls url))
         (not-any? #(str/starts-with? url %) denied-prefixes))))

(defn- sitemap-location-elements
  [document kind]
  (case kind
    :sitemapindex (.select document "sitemap > loc")
    :urlset (.select document "url > loc")))

(defn- parse-sitemap
  [body]
  (let [document (Jsoup/parse body "" (Parser/xmlParser))
        kind (cond
               (.selectFirst document "sitemapindex") :sitemapindex
               (.selectFirst document "urlset") :urlset
               :else (throw (ex-info "Sitemap XML must contain urlset or sitemapindex"
                                     {:type :alida.source.website/invalid-sitemap})))]
    {:kind kind
     :locations (->> (sitemap-location-elements document kind)
                     (map #(.text %))
                     (map str/trim)
                     (remove str/blank?)
                     vec)}))

(defn- discovered-page
  [source-cfg sitemap-url url]
  {:source_id (:id source-cfg)
   :source_type (:type source-cfg)
   :canonical_url url
   :content_type "text/html"
   :sitemap_url sitemap-url})

(defn- discover-sitemap
  ([sys source-cfg sitemap-url]
   (discover-sitemap sys source-cfg #{} 1 sitemap-url))
  ([sys source-cfg visited depth sitemap-url]
   (cond
     (contains? visited sitemap-url)
     []

     (> depth (max-sitemap-depth source-cfg))
     (throw (ex-info (str "Sitemap recursion exceeded max_sitemap_depth: " sitemap-url)
                     {:type :alida.source.website/sitemap-depth-exceeded
                      :source-id (:id source-cfg)
                      :sitemap-url sitemap-url
                      :max-sitemap-depth (max-sitemap-depth source-cfg)}))

     :else
     (let [response (source/require-success!
                     (source/request! sys {:method :get :url sitemap-url})
                     {:source-id (:id source-cfg)
                      :sitemap-url sitemap-url})
           {:keys [kind locations]} (parse-sitemap (:body response))]
       (case kind
         :sitemapindex
         (mapv identity
               (mapcat #(discover-sitemap sys source-cfg (conj visited sitemap-url) (inc depth) %)
                       locations))

         :urlset
         (->> locations
              (filter #(url-allowed? source-cfg %))
              (mapv #(discovered-page source-cfg sitemap-url %))))))))

(defmethod source/discover :website
  [sys source-cfg]
  (let [sitemaps (sitemap-urls source-cfg)]
    (when-not (seq sitemaps)
      (throw (ex-info "Website source requires sitemap_url or sitemap_urls"
                      {:type :alida.source.website/missing-sitemap
                       :source-id (:id source-cfg)})))
    (mapv identity (mapcat #(discover-sitemap sys source-cfg %) sitemaps))))

(defmethod source/fetch :website
  [sys source-cfg discovered-item]
  (let [response (source/request! sys {:method :get
                                       :url (:canonical_url discovered-item)})]
    (if (source/successful-status? (:status response))
      (assoc discovered-item
             :body (:body response)
             :content_type (or (get-in response [:headers "Content-Type"])
                               (get-in response [:headers "content-type"])
                               (:content_type discovered-item)
                               "text/html"))
      (source/anomaly (case (:status response)
                        404 :cognitect.anomalies/not-found
                        :cognitect.anomalies/fault)
                      (merge {:type :alida.source.website/fetch-failed
                              :source-id (:id source-cfg)
                              :canonical-url (:canonical_url discovered-item)
                              :status (:status response)}
                             (source/error-response-details response))))))
