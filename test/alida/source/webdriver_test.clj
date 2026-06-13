(ns alida.source.webdriver-test
  (:require [alida.source :as source]
            [alida.source.webdriver :as webdriver]
            [clojure.test :refer [deftest is]]
            [etaoin.api :as e]))

(defn- page
  [url hrefs]
  {:source_id "support"
   :source_type "webdriver"
   :canonical_url url
   :content_type "text/html"
   :title (str "Page " url)
   :body (str "<main><h1>" url "</h1><p>Rendered article.</p></main>")
   :hrefs hrefs})

(deftest discovers-rendered-pages-and-follows-allowed-links
  (let [visited (atom [])
        quit? (atom false)]
    (with-redefs-fn {#'webdriver/start-driver! (fn [_] :driver)
                     #'webdriver/quit-driver! (fn [driver]
                                          (is (= :driver driver))
                                          (reset! quit? true))
                     #'webdriver/render-page! (fn [driver _source-cfg url]
                                          (is (= :driver driver))
                                          (swap! visited conj url)
                                          (case url
                                            "https://example.test/portal"
                                            (page url ["https://example.test/portal/topic/a"
                                                       "https://example.test/private/skip"
                                                       "https://other.test/offsite"])

                                            "https://example.test/portal/topic/a"
                                            (page url ["https://example.test/portal/topic/b"])

                                            "https://example.test/portal/topic/b"
                                            (page url [])))}
      (fn []
      (let [items (source/discover
                   {}
                   {:id "support"
                    :type "webdriver"
                    :url "https://example.test/portal"
                    :denied_url_prefixes ["https://example.test/private/"]})]
        (is @quit?)
        (is (= ["https://example.test/portal"
                "https://example.test/portal/topic/a"
                "https://example.test/portal/topic/b"]
               @visited))
        (is (= @visited (mapv :canonical_url items)))
        (is (every? :body items)))))))

(deftest configured-allowed-prefixes-override-same-origin-default
  (let [visited (atom [])]
    (with-redefs-fn {#'webdriver/start-driver! (fn [_] :driver)
                     #'webdriver/quit-driver! (fn [_])
                     #'webdriver/render-page! (fn [_ _ url]
                                          (swap! visited conj url)
                                          (page url ["https://example.test/allowed/a"
                                                     "https://example.test/other/b"]))}
      (fn []
      (let [items (source/discover
                   {}
                   {:id "support"
                    :type "webdriver"
                    :url "https://example.test/portal"
                    :allowed_url_prefixes ["https://example.test/allowed/"]
                    :max_pages 2})]
        (is (= ["https://example.test/portal"
                "https://example.test/allowed/a"]
               @visited))
        (is (= @visited (mapv :canonical_url items))))))))

(deftest discovery-stops-at-max-pages-and-returns-render-errors
  (let [quit? (atom false)]
    (with-redefs-fn {#'webdriver/start-driver! (fn [_] :driver)
                     #'webdriver/quit-driver! (fn [_] (reset! quit? true))
                     #'webdriver/render-page! (fn [_ _ url]
                                          (if (= "https://example.test/portal" url)
                                            (page url ["https://example.test/portal/a"])
                                            (source/anomaly :cognitect.anomalies/fault
                                                            {:type :test/render-failed
                                                             :canonical-url url})))}
      (fn []
      (let [items (source/discover
                   {}
                   {:id "support"
                    :type "webdriver"
                    :url "https://example.test/portal"
                    :max_pages 2})]
        (is @quit?)
        (is (= 2 (count items)))
        (is (source/anomaly? (second items))))))))

(deftest discovery-restarts-browser-after-page-limit
  (let [starts (atom 0)
        quits (atom [])
        rendered-with (atom [])]
    (with-redefs-fn {#'webdriver/start-driver! (fn [_]
                                           (keyword (str "driver-" (swap! starts inc))))
                     #'webdriver/quit-driver! (fn [driver]
                                          (swap! quits conj driver))
                     #'webdriver/render-page! (fn [driver _ url]
                                          (swap! rendered-with conj driver)
                                          (case url
                                            "https://example.test/portal"
                                            (page url ["https://example.test/portal/a"])

                                            "https://example.test/portal/a"
                                            (page url ["https://example.test/portal/b"])

                                            "https://example.test/portal/b"
                                            (page url [])))}
      (fn []
        (let [items (source/discover
                     {}
                     {:id "support"
                      :type "webdriver"
                      :url "https://example.test/portal"
                      :browser_restart_after_pages 2})]
          (is (= 3 (count items)))
          (is (= [:driver-1 :driver-1 :driver-2] @rendered-with))
          (is (= [:driver-1 :driver-2] @quits)))))))

(deftest discovery-restarts-browser-after-consecutive-render-failures
  (let [starts (atom 0)
        quits (atom [])]
    (with-redefs-fn {#'webdriver/start-driver! (fn [_]
                                           (keyword (str "driver-" (swap! starts inc))))
                     #'webdriver/quit-driver! (fn [driver]
                                          (swap! quits conj driver))
                     #'webdriver/render-page! (fn [_ _ url]
                                          (if (= "https://example.test/portal" url)
                                            (page url ["https://example.test/portal/a"
                                                       "https://example.test/portal/b"])
                                            (source/anomaly :cognitect.anomalies/fault
                                                            {:type :test/render-failed
                                                             :canonical-url url})))}
      (fn []
        (let [items (source/discover
                     {}
                     {:id "support"
                      :type "webdriver"
                      :url "https://example.test/portal"
                      :browser_restart_after_failures 2})]
          (is (= 3 (count items)))
          (is (= [:driver-1 :driver-2] @quits)))))))

(deftest discovery-retries-blank-render-with-a-fresh-browser
  (let [starts (atom 0)
        quits (atom [])
        calls (atom [])]
    (with-redefs-fn {#'webdriver/start-driver! (fn [_]
                                           (keyword (str "driver-" (swap! starts inc))))
                     #'webdriver/quit-driver! (fn [driver]
                                          (swap! quits conj driver))
                     #'webdriver/render-page! (fn [driver _ url]
                                          (swap! calls conj [driver url])
                                          (if (= driver :driver-1)
                                            (assoc (page url []) :body "<body></body>")
                                            (page url [])))}
      (fn []
        (let [items (source/discover
                     {}
                     {:id "support"
                      :type "webdriver"
                      :url "https://example.test/portal"
                      :browser_restart_after_pages 50})]
          (is (= 1 (count items)))
          (is (= "<main><h1>https://example.test/portal</h1><p>Rendered article.</p></main>"
                 (:body (first items))))
          (is (= [[:driver-1 "https://example.test/portal"]
                  [:driver-2 "https://example.test/portal"]]
                 @calls))
          (is (= [:driver-1 :driver-2] @quits)))))))

(deftest discovery-retries-blank-render-at-canonical-url
  (let [starts (atom 0)
        calls (atom [])]
    (with-redefs-fn {#'webdriver/start-driver! (fn [_]
                                           (keyword (str "driver-" (swap! starts inc))))
                     #'webdriver/quit-driver! (fn [_])
                     #'webdriver/render-page! (fn [driver _ url]
                                          (swap! calls conj [driver url])
                                          (if (= driver :driver-1)
                                            (assoc (page "https://example.test/canonical" [])
                                                   :body "<body></body>")
                                            (page url [])))}
      (fn []
        (let [items (source/discover
                     {}
                     {:id "support"
                      :type "webdriver"
                      :url "https://example.test/shim"})]
          (is (= ["https://example.test/canonical"]
                 (mapv :canonical_url items)))
          (is (= [[:driver-1 "https://example.test/shim"]
                  [:driver-2 "https://example.test/canonical"]]
                 @calls)))))))

(deftest failed-navigation-returns-render-anomaly
  (with-redefs [e/set-page-load-timeout (fn [_ _])
                e/go (fn [_ _]
                       (throw (ex-info "navigation failed" {})))]
    (let [result (#'webdriver/render-page!
                  :driver
                  {:id "support"
                   :type "webdriver"}
                  "https://example.test/portal/a")]
      (is (source/anomaly? result))
      (is (= :alida.source.webdriver/render-failed
             (get-in result [:alida/error :type])))
      (is (= "https://example.test/portal/a"
             (get-in result [:alida/error :canonical_url]))))))

(deftest discovery-can-render-pages-in-parallel
  (let [started (atom [])
        rendered (atom [])
        latch (java.util.concurrent.CountDownLatch. 2)]
    (with-redefs-fn {#'webdriver/start-driver! (fn [_]
                                                 (let [driver (keyword (str "driver-" (inc (count @started))))]
                                                   (swap! started conj driver)
                                                   driver))
                     #'webdriver/quit-driver! (fn [_])
                     #'webdriver/render-page! (fn [driver _ url]
                                                (swap! rendered conj [driver url])
                                                (when (#{"https://example.test/portal/a"
                                                         "https://example.test/portal/b"} url)
                                                  (.countDown latch)
                                                  (.await latch 1 java.util.concurrent.TimeUnit/SECONDS))
                                                (case url
                                                  "https://example.test/portal"
                                                  (page url ["https://example.test/portal/a"
                                                             "https://example.test/portal/b"])

                                                  "https://example.test/portal/a"
                                                  (page url [])

                                                  "https://example.test/portal/b"
                                                  (page url [])))}
      (fn []
        (let [items (source/discover
                     {}
                     {:id "support"
                      :type "webdriver"
                      :url "https://example.test/portal"
                      :max_concurrency 2})]
          (is (= 3 (count items)))
          (is (= 2 (count @started)))
          (is (= #{"https://example.test/portal"
                   "https://example.test/portal/a"
                   "https://example.test/portal/b"}
                 (set (map second @rendered)))))))))

(deftest parallel-discovery-does-not-exceed-max-pages
  (let [rendered (atom [])]
    (with-redefs-fn {#'webdriver/start-driver! (fn [_] :driver)
                     #'webdriver/quit-driver! (fn [_])
                     #'webdriver/render-page! (fn [_ _ url]
                                                (swap! rendered conj url)
                                                (page url []))}
      (fn []
        (let [items (source/discover
                     {}
                     {:id "support"
                      :type "webdriver"
                      :start_urls ["https://example.test/portal/a"
                                   "https://example.test/portal/b"]
                      :max_concurrency 2
                      :max_pages 1})]
          (is (= 1 (count items)))
          (is (= 1 (count @rendered))))))))

(deftest source-requires-a-start-url
  (is (thrown-with-msg?
       clojure.lang.ExceptionInfo
       #"requires url"
       (source/discover {} {:id "support"
                            :type "webdriver"}))))

(deftest fetch-returns-rendered-document
  (let [item (page "https://example.test/portal/a" [])]
    (is (= (assoc item :content_type "text/html")
           (source/fetch {} {:id "support"
                             :type "webdriver"}
                         item)))))

(deftest fetch-rejects-items-without-rendered-body
  (let [result (source/fetch {} {:id "support"
                                 :type "webdriver"}
                             {:canonical_url "https://example.test/portal/a"})]
    (is (source/anomaly? result))
    (is (= :alida.source.webdriver/missing-rendered-body
           (get-in result [:alida/error :type])))))

(deftest fallback-rendering-is-limited-to-article-pages
  (is (#'webdriver/article-url? "https://example.test/servicedesk/customer/portal/7/article/123"))
  (is (#'webdriver/article-url? "https://example.test/servicedesk/customer/portal/7/topic/abc/article/123"))
  (is (not (#'webdriver/article-url? "https://example.test/servicedesk/customer/portal/7/topic/abc")))
  (is (not (#'webdriver/article-url? "https://example.test/servicedesk/customer/portals"))))

(deftest wait-selectors-are-url-aware
  (let [source-cfg {:render_profile "jira-service-management"
                    :content_wait_selectors ["main article"]}]
    (is (= ["a[href*='/topic/']"]
           (#'webdriver/wait-selectors-for-url source-cfg
                                         "https://example.test/servicedesk/customer/portals")))
    (is (= ["a[href*='/article/']"]
           (#'webdriver/wait-selectors-for-url source-cfg
                                         "https://example.test/servicedesk/customer/portal/7/topic/abc")))
    (is (= ["iframe"]
           (#'webdriver/wait-selectors-for-url source-cfg
                                         "https://example.test/servicedesk/customer/portal/7/article/123")))
    (is (= ["iframe"]
           (#'webdriver/wait-selectors-for-url source-cfg
                                         "https://example.test/servicedesk/customer/portal/7/topic/abc/article/123")))))

(deftest generic-webdriver-article-urls-use-configured-wait-selectors
  (let [source-cfg {:content_wait_selectors ["main article"]}]
    (is (= ["main article"]
           (#'webdriver/wait-selectors-for-url source-cfg
                                               "https://example.test/article/foo")))))

(deftest direct-article-links-keep-topic-context
  (is (= "https://example.test/servicedesk/customer/portal/7/topic/topic-1/article/123"
         (#'webdriver/contextualize-article-url
          "https://example.test/servicedesk/customer/portal/7/topic/topic-1/article/999"
          "https://example.test/servicedesk/customer/portal/7/article/123")))
  (is (= "https://example.test/servicedesk/customer/portal/7/topic/topic-1/article/123"
         (#'webdriver/contextualize-article-url
          "https://example.test/servicedesk/customer/portal/7/topic/topic-1/article/999"
          "https://example.test/plugins/servlet/servicedesk/customer/confluence/shim/spaces/API/pages/123")))
  (is (= "https://example.test/servicedesk/customer/portal/7/topic/topic-1/article/123"
         (#'webdriver/contextualize-article-url
          "https://example.test/servicedesk/customer/portal/7/topic/topic-1/article/999"
          "https://example.test/wiki/spaces/KD/pages/123/Example")))
  (is (= "https://example.test/servicedesk/customer/portal/7/topic/topic-2/article/123"
         (#'webdriver/contextualize-article-url
          "https://example.test/servicedesk/customer/portal/7/topic/topic-1/article/999"
          "https://example.test/servicedesk/customer/portal/7/topic/topic-2/article/123")))
  (is (= "https://example.test/servicedesk/customer/portal/7/article/123"
         (#'webdriver/contextualize-article-url
          "https://example.test/servicedesk/customer/portal/7/article/999"
          "https://example.test/servicedesk/customer/portal/7/article/123"))))

(deftest confluence-links-on-direct-article-pages-become-direct-article-urls
  (is (= "https://example.test/servicedesk/customer/portal/7/article/123"
         (#'webdriver/contextualize-article-url
          "https://example.test/servicedesk/customer/portal/7/article/999"
          "https://example.test/plugins/servlet/servicedesk/customer/confluence/shim/spaces/KD/pages/123/Related")))
  (is (= "https://example.test/servicedesk/customer/portal/7/article/123"
         (#'webdriver/contextualize-article-url
          "https://example.test/servicedesk/customer/portal/7/article/999"
          "https://example.test/wiki/spaces/KD/pages/123/Related"))))

(deftest topic-article-links-also-enqueue-direct-article-urls
  (is (= "https://example.test/servicedesk/customer/portal/7/article/123"
         (#'webdriver/direct-article-url
          "https://example.test/servicedesk/customer/portal/7/topic/topic-1/article/123")))
  (is (nil? (#'webdriver/direct-article-url
             "https://example.test/servicedesk/customer/portal/7/article/123")))
  (is (= ["https://example.test/servicedesk/customer/portal/7/topic/topic-1/article/123"
          "https://example.test/servicedesk/customer/portal/7/article/123"]
         (#'webdriver/expand-article-url-variants
          "https://example.test/servicedesk/customer/portal/7/topic/topic-1/article/123"))))

(deftest direct-article-frame-readiness-waits-for-related-shim-links
  (with-redefs [webdriver/frame-content-state (fn [_]
                                          {:relatedHrefCount 2})]
    (is (#'webdriver/frame-related-links-ready? :driver)))
  (with-redefs [webdriver/frame-content-state (fn [_]
                                          {:relatedHrefCount 1})]
    (is (not (#'webdriver/frame-related-links-ready? :driver)))))

(deftest waits-use-current-url-after-redirect
  (let [selectors (atom nil)]
    (with-redefs-fn {#'e/get-url (fn [_] "https://example.test/servicedesk/customer/portal/7/article/123")
                     #'webdriver/page-ready? (fn [_] true)
                     #'webdriver/any-selector-present? (fn [_ observed-selectors]
                                                   (reset! selectors observed-selectors)
                                                   true)}
      (fn []
      (#'webdriver/wait-for-page!
       :driver
       {:render_profile "jira-service-management"
        :wait_timeout_ms 1
        :wait_interval_ms 1}
       "https://example.test/plugins/servlet/servicedesk/customer/confluence/shim/x/abc")
      (is (= ["iframe"] @selectors))))))

(deftest iframe-content-keeps-outer-url-and-title
  (is (= {:url "https://example.test/portal/topic/a/article/1"
          :title "Outer title"
          :fallback_url nil
          :body "<main>Inner article</main>"
          :hrefs ["https://example.test/outer"
                  "https://example.test/inner"]}
         (#'webdriver/merge-iframe-page
          {:url "https://example.test/portal/topic/a/article/1"
           :title "Outer title"
           :fallback_url nil
           :body ""
           :hrefs ["https://example.test/outer"]}
          {:url "https://example.test/wiki/spaces/kb/pages/1"
           :title "Inner title"
           :body "<main>Inner article</main>"
           :hrefs ["https://example.test/outer"
                   "https://example.test/inner"]}))))
