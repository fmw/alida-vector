(ns alida.source.jira-service-management
  (:require [alida.source :as source]
            [alida.source.webdriver :as webdriver]
            [alida.url :as url]
            [clojure.data.json :as json]
            [clojure.string :as str]
            [com.climate.claypoole :as cp])
  (:import [org.jsoup Jsoup]))

(def default-max-pages 1000)
(def default-max-concurrency 20)
(def default-webdriver-max-concurrency 5)
(def default-category-page-limit 100)

;; The article view endpoint negotiates representation on Accept: without it the
;; gateway returns text/plain (rejected by extraction). Request HTML explicitly.
(def article-accept-header "text/html")

(defn- source-urls
  [source-cfg]
  (or (seq (:start_urls source-cfg))
      (when-let [url (:start_url source-cfg)] [url])
      (when-let [url (:url source-cfg)] [url])))

(defn- max-pages
  [source-cfg]
  (or (:max_pages source-cfg) default-max-pages))

(defn- max-concurrency
  [source-cfg]
  (or (:api_max_concurrency source-cfg)
      (:max_concurrency source-cfg)
      default-max-concurrency))

(defn- crawl-method
  [source-cfg]
  (keyword (or (:crawl_method source-cfg) "api")))

(defn- allowed-url?
  [source-cfg url]
  (url/allowed? (url/source-allow-config source-cfg (seq (:allowed_url_prefixes source-cfg)))
                url))

(defn- request-success
  [sys request context]
  (source/require-success! (source/request! sys request) context))

(defn- read-json
  [body]
  (json/read-str (or body "{}") :key-fn keyword))

(defn- payload-json
  [body]
  (let [document (Jsoup/parse (or body "") "")
        element (.selectFirst document "#jsonPayload")]
    (when element
      (read-json (.text element)))))

(defn- workspace-id
  [body payload]
  (or (some->> (str body)
               (re-find #"workspace/([0-9a-fA-F-]{36})")
               second)
      (get-in payload [:portal :workspaceId])))

(defn- project-id
  [payload]
  (or (get-in payload [:portal :projectId])
      (get-in payload [:portal :project :id])
      (get-in payload [:portal :project :projectId])))

(defn- category-id
  [category]
  (or (:id category)
      (url/path-id "topic" (:categoryUrl category))
      (url/path-id "topic" (:url category))))

(defn- categories
  [payload]
  (or (seq (get-in payload [:portal :categories :categories]))
      []))

(defn- portal-context
  [sys source-cfg start-url]
  (let [response (request-success sys
                                  {:method :get :url start-url}
                                  {:source-id (:id source-cfg)
                                   :url start-url})
        payload (payload-json (:body response))]
    (when-not payload
      (throw (ex-info "Jira Service Management page did not expose jsonPayload"
                      {:type :alida.source.jira-service-management/missing-json-payload
                       :source-id (:id source-cfg)
                       :url start-url})))
    (let [ctx {:origin (url/origin start-url)
               :portal-id (or (url/path-id "portal" start-url)
                              (some-> (get-in payload [:portal :id]) str))
               :workspace-id (workspace-id (:body response) payload)
               :project-id (some-> (project-id payload) str)
               :categories (categories payload)}]
      ;; A start URL pointing at the portals home (or any page without a portal
      ;; payload) yields no categories and would otherwise discover 0 articles
      ;; silently. Fail loudly with the likely fix rather than index nothing.
      (when (and (empty? (:categories ctx))
                 (not (url/article-id start-url)))
        (throw (ex-info (str "Jira Service Management start URL exposed no knowledge-base "
                             "categories. Point the source at a specific portal, e.g. "
                             (:origin ctx) "/servicedesk/customer/portal/<id>")
                        {:type :alida.source.jira-service-management/no-categories
                         :source-id (:id source-cfg)
                         :url start-url})))
      ctx)))

(defn- article-url
  [{:keys [origin portal-id]} article-id]
  (str origin "/servicedesk/customer/portal/" portal-id "/article/" article-id))

(defn- article-api-url
  [{:keys [origin]} article-id]
  (str origin "/rest/servicedesk/knowledgebase/latest/articles/view/" article-id))

(defn- category-page-limit
  [source-cfg]
  (or (:api_category_page_limit source-cfg)
      default-category-page-limit))

(defn- category-api-url
  [{:keys [origin workspace-id project-id]} category-id start limit]
  (str origin
       "/gateway/api/jsd-apollo-stargate/sharded/workspace/" workspace-id
       "/api/project/" project-id
       "/category/" category-id
       "/article?expand=category&limit=" limit
       "&orderBy=%2Bfeatured&start=" start))

(defn- portal-view-url
  "A viewUrl is usable as a canonical URL only when it is a portal-shaped page
   URL. The live gateway returns a /rest/... endpoint here, which must not become
   the canonical URL (it produces mixed URL shapes across discovery and fails the
   portal-shaped allow-prefix filter), so fall back to the portal article URL."
  [ctx view-url]
  (some->> view-url
           (url/normalize (:origin ctx))
           (#(when (str/includes? % "/servicedesk/customer/portal/") %))))

(defn- article-ref
  [ctx result]
  (let [id (some-> (:id result) str)]
    (when (seq id)
      {:article_id id
       :title (:title result)
       :canonical_url (or (portal-view-url ctx (:viewUrl result))
                          (article-url ctx id))})))

(defn- last-category-page?
  [body refs limit]
  (or (true? (:isLastPage body))
      (true? (:last body))
      (false? (:hasMore body))
      (< (count refs) limit)))

(defn- category-article-page
  [sys source-cfg ctx category-id start limit]
  (let [url (category-api-url ctx category-id start limit)
        response (request-success
                  sys
                  {:method :get
                   :url url
                   :headers {"Accept" "application/json, text/plain, */*"
                             "Content-Type" "application/json"}}
                  {:source-id (:id source-cfg)
                   :category-id category-id
                   :start start
                   :limit limit
                   :url url})
        body (read-json (:body response))
        refs (vec (keep #(article-ref ctx %) (:results body)))]
    {:refs refs
     :last? (last-category-page? body refs limit)}))

(defn- category-article-refs
  [sys source-cfg ctx category remaining]
  (let [id (category-id category)]
    (when (seq id)
      (let [limit (category-page-limit source-cfg)]
        (loop [start 0
               refs []
               seen-ids #{}]
          (if (>= (count refs) remaining)
            refs
            (let [{page-refs :refs last? :last?} (category-article-page sys source-cfg ctx id start limit)
                  new-refs (remove #(contains? seen-ids (:article_id %)) page-refs)
                  refs (into refs (take (- remaining (count refs)) new-refs))
                  seen-ids (into seen-ids (map :article_id page-refs))]
              (if (or last?
                      (empty? page-refs)
                      (empty? new-refs))
                refs
                (recur (+ start (count page-refs)) refs seen-ids)))))))))

(defn- seed-article-refs
  [sys source-cfg ctx start-url remaining]
  (let [category-refs (mapcat #(category-article-refs sys source-cfg ctx % remaining)
                              (:categories ctx))
        start-refs (keep (fn [u]
                           (when-let [id (url/article-id u)]
                             {:article_id id
                              :canonical_url (article-url ctx id)}))
                         [start-url])]
    (->> (concat start-refs category-refs)
         (filter #(allowed-url? source-cfg (:canonical_url %)))
         distinct
         vec)))

(defn- resolve-shim-link
  [sys _source-cfg url]
  (try
    (let [response (source/request! sys {:method :get
                                         :url url
                                         :redirect-policy :never})
          location (source/header response "Location")]
      (some->> location (alida.url/normalize url) alida.url/article-id))
    (catch Exception _
      nil)))

(defn- href-article-id
  [sys source-cfg href]
  (or (url/article-id href)
      (when (str/includes? href "/plugins/servlet/servicedesk/customer/confluence/shim/x/")
        (resolve-shim-link sys source-cfg href))))

(defn- article-links
  [sys source-cfg base-url body]
  (let [document (Jsoup/parse (or body "") base-url)]
    (->> (.select document "a[href]")
         (map #(.absUrl % "href"))
         (keep #(href-article-id sys source-cfg %))
         distinct
         vec)))

(defn- article-title
  [fallback-title body]
  (or fallback-title
      (let [document (Jsoup/parse (or body "") "")
            heading (.selectFirst document "h1")]
        (some-> heading .text str/trim not-empty))))

(defn- fetch-article
  [sys source-cfg ctx ref]
  (let [id (:article_id ref)
        url (article-api-url ctx id)
        canonical-url (or (:canonical_url ref) (article-url ctx id))
        response (source/request! sys {:method :get :url url
                                       :headers {"Accept" article-accept-header}})]
    (if (source/successful-status? (:status response))
      {:source_id (:id source-cfg)
       :source_type (:type source-cfg)
       :external_id id
       :canonical_url canonical-url
       :content_type (or (source/header response "Content-Type") "text/html")
       :title (article-title (:title ref) (:body response))
       :body (:body response)
       :hrefs (mapv #(article-url ctx %)
                    (article-links sys source-cfg canonical-url (:body response)))}
      (source/fetch-anomaly response
                            {:type :alida.source.jira-service-management/article-fetch-failed
                             :source-id (:id source-cfg)
                             :canonical-url canonical-url
                             :article-id id}))))

(defn- enqueue-refs
  [source-cfg ctx queued refs page]
  (reduce (fn [[queue queued] href]
            (if-let [id (and (allowed-url? source-cfg href)
                             (url/article-id href))]
              (if (contains? queued id)
                [queue queued]
                [(conj queue {:article_id id
                              :canonical_url (article-url ctx id)})
                 (conj queued id)])
              [queue queued]))
          [refs queued]
          (:hrefs page)))

(defn- fetch-batch
  [pool sys source-cfg ctx refs]
  (if pool
    (vec (cp/upmap pool #(fetch-article sys source-cfg ctx %) refs))
    (mapv #(fetch-article sys source-cfg ctx %) refs)))

(defn- discover-api-start
  "BFS the portal's article graph. The thread pool (when concurrency > 1) is
   created once for the whole traversal rather than per batch."
  [pool sys source-cfg start-url remaining-pages]
  (let [ctx (portal-context sys source-cfg start-url)
        seeds (seed-article-refs sys source-cfg ctx start-url remaining-pages)]
    (loop [queue (into clojure.lang.PersistentQueue/EMPTY seeds)
           queued (set (map :article_id seeds))
           pages []]
      (if (or (empty? queue)
              (>= (count pages) remaining-pages))
        pages
        (let [remaining (- remaining-pages (count pages))
              batch-size (min remaining (max 1 (max-concurrency source-cfg)) (count queue))
              batch (vec (take batch-size queue))
              queue (into clojure.lang.PersistentQueue/EMPTY (drop batch-size queue))
              fetched (fetch-batch pool sys source-cfg ctx batch)
              pages (into pages fetched)
              [queue queued] (reduce (fn [[queue queued] page]
                                       (if (source/anomaly? page)
                                         [queue queued]
                                         (enqueue-refs source-cfg ctx queued queue page)))
                                     [queue queued]
                                     fetched)]
          (recur queue queued pages))))))

(defn- discover-api*
  [pool sys source-cfg]
  (let [starts (source-urls source-cfg)]
    (when-not (seq starts)
      (throw (ex-info "Jira Service Management source requires url, start_url, or start_urls"
                      {:type :alida.source.jira-service-management/missing-url
                       :source-id (:id source-cfg)})))
    (loop [starts starts
           pages []]
      (if (or (empty? starts)
              (>= (count pages) (max-pages source-cfg)))
        pages
        (let [remaining (- (max-pages source-cfg) (count pages))]
          (recur (rest starts)
                 (into pages (discover-api-start pool sys source-cfg (first starts) remaining))))))))

(defn- discover-api
  [sys source-cfg]
  (let [concurrency (max 1 (max-concurrency source-cfg))]
    (if (> concurrency 1)
      (cp/with-shutdown! [pool (cp/threadpool concurrency :name "alida-jsm-api")]
        (discover-api* pool sys source-cfg))
      (discover-api* nil sys source-cfg))))

(defn- webdriver-source-cfg
  "Delegate config for the rendered crawl. Defaults to multiple parallel
   browsers because a single browser makes a portal crawl take the better part
   of an hour; an explicit max_concurrency still wins."
  [source-cfg]
  (-> source-cfg
      (assoc :type "webdriver"
             :render_profile "jira-service-management")
      (update :max_concurrency #(or % default-webdriver-max-concurrency))))

(defmethod source/discover :jira-service-management
  [sys source-cfg]
  (case (crawl-method source-cfg)
    :webdriver (webdriver/discover-rendered sys (webdriver-source-cfg source-cfg))
    :auto (try
            (discover-api sys source-cfg)
            (catch Exception _
              (webdriver/discover-rendered sys (webdriver-source-cfg source-cfg))))
    :api (discover-api sys source-cfg)
    (discover-api sys source-cfg)))

(defmethod source/fetch :jira-service-management
  [sys source-cfg discovered-item]
  (if (= :webdriver (crawl-method source-cfg))
    (webdriver/fetch-rendered sys (webdriver-source-cfg source-cfg) discovered-item)
    (cond
      (source/anomaly? discovered-item)
      discovered-item

      (:body discovered-item)
      (assoc discovered-item
             :content_type (or (:content_type discovered-item) "text/html"))

      :else
      (source/anomaly :cognitect.anomalies/fault
                      {:type :alida.source.jira-service-management/missing-api-body
                       :source-id (:id source-cfg)
                       :canonical-url (:canonical_url discovered-item)}))))
