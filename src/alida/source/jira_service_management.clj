(ns alida.source.jira-service-management
  (:require [alida.source :as source]
            [alida.source.webdriver :as webdriver]
            [clojure.data.json :as json]
            [clojure.string :as str]
            [com.climate.claypoole :as cp])
  (:import [java.net URI]
           [org.jsoup Jsoup]))

(def default-max-pages 1000)
(def default-max-concurrency 20)
(def default-category-page-limit 100)

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

(defn- url-origin
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

(defn- normalize-url
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

(defn- portal-id
  [url]
  (second (re-find #"/portal/([^/?#]+)" (or url ""))))

(defn- topic-id
  [url]
  (second (re-find #"/topic/([^/?#]+)" (or url ""))))

(defn- article-id
  [url]
  (or (second (re-find #"/article/([^/?#]+)" (or url "")))
      (second (re-find #"/kb/view/([^/?#]+)" (or url "")))
      (second (re-find #"/pages/([^/?#]+)" (or url "")))))

(defn- allowed-url?
  [source-cfg url]
  (let [allowed-prefixes (seq (:allowed_url_prefixes source-cfg))
        denied-urls (set (:denied_urls source-cfg))
        denied-prefixes (:denied_url_prefixes source-cfg)]
    (and (seq url)
         (or (not allowed-prefixes)
             (some #(str/starts-with? url %) allowed-prefixes))
         (not (contains? denied-urls url))
         (not-any? #(str/starts-with? url %) denied-prefixes))))

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
      (topic-id (:categoryUrl category))
      (topic-id (:url category))))

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
    {:origin (url-origin start-url)
     :portal-id (or (portal-id start-url)
                    (some-> (get-in payload [:portal :id]) str))
     :workspace-id (workspace-id (:body response) payload)
     :project-id (some-> (project-id payload) str)
     :categories (categories payload)}))

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

(defn- article-ref
  [ctx result]
  (let [id (some-> (:id result) str)]
    (when (seq id)
      {:article_id id
       :title (:title result)
       :canonical_url (or (some->> (:viewUrl result) (normalize-url (:origin ctx)))
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
        start-refs (keep (fn [url]
                           (when-let [id (article-id url)]
                             {:article_id id
                              :canonical_url (article-url ctx id)}))
                         [start-url])]
    (->> (concat start-refs category-refs)
         (filter #(allowed-url? source-cfg (:canonical_url %)))
         distinct
         vec)))

(defn- response-location
  [response]
  (or (get-in response [:headers "Location"])
      (get-in response [:headers "location"])))

(defn- resolve-shim-link
  [sys _source-cfg url]
  (try
    (let [response (source/request! sys {:method :get
                                         :url url
                                         :redirect-policy :never})
          location (response-location response)]
      (some->> location (normalize-url url) article-id))
    (catch Exception _
      nil)))

(defn- href-article-id
  [sys source-cfg href]
  (or (article-id href)
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
        response (source/request! sys {:method :get :url url})]
    (if (source/successful-status? (:status response))
      {:source_id (:id source-cfg)
       :source_type (:type source-cfg)
       :external_id id
       :canonical_url canonical-url
       :content_type (or (get-in response [:headers "Content-Type"])
                         (get-in response [:headers "content-type"])
                         "text/html")
       :title (article-title (:title ref) (:body response))
       :body (:body response)
       :hrefs (mapv #(article-url ctx %)
                    (article-links sys source-cfg canonical-url (:body response)))}
      (source/anomaly (case (:status response)
                        404 :cognitect.anomalies/not-found
                        :cognitect.anomalies/fault)
                      (merge {:type :alida.source.jira-service-management/article-fetch-failed
                              :source-id (:id source-cfg)
                              :canonical-url canonical-url
                              :article-id id
                              :status (:status response)}
                             (source/error-response-details response))))))

(defn- enqueue-refs
  [source-cfg ctx queued refs page]
  (reduce (fn [[queue queued] href]
            (if (and (allowed-url? source-cfg href)
                     (article-id href))
              (let [id (article-id href)]
                (if (contains? queued id)
                  [queue queued]
                  [(conj queue {:article_id id
                                :canonical_url (article-url ctx id)})
                   (conj queued id)]))
              [queue queued]))
          [refs queued]
          (:hrefs page)))

(defn- fetch-batch
  [sys source-cfg ctx refs]
  (let [concurrency (max 1 (max-concurrency source-cfg))]
    (if (= 1 concurrency)
      (mapv #(fetch-article sys source-cfg ctx %) refs)
      (cp/with-shutdown! [pool (cp/threadpool concurrency :name "alida-jsm-api")]
        (vec (doall
              (cp/upmap pool #(fetch-article sys source-cfg ctx %) refs)))))))

(defn- discover-api-start
  [sys source-cfg start-url remaining-pages]
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
              fetched (fetch-batch sys source-cfg ctx batch)
              pages (into pages fetched)
              [queue queued] (reduce (fn [[queue queued] page]
                                       (if (source/anomaly? page)
                                         [queue queued]
                                         (enqueue-refs source-cfg ctx queued queue page)))
                                     [queue queued]
                                     fetched)]
          (recur queue queued pages))))))

(defn- discover-api
  [sys source-cfg]
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
                 (into pages (discover-api-start sys source-cfg (first starts) remaining))))))))

(defmethod source/discover :jira-service-management
  [sys source-cfg]
  (case (crawl-method source-cfg)
    :webdriver (webdriver/discover-rendered sys (assoc source-cfg
                                                       :type "webdriver"
                                                       :render_profile "jira-service-management"))
    :auto (try
            (discover-api sys source-cfg)
            (catch Exception _
              (webdriver/discover-rendered sys (assoc source-cfg
                                                     :type "webdriver"
                                                     :render_profile "jira-service-management"))))
    :api (discover-api sys source-cfg)
    (discover-api sys source-cfg)))

(defmethod source/fetch :jira-service-management
  [sys source-cfg discovered-item]
  (if (= :webdriver (crawl-method source-cfg))
    (webdriver/fetch-rendered sys (assoc source-cfg
                                         :type "webdriver"
                                         :render_profile "jira-service-management")
                              discovered-item)
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
