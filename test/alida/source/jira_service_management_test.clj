(ns alida.source.jira-service-management-test
  (:require [alida.source :as source]
            [alida.source.jira-service-management]
            [alida.source.webdriver :as webdriver]
            [alida.test-helpers :refer [fake-http]]
            [clojure.data.json :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]))

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
                        "https://example.atlassian.net/rest/servicedesk/knowledgebase/latest/articles/view/1001"
                        {:status 200
                         :headers {"Content-Type" "text/html"}
                         :body "<article><h1>Article One</h1><p>Body one.</p><a href=\"/servicedesk/customer/portal/1/article/1003\">Related</a></article>"}
                        "https://example.atlassian.net/rest/servicedesk/knowledgebase/latest/articles/view/1002"
                        {:status 200
                         :headers {"Content-Type" "text/html"}
                         :body "<article><h1>Article Two</h1><p>Body two.</p></article>"}
                        "https://example.atlassian.net/rest/servicedesk/knowledgebase/latest/articles/view/1003"
                        {:status 200
                         :headers {"Content-Type" "text/html"}
                         :body "<article><h1>Article Three</h1><p>Body three.</p></article>"}}
                       requests)
        items (source/discover sys source-cfg)]
    (is (= #{"1001" "1002" "1003"} (set (mapv :external_id items))))
    (is (= #{"https://example.atlassian.net/servicedesk/customer/portal/1/topic/topic-a/article/1001"
             "https://example.atlassian.net/servicedesk/customer/portal/1/topic/topic-a/article/1002"
             "https://example.atlassian.net/servicedesk/customer/portal/1/article/1003"}
           (set (mapv :canonical_url items))))
    (is (every? #(= "jira-service-management" (:source_type %)) items))
    (is (some #(str/includes? (:body %) "Article Three") items))
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
                        "https://example.atlassian.net/rest/servicedesk/knowledgebase/latest/articles/view/1001"
                        {:status 200
                         :headers {"Content-Type" "text/html"}
                         :body "<article><h1>Article One</h1><p>Body.</p></article>"}}
                       (atom []))
        items (source/discover sys source-cfg)]
    (is (= ["https://example.atlassian.net/servicedesk/customer/portal/1/article/1001"]
           (mapv :canonical_url items))
        "a /rest/ viewUrl must not leak into the canonical URL")))

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
                                              [(select-keys cfg [:type :url])])]
    (is (= [{:type "webdriver"
             :url (:url source-cfg)}]
           (source/discover {} (assoc source-cfg :crawl_method "webdriver"))))))

(deftest auto-crawl-method-falls-back-to-webdriver-when-api-context-fails
  (with-redefs [webdriver/discover-rendered (fn [_ cfg]
                                              [(select-keys cfg [:type :url])])]
    (let [sys (fake-http {(:url source-cfg) {:status 200 :body "<html>No payload</html>"}}
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

                 (str/includes? (:url request) "/rest/servicedesk/knowledgebase/latest/articles/view/")
                 (do
                   (let [current (swap! active inc)]
                     (swap! max-active max current)
                     (.countDown latch)
                     (.await latch 1 java.util.concurrent.TimeUnit/SECONDS)
                     (swap! active dec))
                   {:status 200
                    :headers {"Content-Type" "text/html"}
                    :body "<article><h1>Article</h1><p>Body.</p></article>"})

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
                   "https://example.atlassian.net/rest/servicedesk/knowledgebase/latest/articles/view/1001"
                   {:status 200 :headers {"Content-Type" "text/html"} :body "<article>One</article>"}
                   "https://example.atlassian.net/rest/servicedesk/knowledgebase/latest/articles/view/1002"
                   {:status 200 :headers {"Content-Type" "text/html"} :body "<article>Two</article>"}
                   "https://example.atlassian.net/rest/servicedesk/knowledgebase/latest/articles/view/1003"
                   {:status 200 :headers {"Content-Type" "text/html"} :body "<article>Three</article>"}}
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
                        "https://example.atlassian.net/rest/servicedesk/knowledgebase/latest/articles/view/1001"
                        {:status 200 :headers {"Content-Type" "text/html"} :body "<article>One</article>"}
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
                        (str second-origin "/rest/servicedesk/knowledgebase/latest/articles/view/2001")
                        {:status 200 :headers {"Content-Type" "text/html"} :body "<article>Two</article>"}}
                       (atom []))
        items (source/discover sys cfg)]
    (is (= #{"https://example.atlassian.net/servicedesk/customer/portal/1/article/1001"
             "https://second.example.atlassian.net/servicedesk/customer/portal/2/article/2001"}
           (set (mapv :canonical_url items))))))
