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

(deftest navigable?-enforces-host-allowlist-and-scope-rules
  (let [navigable? #'webdriver/navigable?
        source-cfg {:id "support"
                    :type "webdriver"
                    :url "https://example.test/portal"
                    :allowed_url_prefixes ["https://example.test/portal/"]
                    :denied_url_prefixes ["https://example.test/portal/private/"]
                    :denied_urls ["https://example.test/portal/secret"]
                    :internal_link_hosts ["example.test"]}]
    ;; in-scope, trusted host
    (is (navigable? source-cfg "https://example.test/portal/article/1"))
    ;; off-host (SSRF target) blocked even though no prefix rule would match
    (is (not (navigable? source-cfg "http://169.254.169.254/latest/meta-data")))
    ;; same host but outside allowed_url_prefixes
    (is (not (navigable? source-cfg "https://example.test/admin/console")))
    ;; same host, in allowed prefix, but explicitly denied
    (is (not (navigable? source-cfg "https://example.test/portal/private/x")))
    (is (not (navigable? source-cfg "https://example.test/portal/secret")))
    (is (not (navigable? source-cfg nil)))))

(deftest chromium-sandbox-is-disabled-only-when-explicitly-configured
  (let [browser-args #'webdriver/browser-args]
    (with-redefs-fn {#'webdriver/browser-sandbox-disabled? (constantly false)}
      #(is (not (some #{"--no-sandbox"} (browser-args {})))))
    (with-redefs-fn {#'webdriver/browser-sandbox-disabled? (constantly true)}
      #(is (some #{"--no-sandbox"} (browser-args {}))))
    (with-redefs-fn {#'webdriver/browser-sandbox-disabled? (constantly false)}
      #(is (some #{"--no-sandbox"}
                 (browser-args {:browser_args ["--no-sandbox"]}))))))

(deftest chromium-runtime-directories-are-writable-and-isolated
  (let [first-driver (#'webdriver/runtime-directory-args)
        second-driver (#'webdriver/runtime-directory-args)]
    (doseq [args [first-driver second-driver]]
      (is (some #(re-matches #"--disk-cache-dir=/tmp/alida-vector/chrome-cache-\d+" %)
                args))
      (is (some #(re-matches #"--user-data-dir=/tmp/alida-vector/chrome-profile-\d+" %)
                args)))
    (is (not= first-driver second-driver))))

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
                      :render_failure_retries 0
                      :browser_restart_after_failures 2})]
          (is (= 3 (count items)))
          (is (= [:driver-1 :driver-2] @quits)))))))

(deftest discovery-requeues-failed-renders-until-one-succeeds
  (let [starts (atom 0)
        calls (atom [])]
    (with-redefs-fn {#'webdriver/start-driver! (fn [_]
                                                 (keyword (str "driver-" (swap! starts inc))))
                     #'webdriver/quit-driver! (fn [_])
                     #'webdriver/render-page! (fn [driver _ url]
                                                (swap! calls conj [driver url])
                                                (if (= driver :driver-1)
                                                  (source/anomaly :cognitect.anomalies/fault
                                                                  {:type :test/render-failed
                                                                   :canonical-url url})
                                                  (page url [])))}
      (fn []
        (let [items (source/discover
                     {}
                     {:id "support"
                      :type "webdriver"
                      :url "https://example.test/portal"})]
          (is (= 1 (count items)))
          (is (not (source/anomaly? (first items))))
          ;; two failures on the first browser (the second triggers the
          ;; consecutive-failure restart), then success on the fresh one
          (is (= [[:driver-1 "https://example.test/portal"]
                  [:driver-1 "https://example.test/portal"]
                  [:driver-2 "https://example.test/portal"]]
                 @calls)))))))

(deftest parallel-discovery-requeues-failed-renders
  (let [calls (atom 0)]
    (with-redefs-fn {#'webdriver/start-driver! (fn [_] :driver)
                     #'webdriver/quit-driver! (fn [_])
                     #'webdriver/render-page! (fn [_ _ url]
                                                (if (= 1 (swap! calls inc))
                                                  (source/anomaly :cognitect.anomalies/fault
                                                                  {:type :test/render-failed
                                                                   :canonical-url url})
                                                  (page url [])))}
      (fn []
        (let [items (source/discover
                     {}
                     {:id "support"
                      :type "webdriver"
                      :url "https://example.test/portal"
                      :max_concurrency 2
                      :browser_restart_after_failures 0})]
          (is (= 1 (count items)))
          (is (not (source/anomaly? (first items))))
          (is (= 2 @calls)))))))

(deftest render-failure-retries-can-be-disabled
  (let [calls (atom 0)]
    (with-redefs-fn {#'webdriver/start-driver! (fn [_] :driver)
                     #'webdriver/quit-driver! (fn [_])
                     #'webdriver/render-page! (fn [_ _ url]
                                                (swap! calls inc)
                                                (source/anomaly :cognitect.anomalies/fault
                                                                {:type :test/render-failed
                                                                 :canonical-url url}))}
      (fn []
        (let [items (source/discover
                     {}
                     {:id "support"
                      :type "webdriver"
                      :url "https://example.test/portal"
                      :render_failure_retries 0})]
          (is (= 1 (count items)))
          (is (source/anomaly? (first items)))
          (is (= 1 @calls)))))))

(deftest discovery-reretries-blank-render-in-the-same-browser
  ;; A blank render is retried once in place; the browser is NOT restarted,
  ;; because a blank is content that never materialized rather than a corrupt
  ;; browser session, and a fresh browser reproduces it identically.
  (let [starts (atom 0)
        quits (atom [])
        calls (atom [])]
    (with-redefs-fn {#'webdriver/start-driver! (fn [_]
                                           (keyword (str "driver-" (swap! starts inc))))
                     #'webdriver/quit-driver! (fn [driver]
                                          (swap! quits conj driver))
                     #'webdriver/render-page! (fn [driver _ url]
                                          (swap! calls conj [driver url])
                                          (if (= 1 (count @calls))
                                            (assoc (page url []) :body "<body></body>")
                                            (page url [])))}
      (fn []
        (let [items (source/discover
                     {}
                     {:id "support"
                      :type "webdriver"
                      :url "https://example.test/portal"})]
          (is (= 1 (count items)))
          (is (= "<main><h1>https://example.test/portal</h1><p>Rendered article.</p></main>"
                 (:body (first items))))
          ;; both renders ran on the original browser — no restart
          (is (= [[:driver-1 "https://example.test/portal"]
                  [:driver-1 "https://example.test/portal"]]
                 @calls))
          (is (= 1 @starts)))))))

(deftest discovery-reretries-blank-render-at-canonical-url
  (let [starts (atom 0)
        calls (atom [])]
    (with-redefs-fn {#'webdriver/start-driver! (fn [_]
                                           (keyword (str "driver-" (swap! starts inc))))
                     #'webdriver/quit-driver! (fn [_])
                     #'webdriver/render-page! (fn [driver _ url]
                                          (swap! calls conj [driver url])
                                          (if (= 1 (count @calls))
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
          ;; the in-place retry re-renders at the canonical URL, same browser
          (is (= [[:driver-1 "https://example.test/shim"]
                  [:driver-1 "https://example.test/canonical"]]
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

(deftest parallel-discovery-retries-when-active-render-occupies-page-limit
  (let [calls (atom 0)]
    (with-redefs-fn {#'webdriver/start-driver! (fn [_] :driver)
                     #'webdriver/quit-driver! (fn [_])
                     #'webdriver/render-page! (fn [_ _ url]
                                                (if (= 1 (swap! calls inc))
                                                  (source/anomaly :cognitect.anomalies/fault
                                                                  {:type :test/render-failed
                                                                   :canonical-url url})
                                                  (page url [])))}
      (fn []
        (let [items (source/discover
                     {}
                     {:id "support"
                      :type "webdriver"
                      :url "https://example.test/portal"
                      :max_concurrency 2
                      :max_pages 1
                      :browser_restart_after_failures 0})]
          (is (= 1 (count items)))
          (is (not (source/anomaly? (first items))))
          (is (= 2 @calls)))))))

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

(deftest generic-profile-falls-back-to-configured-wait-selectors
  (let [source-cfg {:content_wait_selectors ["main article"]}]
    (is (= ["main article"]
           (#'webdriver/wait-selectors-for-url source-cfg
                                               "https://example.test/article/foo")))))

(deftest generic-profile-has-no-default-wait-selectors
  ;; with no render_profile and no configured selectors there are no content
  ;; selectors to await; the engine falls back to a body-text readiness check
  ;; (see generic-page-readiness-waits-for-body-text) rather than treating the
  ;; always-present <body> as ready
  (is (nil? (#'webdriver/wait-selectors-for-url {} "https://example.test/anything"))))

(deftest generic-page-readiness-waits-for-body-text
  (let [selector-checked (atom false)
        text-checked (atom false)]
    (with-redefs-fn {#'e/get-url (fn [_] "https://example.test/page")
                     #'webdriver/page-ready? (fn [_] true)
                     #'webdriver/any-selector-present? (fn [_ _] (reset! selector-checked true) true)
                     #'webdriver/frame-text-present? (fn [_] (reset! text-checked true) true)}
      (fn []
        (#'webdriver/wait-for-page! :driver
                                    {:wait_timeout_ms 1 :wait_interval_ms 1}
                                    "https://example.test/page")
        ;; readiness came from body text, not from the always-present <body>
        (is @text-checked)
        (is (not @selector-checked))))))

(deftest configured-wait-selectors-take-precedence-over-body-text
  (let [selector-checked (atom false)
        text-checked (atom false)]
    (with-redefs-fn {#'e/get-url (fn [_] "https://example.test/page")
                     #'webdriver/page-ready? (fn [_] true)
                     #'webdriver/any-selector-present? (fn [_ _] (reset! selector-checked true) true)
                     #'webdriver/frame-text-present? (fn [_] (reset! text-checked true) true)}
      (fn []
        (#'webdriver/wait-for-page! :driver
                                    {:content_wait_selectors [".article"]
                                     :wait_timeout_ms 1 :wait_interval_ms 1}
                                    "https://example.test/page")
        ;; an explicit selector is awaited strictly; body text is not consulted
        (is @selector-checked)
        (is (not @text-checked))))))

(deftest generic-profile-does-not-rewrite-discovered-links
  ;; the generic profile passes discovered links through untouched (no
  ;; site-specific URL reshaping or variant expansion)
  (let [transform (:transform-hrefs (webdriver/render-profile nil))]
    (is (= ["https://example.test/a" "https://example.test/b"]
           (transform "https://example.test/ctx"
                      ["https://example.test/a" "https://example.test/b"])))))

(deftest generic-profile-has-no-blank-fallback-or-extra-frame-wait
  (let [generic (webdriver/render-profile nil)]
    (is (nil? (:extras-js generic)))
    (is (nil? ((:blank-fallback-url generic) {} "https://example.test/x" {:body ""})))
    (is (nil? ((:await-extra-frame-content! generic) :driver {} "https://example.test/x")))))

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
