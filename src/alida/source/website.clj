(ns alida.source.website
  (:require [alida.source :as source]
            [clojure.string :as str])
  (:import [org.jsoup Jsoup]
           [org.jsoup.parser Parser]))

(defn- sitemap-urls
  [source-cfg]
  (or (seq (:sitemap_urls source-cfg))
      (when-let [url (:sitemap_url source-cfg)]
        [url])))

(defn- url-allowed?
  [source-cfg url]
  (let [allowed-prefixes (:allowed_url_prefixes source-cfg)
        denied-prefixes (:denied_url_prefixes source-cfg)]
    (and (or (not (seq allowed-prefixes))
             (some #(str/starts-with? url %) allowed-prefixes))
         (not-any? #(str/starts-with? url %) denied-prefixes))))

(defn- parse-sitemap-locations
  [body]
  (let [document (Jsoup/parse body "" (Parser/xmlParser))]
    (->> (.select document "loc")
         (map #(.text %))
         (map str/trim)
         (remove str/blank?)
         vec)))

(defn- discover-sitemap
  [sys source-cfg sitemap-url]
  (let [response (source/require-success!
                  (source/request! sys {:method :get :url sitemap-url})
                  {:source-id (:id source-cfg)
                   :sitemap-url sitemap-url})]
    (->> (parse-sitemap-locations (:body response))
         (filter #(url-allowed? source-cfg %))
         (mapv (fn [url]
                 {:source_id (:id source-cfg)
                  :source_type (:type source-cfg)
                  :canonical_url url
                  :content_type "text/html"
                  :sitemap_url sitemap-url})))))

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
                      {:type :alida.source.website/fetch-failed
                       :source-id (:id source-cfg)
                       :canonical-url (:canonical_url discovered-item)
                       :status (:status response)
                       :body (:body response)}))))
