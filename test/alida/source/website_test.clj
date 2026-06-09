(ns alida.source.website-test
  (:require [alida.source :as source]
            [alida.source.website]
            [clojure.test :refer [deftest is]]))

(def sitemap
  "<?xml version=\"1.0\" encoding=\"UTF-8\"?>
   <urlset>
     <url><loc>https://example.test/docs/a</loc></url>
     <url><loc>https://example.test/docs/b</loc></url>
     <url><loc>https://example.test/private/c</loc></url>
   </urlset>")

(def sitemap-index
  "<?xml version=\"1.0\" encoding=\"UTF-8\"?>
   <sitemapindex>
     <sitemap><loc>https://example.test/post-sitemap.xml</loc></sitemap>
     <sitemap><loc>https://example.test/page-sitemap.xml</loc></sitemap>
   </sitemapindex>")

(def post-sitemap
  "<?xml version=\"1.0\" encoding=\"UTF-8\"?>
   <urlset>
     <url><loc>https://example.test/blog/a</loc></url>
   </urlset>")

(def page-sitemap
  "<?xml version=\"1.0\" encoding=\"UTF-8\"?>
   <urlset>
     <url><loc>https://example.test/docs/a</loc></url>
     <url><loc>https://example.test/docs/b</loc></url>
   </urlset>")

(defn- fake-http
  [responses requests]
  {:alida/http-request (fn [request]
                         (swap! requests conj request)
                         (let [response (get responses (:url request))]
                           (or response
                               {:status 500
                                :body "missing fake response"})))})

(deftest discovers-website-urls-from-sitemap-with-prefix-filters
  (let [requests (atom [])
        sys (fake-http {"https://example.test/sitemap.xml" {:status 200 :body sitemap}}
                       requests)
        items (source/discover sys {:id "site"
                                    :type "website"
                                    :sitemap_url "https://example.test/sitemap.xml"
                                    :allowed_url_prefixes ["https://example.test/docs/"]
                                    :denied_url_prefixes ["https://example.test/docs/b"]})]
    (is (= [{:source_id "site"
             :source_type "website"
             :canonical_url "https://example.test/docs/a"
             :content_type "text/html"
             :sitemap_url "https://example.test/sitemap.xml"}]
           items))
    (is (= [{:throw-exceptions false
             :connect-timeout source/default-request-timeout-ms
             :request-timeout source/default-request-timeout-ms
             :method :get
             :url "https://example.test/sitemap.xml"}]
           @requests))))

(deftest exact-denied-urls-are-excluded-from-website-discovery
  (let [requests (atom [])
        sys (fake-http {"https://example.test/sitemap.xml" {:status 200 :body sitemap}}
                       requests)
        items (source/discover sys {:id "site"
                                    :type "website"
                                    :sitemap_url "https://example.test/sitemap.xml"
                                    :allowed_url_prefixes ["https://example.test/docs/"]
                                    :denied_urls ["https://example.test/docs/a"]})]
    (is (= ["https://example.test/docs/b"]
           (mapv :canonical_url items)))))

(deftest sitemap-http-failure-is-a-fatal-source-error
  (let [sys (fake-http {"https://example.test/sitemap.xml" {:status 503 :body "unavailable"}}
                       (atom []))]
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"HTTP 503"
         (source/discover sys {:id "site"
                               :type "website"
                               :sitemap_url "https://example.test/sitemap.xml"})))))

(deftest discovers-website-urls-from-sitemap-index
  (let [requests (atom [])
        sys (fake-http {"https://example.test/sitemap.xml" {:status 200 :body sitemap-index}
                        "https://example.test/post-sitemap.xml" {:status 200 :body post-sitemap}
                        "https://example.test/page-sitemap.xml" {:status 200 :body page-sitemap}}
                       requests)
        items (source/discover sys {:id "site"
                                    :type "website"
                                    :sitemap_url "https://example.test/sitemap.xml"
                                    :allowed_url_prefixes ["https://example.test/docs/"]})]
    (is (= [{:source_id "site"
             :source_type "website"
             :canonical_url "https://example.test/docs/a"
             :content_type "text/html"
             :sitemap_url "https://example.test/page-sitemap.xml"}
            {:source_id "site"
             :source_type "website"
             :canonical_url "https://example.test/docs/b"
             :content_type "text/html"
             :sitemap_url "https://example.test/page-sitemap.xml"}]
           items))
    (is (= ["https://example.test/sitemap.xml"
            "https://example.test/post-sitemap.xml"
            "https://example.test/page-sitemap.xml"]
           (mapv :url @requests)))))

(deftest recursive-sitemap-discovery-skips-already-visited-sitemaps
  (let [requests (atom [])
        sys (fake-http {"https://example.test/sitemap.xml" {:status 200 :body sitemap-index}
                        "https://example.test/post-sitemap.xml" {:status 200 :body page-sitemap}
                        "https://example.test/page-sitemap.xml" {:status 200
                                                                 :body "<?xml version=\"1.0\" encoding=\"UTF-8\"?>
                                                                        <sitemapindex>
                                                                          <sitemap><loc>https://example.test/sitemap.xml</loc></sitemap>
                                                                        </sitemapindex>"}}
                       requests)
        items (source/discover sys {:id "site"
                                    :type "website"
                                    :sitemap_url "https://example.test/sitemap.xml"})]
    (is (= ["https://example.test/docs/a" "https://example.test/docs/b"]
           (mapv :canonical_url items)))
    (is (= ["https://example.test/sitemap.xml"
            "https://example.test/post-sitemap.xml"
            "https://example.test/page-sitemap.xml"]
           (mapv :url @requests)))))

(deftest fetches-website-pages-and-preserves-content-type
  (let [requests (atom [])
        sys (fake-http {"https://example.test/docs/a" {:status 200
                                                       :headers {"Content-Type" "text/html; charset=utf-8"}
                                                       :body "<h1>A</h1>"}}
                       requests)
        fetched (source/fetch sys
                              {:id "site" :type "website"}
                              {:source_id "site"
                               :source_type "website"
                               :canonical_url "https://example.test/docs/a"})]
    (is (= "<h1>A</h1>" (:body fetched)))
    (is (= "text/html; charset=utf-8" (:content_type fetched)))))

(deftest failed-website-page-fetches-are-recoverable-anomalies
  (let [sys (fake-http {"https://example.test/missing" {:status 404 :body "not found"}}
                       (atom []))
        fetched (source/fetch sys
                              {:id "site" :type "website"}
                              {:source_id "site"
                               :source_type "website"
                               :canonical_url "https://example.test/missing"})]
    (is (source/anomaly? fetched))
    (is (= :cognitect.anomalies/not-found
           (get-in fetched [:alida/error :cognitect.anomalies/category])))))
