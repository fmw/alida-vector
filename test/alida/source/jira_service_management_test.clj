(ns alida.source.jira-service-management-test
  (:require [alida.extract.html :as html]
            [alida.source :as source]
            [alida.source.jira-service-management :as jsm]
            [alida.source.webdriver :as webdriver]
            [alida.test-helpers :refer [fake-http]]
            [clojure.data.json :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [etaoin.api :as e]))

(def workspace-id "123e4567-e89b-12d3-a456-426614174000")

(defn- portal-page
  ([]
   (portal-page {:portal-id 1
                 :project-id 42
                 :workspace-id workspace-id
                 :origin "https://example.atlassian.net"
                 :topic-id "topic-a"}))
  ([{:keys [portal-id project-id workspace-id origin topic-id]}]
  (str "<html><body>"
       "<script>window.api = '/gateway/api/jsd-apollo-stargate/sharded/workspace/"
       workspace-id
       "/api/project/" project-id "';</script>"
       "<div id=\"jsonPayload\">"
       (json/write-str {:portal {:id portal-id
                                  :projectId project-id
                                  :categories
                                  {:categories
                                   [{:id topic-id
                                     :categoryUrl (str origin
                                                       "/servicedesk/customer/portal/"
                                                       portal-id
                                                       "/topic/"
                                                       topic-id)}]}}})
       "</div>"
       "</body></html>")))

(defn- category-url
  ([]
   (category-url {:origin "https://example.atlassian.net"
                  :workspace-id workspace-id
                  :project-id 42
                  :topic-id "topic-a"
                  :start 0
                  :limit 100}))
  ([{:keys [origin workspace-id project-id topic-id start limit]}]
   (str origin
        "/gateway/api/jsd-apollo-stargate/sharded/workspace/"
        workspace-id
        "/api/project/"
        project-id
        "/category/"
        topic-id
        "/article?expand=category&limit="
        limit
        "&orderBy=%2Bfeatured&start="
        start)))

(defn- category-response
  []
  (json/write-str {:results [{:id "1001"
                              :title "Article One"
                              :viewUrl "/servicedesk/customer/portal/1/topic/topic-a/article/1001"}
                             {:id "1002"
                              :title "Article Two"
                              :viewUrl "/servicedesk/customer/portal/1/topic/topic-a/article/1002"}]}))

(defn- page-api-url
  ([article-id]
   (page-api-url "https://example.atlassian.net" article-id))
  ([origin article-id]
   (str origin "/wiki/api/v2/pages/" article-id "?body-format=view")))

(defn- page-response
  [article-id title body]
  (json/write-str {:id article-id
                   :title title
                   :body {:view {:representation "view"
                                 :value body}}}))

(def source-cfg
  {:id "support"
   :type "jira-service-management"
   :crawl_method "api"
   :url "https://example.atlassian.net/servicedesk/customer/portal/1"
   :allowed_url_prefixes ["https://example.atlassian.net/servicedesk/customer/portal/1/"]
   :max_pages 10
   :api_max_concurrency 2})

(deftest discovers-articles-through-jsm-api
  (let [requests (atom [])
        sys (fake-http {"https://example.atlassian.net/servicedesk/customer/portal/1"
                        {:status 200 :body (portal-page)}
                        (category-url)
                        {:status 200 :body (category-response)}
                        (page-api-url "1001")
                        {:status 200
                         :headers {"Content-Type" "application/json"}
                         :body (page-response
                                "1001"
                                "Article One from Confluence"
                                "<article><p>Body one.</p><a href=\"/servicedesk/customer/portal/1/article/1003\">Related</a></article>")}
                        (page-api-url "1002")
                        {:status 200
                         :headers {"Content-Type" "application/json"}
                         :body (page-response "1002" "Article Two" "<article><p>Body two.</p></article>")}
                        (page-api-url "1003")
                        {:status 200
                         :headers {"Content-Type" "application/json"}
                         :body (page-response
                                "1003"
                                "Article Three"
                                "<article><p>Body three.</p></article>")}}
                       requests)
        items (source/discover sys source-cfg)]
    (is (= #{"1001" "1002" "1003"} (set (mapv :external_id items))))
    (is (= #{"https://example.atlassian.net/servicedesk/customer/portal/1/topic/topic-a/article/1001"
             "https://example.atlassian.net/servicedesk/customer/portal/1/topic/topic-a/article/1002"
             "https://example.atlassian.net/servicedesk/customer/portal/1/article/1003"}
           (set (mapv :canonical_url items))))
    (is (every? #(= "jira-service-management" (:source_type %)) items))
    (is (= "Article One from Confluence"
           (:title (first (filter #(= "1001" (:external_id %)) items)))))
    (is (some #(str/includes? (:body %) "Body three") items))
    (is (= 5 (count @requests)))))

(defn- rest-view-category-response
  "Mirror the live gateway, which returns a /rest/... viewUrl rather than a
   portal page URL."
  []
  (json/write-str {:results [{:id "1001"
                              :title "Article One"
                              :viewUrl "/rest/servicedesk/knowledgebase/latest/articles/view/1001"}]}))

(deftest api-normalizes-rest-view-urls-to-portal-form
  (let [sys (fake-http {"https://example.atlassian.net/servicedesk/customer/portal/1"
                        {:status 200 :body (portal-page)}
                        (category-url)
                        {:status 200 :body (rest-view-category-response)}
                        (page-api-url "1001")
                        {:status 200
                         :headers {"Content-Type" "application/json"}
                         :body (page-response "1001" "Article One" "<article><p>Body.</p></article>")}}
                       (atom []))
        items (source/discover sys source-cfg)]
    (is (= ["https://example.atlassian.net/servicedesk/customer/portal/1/article/1001"]
           (mapv :canonical_url items))
        "a /rest/ viewUrl must not leak into the canonical URL")))

(deftest api-skips-isolated-articles-that-are-not-found
  (let [requests (atom [])
        article-url (page-api-url "1001")
        sys {:alida/http-request
             (fn [{:keys [url headers] :as request}]
               (swap! requests conj request)
               (cond
                 (= url "https://example.atlassian.net/servicedesk/customer/portal/1")
                 {:status 200 :body (portal-page)}

                 (= url (category-url))
                 {:status 200 :body (category-response)}

                 (and (= url article-url)
                      (= "application/json" (get headers "Accept")))
                 {:status 404
                  :headers {"Content-Type" "application/json"}
                  :body (json/write-str {:httpStatusCode 404
                                         :message "The knowledge base article could not be found."})}

                 (= url (page-api-url "1002"))
                 {:status 200
                  :headers {"Content-Type" "application/json"}
                  :body (page-response "1002"
                                       "Article Two"
                                       "<article><p>Body.</p></article>")}

                 :else
                 {:status 500 :body "unexpected request"}))}
        items (source/discover sys source-cfg)]
    (is (= 2 (count items)))
    (is (= 1 (count (filter source/skipped? items))))
    (is (= :alida.source.jira-service-management/article-not-found
           (get-in (first (filter source/skipped? items)) [:alida/skipped :type])))
    (is (= ["application/json"]
           (->> @requests
                (filter #(= article-url (:url %)))
                (mapv #(get-in % [:headers "Accept"])))))))

(deftest api-reports-when-the-page-api-is-unavailable
  (let [sys (fake-http {"https://example.atlassian.net/servicedesk/customer/portal/1"
                        {:status 200 :body (portal-page)}
                        (category-url)
                        {:status 200 :body (category-response)}
                        (page-api-url "1001")
                        {:status 404 :body "not found"}
                        (page-api-url "1002")
                        {:status 404 :body "not found"}}
                       (atom []))
        items (source/discover sys source-cfg)]
    (is (= 1 (count items)))
    (is (source/anomaly? (first items)))
    (is (= :alida.source.jira-service-management/article-api-unavailable
           (get-in (first items) [:alida/error :type])))
    (is (= 2 (get-in (first items) [:alida/error :article-count])))))

(deftest api-resolves-same-origin-short-links-through-the-portal-shim
  (doseq [[short-path code]
          [["/wiki/x/abc" "abc"]
           ["/plugins/servlet/servicedesk/customer/confluence/shim/x/def" "def"]]]
    (let [requests (atom [])
          shim-url (str "https://example.atlassian.net"
                        "/plugins/servlet/servicedesk/customer/confluence/shim/x/"
                        code)
          sys (fake-http {"https://example.atlassian.net/servicedesk/customer/portal/1"
                          {:status 200 :body (portal-page)}
                          (category-url)
                          {:status 200 :body (rest-view-category-response)}
                          (page-api-url "1001")
                          {:status 200
                           :headers {"Content-Type" "application/json"}
                           :body (page-response
                                  "1001"
                                  "Article One"
                                  (str "<article><p>Body.</p>"
                                       "<a href=\"https://example.atlassian.net"
                                       short-path
                                       "\">Related</a></article>"))}
                          shim-url
                          {:status 302
                           :headers {"Location" "/servicedesk/customer/kb/view/1003"}}
                          (page-api-url "1003")
                          {:status 200
                           :headers {"Content-Type" "application/json"}
                           :body (page-response
                                  "1003"
                                  "Related article"
                                  "<article><p>Related body.</p></article>")}}
                         requests)
          items (source/discover sys source-cfg)]
      (is (= #{"1001" "1003"} (set (mapv :external_id items))))
      (is (= :never
             (:redirect-policy (first (filter #(= shim-url (:url %)) @requests))))))))

(deftest api-keeps-link-text-in-extracted-content-and-discovers-its-article
  (let [sys (fake-http {"https://example.atlassian.net/servicedesk/customer/portal/1"
                        {:status 200 :body (portal-page)}
                        (category-url)
                        {:status 200 :body (rest-view-category-response)}
                        (page-api-url "1001")
                        {:status 200
                         :headers {"Content-Type" "application/json"}
                         :body (page-response
                                "1001"
                                "Article One"
                                (str "<article><p>Body. "
                                     "<a "
                                     "href=\"/servicedesk/customer/portal/1/article/1003\">"
                                     "https://player.example/video/1003</a></p></article>"))}
                        (page-api-url "1003")
                        {:status 200
                         :headers {"Content-Type" "application/json"}
                         :body (page-response
                                "1003"
                                "Embedded article"
                                "<article><p>Embedded body.</p></article>")}}
                       (atom []))
        items (source/discover sys source-cfg)
        first-article (first (filter #(= "1001" (:external_id %)) items))
        extracted (html/extract source-cfg
                                (source/html-extraction-options source-cfg)
                                first-article)]
    (is (= #{"1001" "1003"} (set (mapv :external_id items))))
    (is (str/includes? (:normalized_content extracted)
                       "https://player.example/video/1003"))))

(deftest api-resolves-only-exact-short-link-paths-on-configured-origins
  (let [requests (atom [])
        sys (fake-http {"https://example.atlassian.net/servicedesk/customer/portal/1"
                        {:status 200 :body (portal-page)}
                        (category-url)
                        {:status 200 :body (rest-view-category-response)}
                        (page-api-url "1001")
                        {:status 200
                         :headers {"Content-Type" "application/json"}
                         :body (page-response
                                "1001"
                                "Article One"
                                (str "<article><p>Body.</p>"
                                     "<a href=\"https://other.example/wiki/x/abc\">External wiki</a>"
                                     "<a href=\"https://other.example/plugins/servlet/servicedesk/customer/confluence/shim/x/abc\">External shim</a>"
                                     "<a href=\"http://169.254.169.254/plugins/servlet/servicedesk/customer/confluence/shim/x/pwn\">Link local</a>"
                                     "<a href=\"https://example.atlassian.net/foo/wiki/x/abc\">Deep path</a>"
                                     "<a href=\"https://example.atlassian.net/landing?redirect=/wiki/x/abc\">Query</a>"
                                     "</article>"))}}
                       requests)]
    (is (= ["1001"] (mapv :external_id (source/discover sys source-cfg))))
    (is (not-any? #(or (str/starts-with? (:url %) "https://other.example/")
                       (str/starts-with? (:url %) "http://169.254.169.254/")
                       (str/includes? (:url %) "/confluence/shim/x/"))
                  @requests))))

(deftest api-reports-successful-responses-without-rendered-content
  (let [sys (fake-http {"https://example.atlassian.net/servicedesk/customer/portal/1"
                        {:status 200 :body (portal-page)}
                        (category-url)
                        {:status 200 :body (rest-view-category-response)}
                        (page-api-url "1001")
                        {:status 200
                         :headers {"Content-Type" "application/json"}
                         :body (json/write-str {:id "1001"
                                               :title "Article One"})}}
                       (atom []))
        item (first (source/discover sys source-cfg))]
    (is (source/anomaly? item))
    (is (= :alida.source.jira-service-management/article-content-missing
           (get-in item [:alida/error :type])))))

(deftest api-reports-invalid-json-responses
  (let [sys (fake-http {"https://example.atlassian.net/servicedesk/customer/portal/1"
                        {:status 200 :body (portal-page)}
                        (category-url)
                        {:status 200 :body (rest-view-category-response)}
                        (page-api-url "1001")
                        {:status 200
                         :headers {"Content-Type" "application/json"}
                         :body "not JSON"}}
                       (atom []))
        item (first (source/discover sys source-cfg))]
    (is (source/anomaly? item))
    (is (= :alida.source.jira-service-management/article-response-invalid
           (get-in item [:alida/error :type])))))

(deftest api-fails-loudly-when-start-url-exposes-no-categories
  (let [portals-page (str "<html><body>"
                          "<script>window.api = '/gateway/api/jsd-apollo-stargate/sharded/workspace/"
                          workspace-id "/api/project/42';</script>"
                          "<div id=\"jsonPayload\">"
                          (json/write-str {:portal {:id 1 :projectId 42}})
                          "</div></body></html>")
        sys (fake-http {"https://example.atlassian.net/servicedesk/customer/portals"
                        {:status 200 :body portals-page}}
                       (atom []))
        cfg (assoc source-cfg :url "https://example.atlassian.net/servicedesk/customer/portals")
        thrown (try (source/discover sys cfg) nil
                    (catch clojure.lang.ExceptionInfo e (ex-data e)))]
    (is (= :alida.source.jira-service-management/no-categories (:type thrown)))))

(deftest fetch-returns-api-discovered-body
  (let [item {:source_id "support"
              :source_type "jira-service-management"
              :canonical_url "https://example.atlassian.net/servicedesk/customer/portal/1/article/1001"
              :body "<article>Already fetched</article>"}]
    (is (= (assoc item :content_type "text/html")
           (source/fetch {} source-cfg item)))))

(deftest webdriver-crawl-method-delegates-to-generic-webdriver-source
  (with-redefs [webdriver/discover-rendered (fn [_ cfg]
                                              [(select-keys cfg [:type :url :render_profile :max_concurrency])])]
    (is (= [{:type "webdriver"
             :url (:url source-cfg)
             :render_profile "jira-service-management"
             :max_concurrency 5}]
           (source/discover {} (assoc source-cfg :crawl_method "webdriver"))))
    (is (= [{:type "webdriver"
             :url (:url source-cfg)
             :render_profile "jira-service-management"
             :max_concurrency 2}]
           (source/discover {} (assoc source-cfg
                                      :crawl_method "webdriver"
                                      :max_concurrency 2))))))

(deftest auto-crawl-method-falls-back-to-webdriver-when-api-context-fails
  (with-redefs [webdriver/discover-rendered (fn [_ cfg]
                                              [(select-keys cfg [:type :url])])]
    (let [sys (fake-http {(:url source-cfg) {:status 200 :body "<html>No payload</html>"}}
                         (atom []))]
      (is (= [{:type "webdriver"
               :url (:url source-cfg)}]
             (source/discover sys (assoc source-cfg :crawl_method "auto")))))))

(deftest auto-crawl-method-falls-back-when-the-page-api-is-unavailable
  (with-redefs [webdriver/discover-rendered (fn [_ cfg]
                                              [(select-keys cfg [:type :url])])]
    (let [sys (fake-http {(:url source-cfg) {:status 200 :body (portal-page)}
                          (category-url) {:status 200 :body (rest-view-category-response)}
                          (page-api-url "1001") {:status 404 :body "not found"}}
                         (atom []))]
      (is (= [{:type "webdriver"
               :url (:url source-cfg)}]
             (source/discover sys (assoc source-cfg :crawl_method "auto")))))))

(deftest api-fetches-articles-in-parallel
  (let [active (atom 0)
        max-active (atom 0)
        latch (java.util.concurrent.CountDownLatch. 2)
        sys {:alida/http-request
             (fn [request]
               (cond
                 (= (:url request) (:url source-cfg))
                 {:status 200 :body (portal-page)}

                 (= (:url request) (category-url))
                 {:status 200 :body (category-response)}

                 (str/includes? (:url request) "/wiki/api/v2/pages/")
                 (do
                   (let [current (swap! active inc)]
                     (swap! max-active max current)
                     (.countDown latch)
                     (.await latch 1 java.util.concurrent.TimeUnit/SECONDS)
                     (swap! active dec))
                   {:status 200
                    :headers {"Content-Type" "application/json"}
                    :body (page-response
                           "1000"
                           "Article"
                           "<article><p>Body.</p></article>")})

                 :else
                 {:status 500 :body "unexpected request"}))}]
    (is (= 2 (count (source/discover sys source-cfg))))
    (is (= 2 @max-active))))

(deftest api-paginates-category-article-results
  (let [requests (atom [])
        cfg (assoc source-cfg
                   :api_category_page_limit 2
                   :max_pages 10)
        responses {"https://example.atlassian.net/servicedesk/customer/portal/1"
                   {:status 200 :body (portal-page)}
                   (category-url {:origin "https://example.atlassian.net"
                                  :workspace-id workspace-id
                                  :project-id 42
                                  :topic-id "topic-a"
                                  :start 0
                                  :limit 2})
                   {:status 200
                    :body (json/write-str {:results [{:id "1001" :title "Article One"}
                                                     {:id "1002" :title "Article Two"}]})}
                   (category-url {:origin "https://example.atlassian.net"
                                  :workspace-id workspace-id
                                  :project-id 42
                                  :topic-id "topic-a"
                                  :start 2
                                  :limit 2})
                   {:status 200
                    :body (json/write-str {:results [{:id "1003" :title "Article Three"}]
                                           :isLastPage true})}
                   (page-api-url "1001")
                   {:status 200
                    :headers {"Content-Type" "application/json"}
                    :body (page-response "1001" "One" "<article>One</article>")}
                   (page-api-url "1002")
                   {:status 200
                    :headers {"Content-Type" "application/json"}
                    :body (page-response "1002" "Two" "<article>Two</article>")}
                   (page-api-url "1003")
                   {:status 200
                    :headers {"Content-Type" "application/json"}
                    :body (page-response "1003" "Three" "<article>Three</article>")}}
        items (source/discover (fake-http responses requests) cfg)]
    (is (= #{"1001" "1002" "1003"} (set (mapv :external_id items))))
    (is (= [(category-url {:origin "https://example.atlassian.net"
                          :workspace-id workspace-id
                          :project-id 42
                          :topic-id "topic-a"
                          :start 0
                          :limit 2})
            (category-url {:origin "https://example.atlassian.net"
                           :workspace-id workspace-id
                           :project-id 42
                           :topic-id "topic-a"
                           :start 2
                           :limit 2})]
           (filterv #(str/includes? % "/category/") (mapv :url @requests))))))

(deftest api-builds-separate-contexts-for-multiple-start-urls
  (let [second-workspace "223e4567-e89b-12d3-a456-426614174000"
        second-origin "https://second.example.atlassian.net"
        cfg (assoc source-cfg
                   :url nil
                   :start_urls [(:url source-cfg)
                                (str second-origin "/servicedesk/customer/portal/2")]
                   :allowed_url_prefixes ["https://example.atlassian.net/servicedesk/customer/portal/1/"
                                          (str second-origin "/servicedesk/customer/portal/2/")])
        sys (fake-http {"https://example.atlassian.net/servicedesk/customer/portal/1"
                        {:status 200 :body (portal-page)}
                        (category-url)
                        {:status 200 :body (json/write-str {:results [{:id "1001"}] :isLastPage true})}
                        (page-api-url "1001")
                        {:status 200
                         :headers {"Content-Type" "application/json"}
                         :body (page-response "1001" "One" "<article>One</article>")}
                        (str second-origin "/servicedesk/customer/portal/2")
                        {:status 200
                         :body (portal-page {:portal-id 2
                                             :project-id 84
                                             :workspace-id second-workspace
                                             :origin second-origin
                                             :topic-id "topic-b"})}
                        (category-url {:origin second-origin
                                       :workspace-id second-workspace
                                       :project-id 84
                                       :topic-id "topic-b"
                                       :start 0
                                       :limit 100})
                        {:status 200 :body (json/write-str {:results [{:id "2001"}] :isLastPage true})}
                        (page-api-url second-origin "2001")
                        {:status 200
                         :headers {"Content-Type" "application/json"}
                         :body (page-response "2001" "Two" "<article>Two</article>")}}
                       (atom []))
        items (source/discover sys cfg)]
    (is (= #{"https://example.atlassian.net/servicedesk/customer/portal/1/article/1001"
             "https://second.example.atlassian.net/servicedesk/customer/portal/2/article/2001"}
           (set (mapv :canonical_url items))))))

;; ---- Rendered (WebDriver) crawl: render profile ----

(def ^:private jsm-profile (webdriver/render-profile "jira-service-management"))

(deftest jsm-profile-supplies-expected-hooks
  (is (= #{:content-wait-selectors :remove-selectors :wait-selectors
           :transform-hrefs :extras-js :blank-fallback-url
           :await-extra-frame-content!}
         (set (keys jsm-profile))))
  (is (string? (:extras-js jsm-profile))))

(deftest jsm-wait-selectors-are-url-aware
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
                                               "https://example.test/servicedesk/customer/portal/7/topic/abc/article/123")))
    ;; non-special URLs fall back to the configured content-wait selectors
    (is (= ["main article"]
           (#'webdriver/wait-selectors-for-url source-cfg
                                               "https://example.test/servicedesk/customer/portal/7")))))

(deftest jsm-waits-use-current-url-after-redirect
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

(deftest jsm-direct-article-links-keep-topic-context
  (is (= "https://example.test/servicedesk/customer/portal/7/topic/topic-1/article/123"
         (#'jsm/contextualize-article-url
          "https://example.test/servicedesk/customer/portal/7/topic/topic-1/article/999"
          "https://example.test/servicedesk/customer/portal/7/article/123")))
  (is (= "https://example.test/servicedesk/customer/portal/7/topic/topic-1/article/123"
         (#'jsm/contextualize-article-url
          "https://example.test/servicedesk/customer/portal/7/topic/topic-1/article/999"
          "https://example.test/plugins/servlet/servicedesk/customer/confluence/shim/spaces/API/pages/123")))
  (is (= "https://example.test/servicedesk/customer/portal/7/topic/topic-1/article/123"
         (#'jsm/contextualize-article-url
          "https://example.test/servicedesk/customer/portal/7/topic/topic-1/article/999"
          "https://example.test/wiki/spaces/KD/pages/123/Example")))
  (is (= "https://example.test/servicedesk/customer/portal/7/topic/topic-2/article/123"
         (#'jsm/contextualize-article-url
          "https://example.test/servicedesk/customer/portal/7/topic/topic-1/article/999"
          "https://example.test/servicedesk/customer/portal/7/topic/topic-2/article/123")))
  (is (= "https://example.test/servicedesk/customer/portal/7/article/123"
         (#'jsm/contextualize-article-url
          "https://example.test/servicedesk/customer/portal/7/article/999"
          "https://example.test/servicedesk/customer/portal/7/article/123"))))

(deftest jsm-confluence-links-on-direct-article-pages-become-direct-article-urls
  (is (= "https://example.test/servicedesk/customer/portal/7/article/123"
         (#'jsm/contextualize-article-url
          "https://example.test/servicedesk/customer/portal/7/article/999"
          "https://example.test/plugins/servlet/servicedesk/customer/confluence/shim/spaces/KD/pages/123/Related")))
  (is (= "https://example.test/servicedesk/customer/portal/7/article/123"
         (#'jsm/contextualize-article-url
          "https://example.test/servicedesk/customer/portal/7/article/999"
          "https://example.test/wiki/spaces/KD/pages/123/Related"))))

(deftest jsm-topic-article-links-also-enqueue-direct-article-urls
  (is (= "https://example.test/servicedesk/customer/portal/7/article/123"
         (#'jsm/direct-article-url
          "https://example.test/servicedesk/customer/portal/7/topic/topic-1/article/123")))
  (is (nil? (#'jsm/direct-article-url
             "https://example.test/servicedesk/customer/portal/7/article/123")))
  (is (= ["https://example.test/servicedesk/customer/portal/7/topic/topic-1/article/123"
          "https://example.test/servicedesk/customer/portal/7/article/123"]
         (#'jsm/expand-article-url-variants
          "https://example.test/servicedesk/customer/portal/7/topic/topic-1/article/123"))))

(deftest jsm-transform-hrefs-contextualizes-and-expands
  (is (= ["https://example.test/servicedesk/customer/portal/7/topic/topic-1/article/123"
          "https://example.test/servicedesk/customer/portal/7/article/123"]
         ((:transform-hrefs jsm-profile)
          "https://example.test/servicedesk/customer/portal/7/topic/topic-1/article/999"
          ["https://example.test/servicedesk/customer/portal/7/article/123"]))))

(deftest jsm-blank-fallback-url-only-for-blank-articles
  (let [fallback (:blank-fallback-url jsm-profile)
        article "https://example.test/servicedesk/customer/portal/7/article/1"]
    ;; blank article with a fallback url -> retry there
    (is (= "https://example.test/kb"
           (fallback {} article {:fallback_url "https://example.test/kb" :body "<main></main>"})))
    ;; non-blank article -> no retry
    (is (nil? (fallback {} article {:fallback_url "https://example.test/kb"
                                    :body "<main>Real content</main>"})))
    ;; no fallback url available -> no retry
    (is (nil? (fallback {} article {:fallback_url nil :body "<main></main>"})))
    ;; non-article page -> no retry even if blank
    (is (nil? (fallback {} "https://example.test/servicedesk/customer/portals"
                        {:fallback_url "https://example.test/kb" :body "<main></main>"})))))

(deftest jsm-related-links-wait-stops-once-related-links-appear
  (let [counts (atom [0 1 2 7 9])
        samples (atom 0)]
    (with-redefs-fn {#'jsm/frame-related-links-count
                     (fn [_]
                       (swap! samples inc)
                       (let [[n & more] @counts]
                         (when more (reset! counts more))
                         n))}
      (fn []
        (#'jsm/wait-for-related-links! :driver {:wait_interval_ms 1})
        ;; stops at the first sample greater than one (0, 1, 2)
        (is (= 3 @samples))))))

(deftest jsm-related-links-wait-stops-when-count-is-stable
  (let [samples (atom 0)]
    (with-redefs-fn {#'jsm/frame-related-links-count
                     (fn [_]
                       (swap! samples inc)
                       0)}
      (fn []
        (#'jsm/wait-for-related-links! :driver {:wait_interval_ms 1
                                                :iframe_related_links_timeout_ms 60000})
        ;; an unchanged count ends the wait after a few samples instead of
        ;; sitting out the full timeout
        (is (= 4 @samples))))))

(deftest jsm-await-extra-frame-content-only-for-direct-articles
  (let [awaited (atom [])]
    (with-redefs-fn {#'jsm/wait-for-related-links! (fn [_ _] (swap! awaited conj :waited))}
      (fn []
        ((:await-extra-frame-content! jsm-profile) :driver {}
         "https://example.test/servicedesk/customer/portal/7/article/123")
        ((:await-extra-frame-content! jsm-profile) :driver {}
         "https://example.test/servicedesk/customer/portal/7/topic/abc/article/123")
        ;; only the direct (non-topic) article triggers the related-links wait
        (is (= [:waited] @awaited))))))
