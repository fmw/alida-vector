(ns alida.source.jira-service-management
  (:require [alida.source :as source]
            [clojure.string :as str]
            [com.brunobonacci.mulog :as u]
            [etaoin.api :as e])
  (:import [java.net URI]
           [org.jsoup Jsoup]))

(def default-max-pages 1000)
(def default-page-load-timeout-seconds 30)
(def default-wait-timeout-ms 30000)
(def default-wait-interval-ms 250)
(def default-url-stabilization-ms 500)
(def default-url-stabilization-attempts 10)
(def default-url-stabilization-stable-count 2)
(def default-iframe-related-links-timeout-ms 5000)
(def default-browser-restart-after-pages 50)
(def default-browser-restart-after-failures 2)
(def default-progress-log-every-pages 25)

(def default-content-wait-selectors
  ["a[href*='/article/']"
   "a[href*='/topic/']"
   "#main-content"
   "main article"
   "article"
   "[data-testid*='article']"
   "[class*='article']"])

(def default-browser-args
  ["--no-sandbox"
   "--disable-dev-shm-usage"
   "--disable-gpu"
   "--disable-web-security"
   "--disable-blink-features=AutomationControlled"
   "--blink-settings=imagesEnabled=false"
   "--disable-plugins"
   "--disable-extensions"
   "--disable-breakpad"
   "--disable-default-apps"
   "--disable-background-networking"
   "--disable-background-timer-throttling"
   "--disable-hang-monitor"
   "--disable-popup-blocking"
   "--disable-prompt-on-repost"
   "--disable-renderer-backgrounding"
   "--disable-sync"
   "--disable-features=TranslateUI"
   "--disable-notifications"
   "--metrics-recording-only"
   "--no-first-run"
   "--password-store=basic"
   "--use-mock-keychain"
   "--disk-cache-dir=/tmp/alida-vector/chrome-cache"
   "--disk-cache-size=104857600"
   "--window-size=1920,1080"])

(def cleanup-script
  "const removeSelectors = arguments[0] || [];
   const internalHosts = new Set((arguments[1] || []).map(host => String(host).toLowerCase()));
   const preserveExternalLinks = arguments[2] !== false;

   const linkHost = href => {
     try {
       return new URL(href, window.location.href).hostname.toLowerCase();
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
   const payload = () => {
     try {
       const payloadText = document.querySelector('#jsonPayload')?.textContent;
       if (!payloadText) return null;
       return JSON.parse(payloadText);
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
   const knowledgeBaseFallbackUrl = () => payloadJson?.portal?.kbPage?.url || null;

   const contentSelector = '#main-content, #content, #main, .content, main, article, body';
   const hrefs = new Set();
   const collectLinks = doc => {
     if (!doc) return;
     doc.querySelectorAll('a[href]').forEach(a => {
       const href = a.href || a.getAttribute('href');
       if (href) hrefs.add(href);
     });
   };
   const pickContent = doc => {
     if (!doc) return null;
     return doc.querySelector(contentSelector) || doc.body || doc.documentElement;
   };

   let content = pickContent(document);
   collectLinks(document);

   const iframe = document.querySelector('iframe');
   if (iframe && iframe.contentDocument) {
     collectLinks(iframe.contentDocument);
     const iframeContent = pickContent(iframe.contentDocument);
     if (iframeContent) {
       content = iframeContent;
     }
   }

   if (preserveExternalLinks) {
     document.querySelectorAll('a[href]').forEach(a => {
       const href = a.href || a.getAttribute('href');
       const text = (a.textContent || '').trim();
       const host = linkHost(href);
       if (href && text && host && !internalHosts.has(host)) {
         a.replaceWith(document.createTextNode(`[${text}](${href})`));
       }
     });
   }

   const description = categoryDescription();

   for (const selector of removeSelectors) {
     document.querySelectorAll(selector).forEach(el => el.remove());
   }

   if (content && description && !(content.textContent || '').includes(description)) {
     const p = document.createElement('p');
     p.textContent = description;
     content.prepend(p);
   }

   return {
     url: window.location.href,
     title: document.title || '',
     html: content ? content.outerHTML : document.documentElement.outerHTML,
     hrefs: Array.from(hrefs),
     fallbackUrl: knowledgeBaseFallbackUrl()
   };")

(defn- source-urls
  [source-cfg]
  (or (seq (:start_urls source-cfg))
      (when-let [url (:start_url source-cfg)] [url])
      (when-let [url (:url source-cfg)] [url])))

(defn- url-host
  [url]
  (try
    (some-> (URI. url) .getHost str/lower-case)
    (catch Exception _
      nil)))

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

(defn- max-pages
  [source-cfg]
  (or (:max_pages source-cfg)
      default-max-pages))

(defn- wait-timeout-ms
  [source-cfg]
  (or (:wait_timeout_ms source-cfg)
      default-wait-timeout-ms))

(defn- wait-interval-ms
  [source-cfg]
  (or (:wait_interval_ms source-cfg)
      default-wait-interval-ms))

(defn- content-wait-selectors
  [source-cfg]
  (or (seq (:content_wait_selectors source-cfg))
      default-content-wait-selectors))

(defn- remove-selectors
  [source-cfg]
  (vec (concat ["meta[name^=ajs-]"
                "script"
                "style"
                "#jsonPayload"
                "#envJson"
                "footer"
                "[role=dialog]"
                "[data-skip-link-wrapper=true]"
                "[data-testid=outer-BannerContainer]"]
               (:remove_selectors source-cfg))))

(defn- browser-args
  [source-cfg]
  (vec (concat default-browser-args (:browser_args source-cfg))))

(defn- browser-restart-after-pages
  [source-cfg]
  (or (:browser_restart_after_pages source-cfg)
      default-browser-restart-after-pages))

(defn- browser-restart-after-failures
  [source-cfg]
  (or (:browser_restart_after_failures source-cfg)
      default-browser-restart-after-failures))

(defn- progress-log-every-pages
  [source-cfg]
  (or (:progress_log_every_pages source-cfg)
      default-progress-log-every-pages))

(defn- internal-link-hosts
  [source-cfg]
  (vec
   (distinct
    (remove str/blank?
            (concat (keep url-host (source-urls source-cfg))
                    (:internal_link_hosts source-cfg))))))

(defn- allowed-url-prefixes
  [source-cfg]
  (or (seq (:allowed_url_prefixes source-cfg))
      (keep url-origin (source-urls source-cfg))))

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

(defn- topic-id
  [url]
  (second (re-find #"/topic/([^/?#]+)" (or url ""))))

(defn- portal-id
  [url]
  (second (re-find #"/portal/([^/?#]+)" (or url ""))))

(defn- article-id
  [url]
  (or (second (re-find #"/article/([^/?#]+)" (or url "")))
      (second (re-find #"/plugins/servlet/servicedesk/customer/confluence/shim/spaces/[^/]+/pages/([^/?#]+)"
                       (or url "")))
      (second (re-find #"/wiki/spaces/[^/]+/pages/([^/?#]+)"
                       (or url "")))))

(defn- contextualize-article-url
  [context-url url]
  (let [topic-id (topic-id context-url)
        portal-id (portal-id context-url)
        article-id (article-id url)]
    (cond
      (and topic-id
           portal-id
           article-id
           (string? url)
           (not (str/includes? url "/topic/")))
      (str (url-origin context-url)
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
      (str (url-origin context-url)
           "/servicedesk/customer/portal/" portal-id
           "/article/" article-id)

      :else
      url)))

(defn- direct-article-url
  [url]
  (let [portal-id (portal-id url)
        article-id (article-id url)]
    (when (and portal-id
               article-id
               (string? url)
               (str/includes? url "/topic/"))
      (str (url-origin url)
           "/servicedesk/customer/portal/" portal-id
           "/article/" article-id))))

(defn- expand-article-url-variants
  [url]
  (if-let [direct-url (direct-article-url url)]
    [url direct-url]
    [url]))

(defn- url-allowed?
  [source-cfg url]
  (let [allowed-prefixes (seq (allowed-url-prefixes source-cfg))
        denied-urls (set (:denied_urls source-cfg))
        denied-prefixes (:denied_url_prefixes source-cfg)]
    (and (seq url)
         (or (not (seq allowed-prefixes))
             (some #(str/starts-with? url %) allowed-prefixes))
         (not (contains? denied-urls url))
         (not-any? #(str/starts-with? url %) denied-prefixes))))

(defn- driver-options
  [source-cfg]
  (cond-> {:args (browser-args source-cfg)
           :capabilities {:pageLoadStrategy "eager"
                          :goog:chromeOptions
                          {:prefs {"profile.default_content_setting_values" {"images" 2}
                                   "profile.managed_default_content_settings" {"images" 2}}}}}
    (seq (System/getenv "CHROMEDRIVER_BIN"))
    (assoc :path-driver (System/getenv "CHROMEDRIVER_BIN"))

    (seq (System/getenv "CHROME_BIN"))
    (assoc :path-browser (System/getenv "CHROME_BIN"))))

(defn- start-driver!
  [source-cfg]
  (e/chrome-headless (driver-options source-cfg)))

(defn- quit-driver!
  [driver]
  (e/quit driver))

(defn- safe-quit-driver!
  [driver]
  (try
    (quit-driver! driver)
    (catch Exception _
      nil)))

(defn- restart-driver!
  [current-driver source-cfg]
  (safe-quit-driver! current-driver)
  (start-driver! source-cfg))

(defn- sleep!
  [millis]
  (Thread/sleep (long millis)))

(defn- page-ready?
  [driver]
  (#{"interactive" "complete"} (e/js-execute driver "return document.readyState;")))

(defn- any-selector-present?
  [driver selectors]
  (boolean
   (e/js-execute driver
                 "return (arguments[0] || []).some(selector => !!document.querySelector(selector));"
                 (vec selectors))))

(declare article-url?)

(defn- topic-url?
  [url]
  (and (string? url)
       (str/includes? url "/topic/")
       (not (str/includes? url "/article/"))))

(defn- portals-url?
  [url]
  (and (string? url)
       (str/ends-with? url "/portals")))

(defn- wait-selectors-for-url
  [source-cfg url]
  (cond
    (article-url? url) ["iframe"]
    (topic-url? url) ["a[href*='/article/']"]
    (portals-url? url) ["a[href*='/topic/']"]
    :else (content-wait-selectors source-cfg)))

(defn- wait-until
  [source-cfg pred]
  (let [deadline (+ (System/currentTimeMillis) (wait-timeout-ms source-cfg))
        interval-ms (wait-interval-ms source-cfg)]
    (loop []
      (cond
        (pred) true
        (>= (System/currentTimeMillis) deadline) false
        :else (do
                (sleep! interval-ms)
                (recur))))))

(defn- wait-until-for
  [timeout-ms interval-ms pred]
  (let [deadline (+ (System/currentTimeMillis) timeout-ms)]
    (loop []
      (cond
        (pred) true
        (>= (System/currentTimeMillis) deadline) false
        :else (do
                (sleep! interval-ms)
                (recur))))))

(defn- wait-for-page!
  [driver source-cfg url]
  (wait-until source-cfg #(page-ready? driver))
  (let [current-url (or (e/get-url driver) url)]
    (wait-until source-cfg #(any-selector-present? driver (wait-selectors-for-url source-cfg current-url)))))

(defn- blank-html?
  [html]
  (str/blank? (.text (Jsoup/parse (or html "")))))

(defn- iframe-present?
  [driver]
  (pos? (long (or (e/js-execute driver "return document.querySelectorAll('iframe').length;")
                  0))))

(defn- frame-text-present?
  [driver]
  (boolean
   (e/js-execute driver
                 "return ((document.body && document.body.innerText) || '').trim().length > 0;")))

(defn- normalized-text
  [value]
  (-> (or value "")
      str/lower-case
      (str/replace #"\s+" " ")
      str/trim))

(defn- frame-content-state
  [driver]
  (e/js-execute driver
                "const content = document.querySelector('#main-content') || document.body;
                 const text = ((content && content.innerText) || '').trim();
                 const hrefCount = document.querySelectorAll('a[href]').length;
                 const relatedHrefCount =
                   document.querySelectorAll(\"a[href*='/plugins/servlet/servicedesk/customer/confluence/shim/spaces/']\").length;
                 return {text, hrefCount, relatedHrefCount};"))

(defn- frame-content-ready?
  [driver expected-title]
  (let [result (frame-content-state driver)
        text (or (get result "text") (:text result) "")
        href-count (or (get result "hrefCount") (:hrefCount result) 0)
        normalized-title (normalized-text expected-title)
        normalized-body (normalized-text text)]
    (and (seq normalized-body)
	         (or (and (seq normalized-title)
	                  (str/includes? normalized-body normalized-title))
	             (pos? (long href-count))
	             (> (count normalized-body) 1000)))))

(defn- direct-service-desk-article-url?
  [url]
  (and (article-url? url)
       (not (str/includes? url "/topic/"))))

(defn- frame-related-links-ready?
  [driver]
  (let [result (frame-content-state driver)
        related-href-count (or (get result "relatedHrefCount")
                               (:relatedHrefCount result)
                               0)]
    (> (long related-href-count) 1)))

(defn- wait-for-frame-content!
  [driver source-cfg expected-title outer-url]
  (wait-until source-cfg #(frame-content-ready? driver expected-title))
  (when (direct-service-desk-article-url? outer-url)
    (wait-until-for (or (:iframe_related_links_timeout_ms source-cfg)
                       default-iframe-related-links-timeout-ms)
                    (wait-interval-ms source-cfg)
                    #(frame-related-links-ready? driver))))

(defn- article-url?
  [url]
  (and (string? url)
       (str/includes? url "/article/")))

(defn- stabilized-url
  [driver original-url source-cfg]
  (let [pause-ms (or (:url_stabilization_ms source-cfg)
                     default-url-stabilization-ms)
        attempts (or (:url_stabilization_attempts source-cfg)
                     default-url-stabilization-attempts)
        required-stable (or (:url_stabilization_stable_count source-cfg)
                            default-url-stabilization-stable-count)]
    (loop [remaining attempts
           previous-url (or (e/get-url driver) original-url)
           stable-count 0]
      (if (zero? remaining)
        previous-url
        (do
          (sleep! pause-ms)
          (let [current-url (or (e/get-url driver) previous-url)]
            (if (= current-url previous-url)
              (let [stable-count (inc stable-count)]
                (if (>= stable-count required-stable)
                  current-url
                  (recur (dec remaining) current-url stable-count)))
              (recur (dec remaining) current-url 0))))))))

(defn- execute-cleanup
  [driver source-cfg]
  (e/js-execute driver
                cleanup-script
                (remove-selectors source-cfg)
                (internal-link-hosts source-cfg)
                (:preserve_external_links source-cfg)))

(defn- page-info
  [driver]
  (let [result (e/js-execute driver
                             "const hrefs = new Set();
                              document.querySelectorAll('a[href]').forEach(a => {
                                const href = a.href || a.getAttribute('href');
                                if (href) hrefs.add(href);
                              });
                              const payload = () => {
                                try {
                                  const payloadText = document.querySelector('#jsonPayload')?.textContent;
                                  if (!payloadText) return null;
                                  return JSON.parse(payloadText);
                                } catch (e) {
                                  return null;
                                }
                              };
                              const payloadJson = payload();
                              return {
                                url: window.location.href,
                                title: document.title || '',
                                hrefs: Array.from(hrefs),
                                fallbackUrl: payloadJson?.portal?.kbPage?.url || null,
                                iframeUrl: document.querySelector('iframe')?.src || null
                              };")
        hrefs (or (get result "hrefs") (:hrefs result) [])]
    {:url (or (get result "url") (:url result) (e/get-url driver))
     :title (or (get result "title") (:title result))
     :body ""
     :hrefs hrefs
     :fallback_url (or (get result "fallbackUrl") (:fallbackUrl result))
     :iframe_url (or (get result "iframeUrl") (:iframeUrl result))}))

(defn- cleanup-result->page
  [driver result]
  (let [hrefs (or (get result "hrefs") (:hrefs result) [])
        title (or (get result "title") (:title result))
        html (or (get result "html") (:html result) (e/get-source driver))]
    {:url (or (get result "url") (:url result) (e/get-url driver))
     :title title
     :body html
     :hrefs hrefs
     :fallback_url (or (get result "fallbackUrl") (:fallbackUrl result))}))

(defn- render-current-frame
  [driver source-cfg]
  (cleanup-result->page driver (execute-cleanup driver source-cfg)))

(defn- render-first-iframe
  [driver source-cfg expected-title outer-url]
  (try
    (e/switch-frame-first driver)
    (wait-until source-cfg #(page-ready? driver))
    (wait-for-frame-content! driver source-cfg expected-title outer-url)
    (render-current-frame driver source-cfg)
    (catch Exception _
      nil)
    (finally
      (try
        (e/switch-frame-top driver)
        (catch Exception _
          nil)))))

(defn- render-url-content
  [driver source-cfg url]
  (try
    (e/go driver url)
    (wait-until source-cfg #(page-ready? driver))
    (wait-until source-cfg #(frame-text-present? driver))
    (render-current-frame driver source-cfg)
    (catch Exception _
      nil)))

(defn- merge-iframe-page
  [outer iframe]
  (assoc iframe
         :url (:url outer)
         :title (or (:title outer) (:title iframe))
         :fallback_url (:fallback_url outer)
         :hrefs (vec (distinct (concat (:hrefs outer) (:hrefs iframe))))))

(defn- render-current-page
  [driver source-cfg]
  (let [had-iframe? (iframe-present? driver)
        shell (when had-iframe? (page-info driver))]
    (if had-iframe?
      (if-let [iframe (render-first-iframe driver source-cfg (:title shell) (:url shell))]
        (if (blank-html? (:body iframe))
          (if-let [iframe-url (:iframe_url shell)]
            (if-let [iframe-page (render-url-content driver source-cfg iframe-url)]
              (if (blank-html? (:body iframe-page))
                (render-current-frame driver source-cfg)
                (merge-iframe-page shell iframe-page))
              (render-current-frame driver source-cfg))
            (render-current-frame driver source-cfg))
          (merge-iframe-page shell iframe))
        (render-current-frame driver source-cfg))
      (render-current-frame driver source-cfg))))

(defn- go-and-render
  [driver source-cfg url]
  (try
    (e/go driver url)
    (catch Exception _
      nil))
  (wait-for-page! driver source-cfg url)
  (render-current-page driver source-cfg))

(defn- rendered-page
  [driver source-cfg url]
  (e/set-page-load-timeout driver (or (:page_load_timeout_seconds source-cfg)
                                      default-page-load-timeout-seconds))
  (let [initial (go-and-render driver source-cfg url)
        fallback-url (:fallback_url initial)
        rendered (if (and fallback-url
                          (article-url? url)
                          (blank-html? (:body initial)))
                   (assoc (go-and-render driver source-cfg fallback-url)
                          :url (:url initial)
                          :fallback_url fallback-url)
                   initial)
        canonical-url (or (some-> (stabilized-url driver url source-cfg)
                                  (normalize-url url))
                          url)
        result-url (or (:url rendered) canonical-url)
        canonical-url (or (normalize-url canonical-url result-url) canonical-url)
        external-id (article-id canonical-url)
        hrefs (:hrefs rendered)
        title (:title rendered)
        html (:body rendered)]
    {:source_id (:id source-cfg)
     :source_type (:type source-cfg)
     :external_id external-id
     :canonical_url canonical-url
     :content_type "text/html"
     :title title
     :body html
     :fallback_url (:fallback_url rendered)
     :hrefs (->> hrefs
                 (keep #(normalize-url canonical-url %))
                 (map #(contextualize-article-url canonical-url %))
                 (mapcat expand-article-url-variants)
                 distinct
                 vec)}))

(defn- render-page!
  [driver source-cfg url]
  (try
    (rendered-page driver source-cfg url)
    (catch Exception e
      (source/anomaly :cognitect.anomalies/fault
                      {:type :alida.source.jira-service-management/render-failed
                       :source-id (:id source-cfg)
                       :canonical_url url
                       :message (or (get-in (ex-data e) [:response :value :message])
                                    (ex-message e))}))))

(defn- blank-page?
  [page]
  (and (not (source/anomaly? page))
       (blank-html? (:body page))))

(defn- enqueue-links
  [source-cfg queue queued visited page]
  (reduce (fn [[queue queued] href]
            (if (and (url-allowed? source-cfg href)
                     (not (contains? visited href))
                     (not (contains? queued href)))
              [(conj queue href) (conj queued href)]
              [queue queued]))
          [queue queued]
          (:hrefs page)))

(defn- log-progress?
  [source-cfg page-count]
  (let [every-pages (progress-log-every-pages source-cfg)]
    (and (pos? every-pages)
         (pos? page-count)
         (zero? (mod page-count every-pages)))))

(defmethod source/discover :jira-service-management
  [_sys source-cfg]
  (let [starts (source-urls source-cfg)]
    (when-not (seq starts)
      (throw (ex-info "Jira Service Management source requires url, start_url, or start_urls"
                      {:type :alida.source.jira-service-management/missing-url
                       :source-id (:id source-cfg)})))
    (let [driver (atom (start-driver! source-cfg))]
      (try
        (loop [queue (into clojure.lang.PersistentQueue/EMPTY starts)
               queued (set starts)
               visited #{}
               pages []
               pages-in-session 0
               consecutive-failures 0]
          (cond
            (or (empty? queue)
                (>= (count pages) (max-pages source-cfg)))
            pages

            (contains? visited (peek queue))
            (recur (pop queue) queued visited pages pages-in-session consecutive-failures)

            :else
            (let [url (peek queue)
                  queue (pop queue)
                  restart-after-pages (browser-restart-after-pages source-cfg)
                  restart-for-page-limit? (and (pos? restart-after-pages)
                                               (>= pages-in-session restart-after-pages))
                  _ (when restart-for-page-limit?
                      (reset! driver (restart-driver! @driver source-cfg)))
                  pages-in-session (if restart-for-page-limit? 0 pages-in-session)
                  page (render-page! @driver source-cfg url)
                  blank-render? (blank-page? page)
                  _ (when blank-render?
                      (reset! driver (restart-driver! @driver source-cfg)))
                  pages-in-session (if blank-render? 0 pages-in-session)
                  page (if blank-render?
                         (render-page! @driver source-cfg (or (:canonical_url page) url))
                         page)
                  rendered? (not (source/anomaly? page))
                  pages-in-session (inc pages-in-session)
                  consecutive-failures (if rendered?
                                         0
                                         (inc consecutive-failures))
                  restart-after-failures (browser-restart-after-failures source-cfg)
                  restart-for-failures? (and (not rendered?)
                                             (pos? restart-after-failures)
                                             (>= consecutive-failures restart-after-failures))
                  _ (when restart-for-failures?
                      (reset! driver (restart-driver! @driver source-cfg)))
                  pages-in-session (if restart-for-failures? 0 pages-in-session)
                  consecutive-failures (if restart-for-failures? 0 consecutive-failures)
                  visited (conj visited url)
                  canonical-url (:canonical_url page)
                  visited (cond-> visited canonical-url (conj canonical-url))
                  pages (conj pages page)
                  page-count (count pages)
                  _ (when (log-progress? source-cfg page-count)
                      (u/log ::discovery-progress
                             :source-id (:id source-cfg)
                             :pages page-count
                             :queue-size (count queue)
                             :errors (count (filter source/anomaly? pages))))
                  [queue queued] (if (source/anomaly? page)
                                   [queue queued]
                                   (enqueue-links source-cfg queue queued visited page))]
              (recur queue queued visited pages pages-in-session consecutive-failures))))
        (finally
          (safe-quit-driver! @driver))))))

(defmethod source/fetch :jira-service-management
  [_sys source-cfg discovered-item]
  (cond
    (source/anomaly? discovered-item)
    discovered-item

    (:body discovered-item)
    (assoc discovered-item
           :content_type (or (:content_type discovered-item) "text/html"))

    :else
    (source/anomaly :cognitect.anomalies/fault
                    {:type :alida.source.jira-service-management/missing-rendered-body
                     :source-id (:id source-cfg)
                     :canonical-url (:canonical_url discovered-item)})))
