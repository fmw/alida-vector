(ns alida.source.jira-service-management
  (:require [alida.source :as source]
            [alida.source.webdriver :as webdriver]
            [alida.url :as url]
            [clojure.data.json :as json]
            [clojure.string :as str]
            [com.climate.claypoole :as cp]
            [etaoin.api :as e])
  (:import [java.net URI]
           [org.jsoup Jsoup]))

(def default-max-pages 1000)
(def default-max-concurrency 20)
(def default-webdriver-max-concurrency 5)
(def default-category-page-limit 100)

(defn- source-origins
  [source-cfg]
  (set (keep url/origin (source/source-urls source-cfg))))

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
  [sys source-cfg request context]
  (source/require-success!
   (source/request-with-retries! sys source-cfg request)
   context))

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
                                  source-cfg
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
               :source-origins (source-origins source-cfg)
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
  (str origin "/wiki/api/v2/pages/" article-id "?body-format=view"))

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
                  source-cfg
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
  [sys url]
  (try
    (let [response (source/request! sys {:method :get
                                         :url url
                                         :redirect-policy :never})
          location (source/header response "Location")]
      (some->> location (alida.url/normalize url) alida.url/article-id))
    (catch Exception _
      nil)))

(defn- confluence-short-link-code
  [href]
  (let [path (try
               (.getPath (URI. href))
               (catch Exception _
                 nil))]
    (or (second (re-matches #"/wiki/x/([^/]+)/?" (or path "")))
        (second
         (re-matches
          #"/plugins/servlet/servicedesk/customer/confluence/shim/x/([^/]+)/?"
          (or path ""))))))

(defn- confluence-short-link-shim
  [ctx href]
  (let [origin (url/origin href)
        code (confluence-short-link-code href)]
    (when (and (contains? (:source-origins ctx) origin)
               code)
      (str origin
           "/plugins/servlet/servicedesk/customer/confluence/shim/x/"
           code))))

(defn- href-article-id
  [sys ctx href]
  (or (url/article-id href)
      (some->> (confluence-short-link-shim ctx href)
               (resolve-shim-link sys))))

(defn- article-links
  [sys ctx base-url body]
  (let [document (Jsoup/parse (or body "") base-url)]
    (->> (.select document "a[href]")
         (map #(.absUrl % "href"))
         (keep #(href-article-id sys ctx %))
         distinct
         vec)))

(defn- article-payload
  [response]
  (try
    (read-json (:body response))
    (catch Exception _
      nil)))

(defn- article-body
  [payload]
  (get-in payload [:body :view :value]))

(defn- fetch-article
  [sys source-cfg ctx ref]
  (let [id (:article_id ref)
        url (article-api-url ctx id)
        canonical-url (or (:canonical_url ref) (article-url ctx id))
        response (source/request-with-retries!
                  sys
                  source-cfg
                  {:method :get
                   :url url
                   :headers {"Accept" "application/json"}})]
    (if (source/successful-status? (:status response))
      (if-let [payload (article-payload response)]
        (let [raw-body (article-body payload)]
          (if (string? raw-body)
            {:source_id (:id source-cfg)
             :source_type (:type source-cfg)
             :external_id id
             :canonical_url canonical-url
             :content_type "text/html"
             :title (or (some-> (:title payload) str/trim not-empty)
                        (:title ref))
             :body raw-body
             :hrefs (mapv #(article-url ctx %)
                          (article-links sys ctx canonical-url raw-body))}
            (source/anomaly
             :cognitect.anomalies/fault
             {:type :alida.source.jira-service-management/article-content-missing
              :source-id (:id source-cfg)
              :canonical-url canonical-url
              :article-id id
              :status (:status response)})))
        (source/anomaly
         :cognitect.anomalies/fault
         {:type :alida.source.jira-service-management/article-response-invalid
          :source-id (:id source-cfg)
          :canonical-url canonical-url
          :article-id id
          :status (:status response)}))
      (if (= 404 (:status response))
        (source/skipped {:type :alida.source.jira-service-management/article-not-found
                         :source-id (:id source-cfg)
                         :canonical-url canonical-url
                         :article-id id
                         :status (:status response)})
        (source/fetch-anomaly response
                              {:type :alida.source.jira-service-management/article-fetch-failed
                               :source-id (:id source-cfg)
                               :canonical-url canonical-url
                               :article-id id})))))

(defn- fetch-article-safely
  [sys source-cfg ctx ref]
  (try
    (fetch-article sys source-cfg ctx ref)
    (catch clojure.lang.ExceptionInfo e
      (let [data (ex-data e)]
        (if (and (:retryable data) (:retry-exhausted data))
          (source/anomaly
           :cognitect.anomalies/fault
           (merge {:type :alida.source.jira-service-management/article-fetch-failed
                   :source-id (:id source-cfg)
                   :canonical-url (or (:canonical_url ref)
                                      (article-url ctx (:article_id ref)))
                   :article-id (:article_id ref)
                   :cause-type (:type data)}
                  (select-keys data
                               [:retryable
                                :retry-exhausted
                                :attempts
                                :max-retries
                                :request-method
                                :request-url])))
          (throw e))))))

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
    (vec (cp/upmap pool #(fetch-article-safely sys source-cfg ctx %) refs))
    (mapv #(fetch-article-safely sys source-cfg ctx %) refs)))

(defn- article-not-found?
  [item]
  (= :alida.source.jira-service-management/article-not-found
     (get-in item [:alida/skipped :type])))

(defn- article-api-unavailable
  [source-cfg ctx pages]
  (if (and (seq pages)
           (every? article-not-found? pages))
    [(source/anomaly
      :cognitect.anomalies/fault
      {:type :alida.source.jira-service-management/article-api-unavailable
       :source-id (:id source-cfg)
       :origin (:origin ctx)
       :status 404
       :article-count (count pages)})]
    pages))

(defn- article-api-unavailable?
  [item]
  (= :alida.source.jira-service-management/article-api-unavailable
     (get-in item [:alida/error :type])))

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
        (article-api-unavailable source-cfg ctx pages)
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
  (let [starts (source/source-urls source-cfg)]
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

;; ----------------------------------------------------------------------------
;; Rendered (WebDriver) crawl: the jira-service-management render profile.
;;
;; All JSM/Atlassian/Confluence knowledge the generic browser engine needs lives
;; here, exposed through the engine's `webdriver/render-profile` extension point.
;; ----------------------------------------------------------------------------

(def ^:private render-content-wait-selectors
  ["a[href*='/article/']"
   "a[href*='/topic/']"
   "#main-content"
   "main article"
   "article"
   "[data-testid*='article']"
   "[class*='article']"])

(def ^:private render-remove-selectors
  ;; Atlassian/JSM page chrome, stripped on top of the engine's universal
  ;; script/style removal.
  ["meta[name^=ajs-]"
   "#jsonPayload"
   "#envJson"
   "footer"
   "[role=dialog]"
   "[data-skip-link-wrapper=true]"
   "[data-testid=outer-BannerContainer]"])

(def ^:private default-iframe-related-links-timeout-ms 5000)
(def ^:private default-iframe-related-links-stable-count 3)
(def ^:private default-render-wait-interval-ms 100)

(defn- render-article-url?
  [url]
  (and (string? url)
       (str/includes? url "/article/")))

(defn- render-topic-url?
  [url]
  (and (string? url)
       (str/includes? url "/topic/")
       (not (str/includes? url "/article/"))))

(defn- render-portals-url?
  [url]
  (and (string? url)
       (str/ends-with? url "/portals")))

(defn- render-direct-service-desk-article-url?
  [url]
  (and (render-article-url? url)
       (not (str/includes? url "/topic/"))))

(defn- render-wait-selectors
  "URL-aware readiness selectors: portals list -> topic links, topic page ->
   article links, article page -> the Confluence iframe. Returns nil for other
   URLs so the engine falls back to the configured content-wait selectors."
  [_source-cfg url]
  (cond
    (render-article-url? url) ["iframe"]
    (render-topic-url? url) ["a[href*='/article/']"]
    (render-portals-url? url) ["a[href*='/topic/']"]
    :else nil))

(defn- contextualize-article-url
  [context-url url]
  (let [topic-id (url/path-id "topic" context-url)
        portal-id (url/path-id "portal" context-url)
        article-id (url/article-id url)]
    (cond
      (and topic-id
           portal-id
           article-id
           (string? url)
           (not (str/includes? url "/topic/")))
      (str (url/origin context-url)
           "/servicedesk/customer/portal/" portal-id
           "/topic/" topic-id
           "/article/" article-id)

      (and topic-id
           (string? url)
           (str/includes? url "/article/")
           (not (str/includes? url "/topic/")))
      (str/replace-first url
                         #"/portal/([^/]+)/article/([^/?#]+)"
                         (str "/portal/$1/topic/" topic-id "/article/$2"))

      (and portal-id
           article-id
           (string? url)
           (or (str/includes? url "/plugins/servlet/servicedesk/customer/confluence/shim/")
               (str/includes? url "/wiki/spaces/")))
      (str (url/origin context-url)
           "/servicedesk/customer/portal/" portal-id
           "/article/" article-id)

      :else
      url)))

(defn- direct-article-url
  [url]
  (let [portal-id (url/path-id "portal" url)
        article-id (url/article-id url)]
    (when (and portal-id
               article-id
               (string? url)
               (str/includes? url "/topic/"))
      (str (url/origin url)
           "/servicedesk/customer/portal/" portal-id
           "/article/" article-id))))

(defn- expand-article-url-variants
  [url]
  (if-let [direct-url (direct-article-url url)]
    [url direct-url]
    [url]))

(defn- render-transform-hrefs
  [context-url hrefs]
  (->> hrefs
       (map #(contextualize-article-url context-url %))
       (mapcat expand-article-url-variants)))

(def ^:private render-extras-js
  ;; Read JSM's in-page #jsonPayload for the category description (prepended to
  ;; the page content by the engine) and the knowledge-base fallback URL (used
  ;; when an article renders blank).
  "const payload = () => {
     try {
       const payloadText = document.querySelector('#jsonPayload')?.textContent;
       if (!payloadText) return null;
       return JSON.parse(payloadText);
     } catch (e) {
       return null;
     }
   };
   const canonical = href => {
     try {
       const url = new URL(href, window.location.href);
       url.hash = '';
       return url.toString();
     } catch (e) {
       return null;
     }
   };
   const payloadJson = payload();
   const categoryDescription = () => {
     try {
       const categories = payloadJson?.portal?.categories?.categories || [];
       const current = canonical(window.location.href);
       const category = categories.find(c => canonical(c.categoryUrl) === current);
       return category?.description || null;
     } catch (e) {
       return null;
     }
   };
   return {
     description: categoryDescription(),
     fallbackUrl: payloadJson?.portal?.kbPage?.url || null
   };")

(defn- render-blank-html?
  [html]
  (str/blank? (.text (Jsoup/parse (or html "")))))

(defn- render-blank-fallback-url
  "When a direct article render comes back blank, JSM exposes a knowledge-base
   page URL in its jsonPayload to retry against (the engine still SSRF/scope
   guards it before navigating)."
  [_source-cfg url page]
  (when (and (:fallback_url page)
             (render-article-url? url)
             (render-blank-html? (:body page)))
    (:fallback_url page)))

(defn- frame-related-links-count
  [driver]
  (long (or (e/js-execute driver
                          "return document.querySelectorAll(\"a[href*='/plugins/servlet/servicedesk/customer/confluence/shim/spaces/']\").length;")
            0)))

(defn- wait-for-related-links!
  "Related shim links render shortly after an article frame's body. Articles with
   zero or one of them would otherwise wait out the full timeout, so stop early
   once the count exceeds the article's own link or has stopped changing for a
   few consecutive samples."
  [driver source-cfg]
  (let [timeout-ms (or (:iframe_related_links_timeout_ms source-cfg)
                       default-iframe-related-links-timeout-ms)
        interval-ms (or (:wait_interval_ms source-cfg)
                        default-render-wait-interval-ms)
        deadline-ns (+ (System/nanoTime) (* timeout-ms 1000000))]
    (loop [previous-count -1
           stable-count 0]
      (let [related-count (frame-related-links-count driver)
            stable-count (if (= related-count previous-count)
                           (inc stable-count)
                           0)]
        (when-not (or (> related-count 1)
                      (>= stable-count default-iframe-related-links-stable-count)
                      (>= (System/nanoTime) deadline-ns))
          (e/wait (/ interval-ms 1000.0))
          (recur related-count stable-count))))))

(defn- render-await-extra-frame-content!
  [driver source-cfg outer-url]
  (when (render-direct-service-desk-article-url? outer-url)
    (wait-for-related-links! driver source-cfg)))

(defmethod webdriver/render-profile "jira-service-management"
  [_]
  {:content-wait-selectors render-content-wait-selectors
   :remove-selectors render-remove-selectors
   :wait-selectors render-wait-selectors
   :transform-hrefs render-transform-hrefs
   :extras-js render-extras-js
   :blank-fallback-url render-blank-fallback-url
   :await-extra-frame-content! render-await-extra-frame-content!})

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
            (let [items (discover-api sys source-cfg)]
              (if (some article-api-unavailable? items)
                (webdriver/discover-rendered sys (webdriver-source-cfg source-cfg))
                items))
            (catch Exception _
              (webdriver/discover-rendered sys (webdriver-source-cfg source-cfg))))
    :api (discover-api sys source-cfg)
    (discover-api sys source-cfg)))

(defmethod source/fetch :jira-service-management
  [sys source-cfg discovered-item]
  (if (= :webdriver (crawl-method source-cfg))
    (webdriver/fetch-rendered sys (webdriver-source-cfg source-cfg) discovered-item)
    (cond
      (source/skipped? discovered-item)
      discovered-item

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

(defmethod source/html-extraction-options :jira-service-management
  [source-cfg]
  (source/external-link-extraction-options source-cfg))
