(ns alida.source.webdriver
  "Generic headless-browser crawl engine. The discovery queue, parallel browser
   workers, restart/retry policy, readiness waits, iframe extraction, and
   allow/deny + SSRF scoping are all site-agnostic. Site-specific behavior (which
   selectors to await, how to rewrite/expand discovered links, in-page metadata
   extraction, extra frame waits) is supplied by a render profile resolved from
   the source's `:render_profile`. The `:default` profile is fully generic; see
   e.g. `alida.source.jira-service-management` for a site-specific profile."
  (:require [alida.source :as source]
            [alida.url :as url]
            [clojure.string :as str]
            [com.climate.claypoole :as cp]
            [com.brunobonacci.mulog :as u]
            [etaoin.api :as e])
  (:import [org.jsoup Jsoup]))

(def default-max-pages 1000)
(def default-page-load-timeout-seconds 30)
(def default-wait-timeout-ms 30000)
(def default-wait-interval-ms 100)
(def default-url-stabilization-ms 100)
(def default-url-stabilization-attempts 20)
(def default-url-stabilization-stable-count 2)
(def default-browser-restart-after-pages 50)
(def default-browser-restart-after-failures 2)
(def default-render-failure-retries 2)
(def default-progress-log-every-pages 25)
(def default-max-concurrency 1)

(def default-browser-args
  ;; --headless=new plus a regular browser user-agent: the default
  ;; HeadlessChrome user-agent is treated specially by some CDN bot mitigation,
  ;; so present the same UA a regular browser would send.
  ["--headless=new"
   (str "--user-agent=Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 "
        "(KHTML, like Gecko) Chrome/146.0.0.0 Safari/537.36")
   "--disable-dev-shm-usage"
   "--disable-gpu"
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
   "--disk-cache-size=104857600"
   "--window-size=1920,1080"])

(def cleanup-script
  ;; Generic, site-agnostic cleanup. The live DOM is read but never mutated:
  ;; selector removal and description prepending happen on a detached clone of
  ;; the content element. Mutating the live page while a SPA
  ;; is still hydrating throws its scripts into a busy loop that wedges the
  ;; renderer (every later WebDriver call then fails with "timed out receiving
  ;; message from renderer"). arguments: [removeSelectors description];
  ;; description (optional) is prepended to
  ;; the content when present, letting a render profile inject site-specific
  ;; metadata without this script knowing where it came from.
  "const removeSelectors = arguments[0] || [];
   const description = arguments[1] || null;

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

   const clone = content ? content.cloneNode(true) : null;

   if (clone) {
     for (const selector of removeSelectors) {
       clone.querySelectorAll(selector).forEach(el => el.remove());
     }
   }

   if (clone && description && !(clone.textContent || '').includes(description)) {
     const p = document.createElement('p');
     p.textContent = description;
     clone.prepend(p);
   }

   return {
     url: window.location.href,
     title: document.title || '',
     html: clone ? clone.outerHTML : document.documentElement.outerHTML,
     hrefs: Array.from(hrefs)
   };")

;; ----------------------------------------------------------------------------
;; Render profiles
;;
;; A render profile adapts this generic engine to a site family. It is a map of
;; data + fn hooks, resolved from the source's `:render_profile` (a string;
;; nil/unknown -> the generic `:default` profile). Keys:
;;
;;   :content-wait-selectors   default selectors awaited when the source config
;;                             does not set `content_wait_selectors`.
;;   :remove-selectors         extra CSS selectors stripped from every page (on
;;                             top of the engine's universal "script"/"style"
;;                             and the source's configured `remove_selectors`).
;;   :wait-selectors           (fn [source-cfg url]) -> selectors | nil. URL-aware
;;                             readiness selectors; nil falls back to
;;                             content-wait-selectors.
;;   :transform-hrefs          (fn [canonical-url hrefs]) -> hrefs. Rewrite/expand
;;                             discovered links (default: identity).
;;   :extras-js                JS string returning {description, fallbackUrl} or
;;                             nil. `description` is prepended to page content;
;;                             `fallbackUrl` is a blank-page navigation fallback.
;;   :blank-fallback-url       (fn [source-cfg url page]) -> url | nil. URL to
;;                             re-render when the first render of `url` is blank
;;                             (still subject to the engine's SSRF/scope guard).
;;   :await-extra-frame-content! (fn [driver source-cfg outer-url]) -> any. Extra
;;                             waiting after switching into an iframe, on top of
;;                             the generic frame-content-ready wait.
;; ----------------------------------------------------------------------------

(defmulti render-profile
  "Resolve a render profile from a `:render_profile` value (see the profile
   contract above). The `:default` method is the fully generic profile."
  identity)

(def generic-profile
  ;; No default content-wait selectors: a generic site's structure is unknown,
  ;; so the engine waits for the body to actually contain text (see
  ;; wait-for-page!) rather than guessing at selectors — and crucially never
  ;; treats the always-present <body> as a readiness signal. A source that needs
  ;; to await a specific element sets `content_wait_selectors`.
  {:content-wait-selectors nil
   :remove-selectors []
   :wait-selectors (fn [_source-cfg _url] nil)
   :transform-hrefs (fn [_canonical-url hrefs] hrefs)
   :extras-js nil
   :blank-fallback-url (fn [_source-cfg _url _page] nil)
   :await-extra-frame-content! (fn [_driver _source-cfg _outer-url] nil)})

(defmethod render-profile :default [_] generic-profile)

(defn- profile
  [source-cfg]
  (render-profile (:render_profile source-cfg)))

(defn- max-pages
  [source-cfg]
  (or (:max_pages source-cfg)
      default-max-pages))

(defn- max-concurrency
  [source-cfg]
  (max 1 (or (:max_concurrency source-cfg)
             default-max-concurrency)))

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
      (:content-wait-selectors (profile source-cfg))))

(defn- browser-sandbox-disabled?
  []
  (contains? #{"1" "true" "yes"}
             (some-> (System/getenv "ALIDA_CHROME_NO_SANDBOX")
                     str/trim
                     str/lower-case)))

(defn- remove-selectors
  [source-cfg]
  (vec (concat ["script" "style"]
               (:remove-selectors (profile source-cfg))
               (:remove_selectors source-cfg))))

(defn- browser-args
  [source-cfg]
  (vec (concat default-browser-args
               ;; RuntimeDefault seccomp plus no privilege escalation prevents
               ;; Chromium's namespace and setuid sandboxes from starting in
               ;; the container image. The image opts out explicitly and relies
               ;; on its non-root container boundary instead; non-container
               ;; launches retain Chromium's sandbox by default.
               (when (browser-sandbox-disabled?) ["--no-sandbox"])
               (:browser_args source-cfg))))

(defn- browser-restart-after-pages
  [source-cfg]
  (or (:browser_restart_after_pages source-cfg)
      default-browser-restart-after-pages))

(defn- browser-restart-after-failures
  [source-cfg]
  (or (:browser_restart_after_failures source-cfg)
      default-browser-restart-after-failures))

(defn- render-failure-retries
  "How many times a failed render is re-enqueued at the back of the queue.
   Transient renderer hangs and session crashes would otherwise drop the URL —
   fatal when it is a seed (the whole crawl yields nothing) and a source of
   run-to-run inconsistency for any other page. Re-enqueueing retries later,
   usually on a browser that has rendered other pages in the meantime, instead
   of immediately inside whatever condition caused the failure."
  [source-cfg]
  (or (:render_failure_retries source-cfg)
      default-render-failure-retries))

(defn- retry-render?
  [source-cfg page attempt]
  (and (source/anomaly? page)
       (<= attempt (render-failure-retries source-cfg))))

(defn- progress-log-every-pages
  [source-cfg]
  (or (:progress_log_every_pages source-cfg)
      default-progress-log-every-pages))

(defn- allowed-url-prefixes
  [source-cfg]
  (or (seq (:allowed_url_prefixes source-cfg))
      (keep url/origin (source/source-urls source-cfg))))

(defn- url-allowed?
  [source-cfg url]
  (url/allowed? (url/source-allow-config source-cfg (seq (allowed-url-prefixes source-cfg)))
                url))

(defn- host-allowed?
  [source-cfg url]
  (boolean
   (when-let [h (url/host url)]
     (contains? (set (source/internal-link-hosts source-cfg)) h))))

(defn- navigable?
  "SSRF + scope guard for URLs sourced from untrusted rendered content (iframe
   src, profile fallback URL). Such a URL must clear both checks:

   1. The host is one of the source's trusted hosts (start-URL hosts plus any
      configured internal_link_hosts) — blocks redirection to internal/metadata
      endpoints.
   2. It passes the same allow/deny scope rules (`allowed_url_prefixes`,
      `denied_urls`, `denied_url_prefixes`) the discovery queue applies, so a
      trusted page cannot steer navigation to a same-host path that the normal
      crawl would reject."
  [source-cfg url]
  (and (host-allowed? source-cfg url)
       (url-allowed? source-cfg url)))

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
  "Plain e/chrome: headless mode comes from the --headless=new browser arg, not
   etaoin's :headless capability (which selects the legacy headless mode)."
  [source-cfg]
  (e/chrome (driver-options source-cfg)))

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
  [current-driver source-cfg reason]
  (u/log ::browser-restart
         :source-id (:id source-cfg)
         :reason reason)
  (safe-quit-driver! current-driver)
  (start-driver! source-cfg))

(defn- pause!
  [millis]
  (e/wait (/ millis 1000.0)))

(defn- page-ready?
  [driver]
  (#{"interactive" "complete"} (e/js-execute driver "return document.readyState;")))

(defn- any-selector-present?
  [driver selectors]
  (boolean
   (e/js-execute driver
                 "return (arguments[0] || []).some(selector => !!document.querySelector(selector));"
                 (vec selectors))))

(defn- frame-text-present?
  [driver]
  (boolean
   (e/js-execute driver
                 "return ((document.body && document.body.innerText) || '').trim().length > 0;")))

(defn- wait-selectors-for-url
  "Readiness selectors for a URL: the active profile may pick them by URL shape;
   nil falls back to the source's configured/default content-wait selectors."
  [source-cfg url]
  (or ((:wait-selectors (profile source-cfg)) source-cfg url)
      (content-wait-selectors source-cfg)))

(defn- wait-until
  [source-cfg pred]
  (try
    (boolean
     (e/wait-predicate pred
                       {:timeout (/ (wait-timeout-ms source-cfg) 1000.0)
                        :interval (/ (wait-interval-ms source-cfg) 1000.0)}))
    (catch Throwable _
      false)))

(defn- wait-for-page!
  [driver source-cfg url]
  (when-not (wait-until source-cfg #(page-ready? driver))
    (throw (ex-info "Timed out waiting for page readiness"
                    {:type :alida.source.webdriver/page-ready-timeout
                     :canonical-url url})))
  (let [current-url (or (e/get-url driver) url)
        selectors (wait-selectors-for-url source-cfg current-url)
        ;; With content selectors (configured, or supplied by the profile), wait
        ;; for one to appear. Without any (the generic profile on an unconfigured
        ;; source), wait for the body to actually contain text — the always-
        ;; present <body> is not a readiness signal, so a JS-heavy page would
        ;; otherwise be captured before it renders.
        content-ready? (if (seq selectors)
                         #(any-selector-present? driver selectors)
                         #(frame-text-present? driver))]
    (when-not (wait-until source-cfg content-ready?)
      (throw (ex-info "Timed out waiting for page content"
                      {:type :alida.source.webdriver/page-content-timeout
                       :canonical-url url
                       :current-url current-url
                       :selectors selectors})))))

(defn- blank-html?
  [html]
  (str/blank? (.text (Jsoup/parse (or html "")))))

(defn- iframe-present?
  [driver]
  (pos? (long (or (e/js-execute driver "return document.querySelectorAll('iframe').length;")
                  0))))

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
                 return {text, hrefCount};"))

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

(defn- wait-for-frame-content!
  [driver source-cfg expected-title outer-url]
  (wait-until source-cfg #(frame-content-ready? driver expected-title))
  ((:await-extra-frame-content! (profile source-cfg)) driver source-cfg outer-url))

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
          (pause! pause-ms)
          (let [current-url (or (e/get-url driver) previous-url)]
            (if (= current-url previous-url)
              (let [stable-count (inc stable-count)]
                (if (>= stable-count required-stable)
                  current-url
                  (recur (dec remaining) current-url stable-count)))
              (recur (dec remaining) current-url 0))))))))

(defn- profile-extras
  "Run the profile's in-page metadata extraction, returning {:description
   :fallback_url} or nil. No-op (and no extra round-trip) for profiles without
   an :extras-js."
  [driver profile-map]
  (when-let [js (:extras-js profile-map)]
    (let [result (e/js-execute driver js)]
      {:description (or (get result "description") (:description result))
       :fallback_url (or (get result "fallbackUrl") (:fallbackUrl result))})))

(defn- execute-cleanup
  [driver source-cfg description]
  (e/js-execute driver
                cleanup-script
                (remove-selectors source-cfg)
                description))

(defn- page-info
  [driver]
  (let [result (e/js-execute driver
                             "const hrefs = new Set();
                              document.querySelectorAll('a[href]').forEach(a => {
                                const href = a.href || a.getAttribute('href');
                                if (href) hrefs.add(href);
                              });
                              return {
                                url: window.location.href,
                                title: document.title || '',
                                hrefs: Array.from(hrefs),
                                iframeUrl: document.querySelector('iframe')?.src || null
                              };")
        hrefs (or (get result "hrefs") (:hrefs result) [])]
    {:url (or (get result "url") (:url result) (e/get-url driver))
     :title (or (get result "title") (:title result))
     :body ""
     :hrefs hrefs
     :fallback_url nil
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
     :fallback_url nil}))

(defn- render-current-frame
  [driver source-cfg]
  (let [extras (profile-extras driver (profile source-cfg))
        page (cleanup-result->page driver (execute-cleanup driver source-cfg (:description extras)))]
    (cond-> page
      (:fallback_url extras) (assoc :fallback_url (:fallback_url extras)))))

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

(defn- outer-shell
  "Capture the outer page before switching into its iframe: generic link/title
   info plus any profile fallback URL (read from the top frame, where in-page
   metadata such as a JSM jsonPayload lives)."
  [driver source-cfg]
  (let [extras (profile-extras driver (profile source-cfg))]
    (cond-> (page-info driver)
      (:fallback_url extras) (assoc :fallback_url (:fallback_url extras)))))

(defn- render-current-page
  [driver source-cfg]
  (let [had-iframe? (iframe-present? driver)
        shell (when had-iframe? (outer-shell driver source-cfg))]
    (if had-iframe?
      (if-let [iframe (render-first-iframe driver source-cfg (:title shell) (:url shell))]
        (if (blank-html? (:body iframe))
          ;; The iframe src comes from untrusted rendered content; only navigate
          ;; to it when it passes the source allow/deny rules (SSRF guard).
          (if-let [iframe-url (let [u (:iframe_url shell)]
                                (when (navigable? source-cfg u) u))]
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
  (e/go driver url)
  (wait-for-page! driver source-cfg url)
  (render-current-page driver source-cfg))

(defn- rendered-page
  [driver source-cfg url]
  (e/set-page-load-timeout driver (or (:page_load_timeout_seconds source-cfg)
                                      default-page-load-timeout-seconds))
  (let [profile-map (profile source-cfg)
        initial (go-and-render driver source-cfg url)
        ;; the profile decides whether a blank render should retry at another
        ;; URL (e.g. JSM's jsonPayload kbPage); the candidate is still subject to
        ;; the SSRF/scope guard because it comes from untrusted page content.
        fallback-url (let [u ((:blank-fallback-url profile-map) source-cfg url initial)]
                       (when (navigable? source-cfg u) u))
        rendered (if fallback-url
                   (assoc (go-and-render driver source-cfg fallback-url)
                          :url (:url initial)
                          :fallback_url fallback-url)
                   initial)
        canonical-url (or (some-> (stabilized-url driver url source-cfg)
                                  (url/normalize url))
                          url)
        result-url (or (:url rendered) canonical-url)
        canonical-url (or (url/normalize canonical-url result-url) canonical-url)
        external-id (url/article-id canonical-url)
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
                 (keep #(url/normalize canonical-url %))
                 ((:transform-hrefs profile-map) canonical-url)
                 distinct
                 vec)}))

(defn- render-page!
  [driver source-cfg url]
  (try
    (rendered-page driver source-cfg url)
    (catch Exception e
      (source/anomaly :cognitect.anomalies/fault
                      {:type :alida.source.webdriver/render-failed
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

(defn- render-with-restarts!
  [driver source-cfg url pages-in-session consecutive-failures]
  (let [restart-after-pages (browser-restart-after-pages source-cfg)
        restart-for-page-limit? (and (pos? restart-after-pages)
                                     (>= pages-in-session restart-after-pages))
        _ (when restart-for-page-limit?
            (reset! driver (restart-driver! @driver source-cfg :page-limit)))
        pages-in-session (if restart-for-page-limit? 0 pages-in-session)
        page (render-page! @driver source-cfg url)
        ;; A blank render is re-tried once in place, but the browser is NOT
        ;; restarted: a blank here means the page's content never materialized
        ;; (e.g. a JSM article whose body only renders under its other URL
        ;; variant), which a fresh browser reproduces identically. Restarting
        ;; just paid a full Chrome startup for nothing. The same article's
        ;; sibling URL variant, or a later queue retry, supplies the content.
        blank-render? (blank-page? page)
        _ (when blank-render?
            (u/log ::blank-render
                   :source-id (:id source-cfg)
                   :canonical-url (or (:canonical_url page) url)
                   :requested-url url))
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
            (reset! driver (restart-driver! @driver source-cfg :consecutive-failures)))
        pages-in-session (if restart-for-failures? 0 pages-in-session)
        consecutive-failures (if restart-for-failures? 0 consecutive-failures)]
    {:page page
     :pages-in-session pages-in-session
     :consecutive-failures consecutive-failures}))

(defn- discover-rendered-sequential
  [_sys source-cfg]
  (let [starts (source/source-urls source-cfg)]
    (when-not (seq starts)
      (throw (ex-info "WebDriver source requires url, start_url, or start_urls"
                      {:type :alida.source.webdriver/missing-url
                       :source-id (:id source-cfg)})))
    (let [driver (atom (start-driver! source-cfg))]
      (try
        (loop [queue (into clojure.lang.PersistentQueue/EMPTY starts)
               queued (set starts)
               visited #{}
               attempts {}
               pages []
               pages-in-session 0
               consecutive-failures 0]
          (cond
            (or (empty? queue)
                (>= (count pages) (max-pages source-cfg)))
            pages

            (contains? visited (peek queue))
            (recur (pop queue) queued visited attempts pages pages-in-session consecutive-failures)

            :else
            (let [url (peek queue)
                  queue (pop queue)
                  render-result (render-with-restarts! driver
                                                       source-cfg
                                                       url
                                                       pages-in-session
                                                       consecutive-failures)
                  page (:page render-result)
                  pages-in-session (:pages-in-session render-result)
                  consecutive-failures (:consecutive-failures render-result)
                  attempt (inc (get attempts url 0))]
              (if (retry-render? source-cfg page attempt)
                (do
                  (u/log ::render-retry
                         :source-id (:id source-cfg)
                         :canonical-url url
                         :attempt attempt)
                  (recur (conj queue url) queued visited (assoc attempts url attempt)
                         pages pages-in-session consecutive-failures))
                (let [visited (conj visited url)
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
                  (recur queue queued visited attempts pages pages-in-session consecutive-failures))))))
        (finally
          (safe-quit-driver! @driver))))))

(defn- initial-render-state
  [starts]
  {:queue (into clojure.lang.PersistentQueue/EMPTY starts)
   :queued (set starts)
   :visited #{}
   :attempts {}
   :pages []
   :active 0
   :done? false})

(defn- take-next-url!
  [lock state max-pages]
  (locking lock
    (loop []
      (let [{:keys [queue pages active done? visited]} @state]
        (cond
          done?
          nil

          (>= (count pages) max-pages)
          (do
            (swap! state assoc :done? true)
            (.notifyAll lock)
            nil)

          (>= (+ (count pages) active) max-pages)
          (do
            (.wait lock)
            (recur))

          (seq queue)
          (let [url (peek queue)]
            (if (contains? visited url)
              (do
                (swap! state assoc :queue (pop queue))
                (recur))
              (do
                (swap! state #(-> %
                                  (assoc :queue (pop queue))
                                  (update :active inc)))
                url)))

          (zero? active)
          (do
            (swap! state assoc :done? true)
            (.notifyAll lock)
            nil)

          :else
          (do
            (.wait lock)
            (recur)))))))

(defn- requeue-url!
  [lock state source-cfg url attempt]
  (u/log ::render-retry
         :source-id (:id source-cfg)
         :canonical-url url
         :attempt attempt)
  (swap! state #(-> %
                    (update :queue conj url)
                    (assoc-in [:attempts url] attempt)
                    (update :active dec)))
  (.notifyAll lock))

(defn- record-page!
  [lock state source-cfg url page]
  (let [{:keys [queue queued visited pages]} @state
        visited (conj visited url)
        canonical-url (:canonical_url page)
        visited (cond-> visited canonical-url (conj canonical-url))
        pages (conj pages page)
        page-count (count pages)
        [queue queued] (if (or (source/anomaly? page)
                               (>= page-count (max-pages source-cfg)))
                         [queue queued]
                         (enqueue-links source-cfg queue queued visited page))]
    (swap! state assoc
           :queue queue
           :queued queued
           :visited visited
           :pages pages)
    (swap! state update :active dec)
    (when (or (>= page-count (max-pages source-cfg))
              (and (empty? queue)
                   (zero? (:active @state))))
      (swap! state assoc :done? true))
    (.notifyAll lock)
    (when (log-progress? source-cfg page-count)
      (u/log ::discovery-progress
             :source-id (:id source-cfg)
             :pages page-count
             :queue-size (count queue)
             :errors (count (filter source/anomaly? pages))))))

(defn- complete-url!
  [lock state source-cfg url page]
  (locking lock
    (let [attempt (inc (get-in @state [:attempts url] 0))]
      (if (retry-render? source-cfg page attempt)
        (requeue-url! lock state source-cfg url attempt)
        (record-page! lock state source-cfg url page)))))

(defn- render-worker!
  [lock state source-cfg]
  (let [driver (atom (start-driver! source-cfg))]
    (try
      (loop [pages-in-session 0
             consecutive-failures 0]
        (when-let [url (take-next-url! lock state (max-pages source-cfg))]
          (let [{:keys [page pages-in-session consecutive-failures]}
                (try
                  (render-with-restarts! driver
                                         source-cfg
                                         url
                                         pages-in-session
                                         consecutive-failures)
                  (catch Exception e
                    {:page (source/anomaly
                            :cognitect.anomalies/fault
                            {:type :alida.source.webdriver/render-failed
                             :source-id (:id source-cfg)
                             :canonical_url url
                             :message (ex-message e)})
                     :pages-in-session 0
                     :consecutive-failures 0}))]
            (complete-url! lock state source-cfg url page)
            (recur pages-in-session consecutive-failures))))
      (finally
        (safe-quit-driver! @driver)))))

(defn- discover-rendered-parallel
  [_sys source-cfg starts]
  (let [lock (Object.)
        state (atom (initial-render-state starts))
        concurrency (max-concurrency source-cfg)]
    (cp/with-shutdown! [pool (cp/threadpool concurrency :name "alida-webdriver")]
      (dorun
       (doall
        (cp/upmap pool
                  (fn [_]
                    (render-worker! lock state source-cfg))
                  (range concurrency)))))
    (:pages @state)))

(defn discover-rendered
  [sys source-cfg]
  (let [starts (source/source-urls source-cfg)]
    (when-not (seq starts)
      (throw (ex-info "WebDriver source requires url, start_url, or start_urls"
                      {:type :alida.source.webdriver/missing-url
                       :source-id (:id source-cfg)})))
    (if (> (max-concurrency source-cfg) 1)
      (discover-rendered-parallel sys source-cfg starts)
      (discover-rendered-sequential sys source-cfg))))

(defn fetch-rendered
  [_sys source-cfg discovered-item]
  (cond
    (source/anomaly? discovered-item)
    discovered-item

    (:body discovered-item)
    (assoc discovered-item
           :content_type (or (:content_type discovered-item) "text/html"))

    :else
    (source/anomaly :cognitect.anomalies/fault
                    {:type :alida.source.webdriver/missing-rendered-body
                     :source-id (:id source-cfg)
                     :canonical-url (:canonical_url discovered-item)})))

(defmethod source/discover :webdriver
  [sys source-cfg]
  (discover-rendered sys source-cfg))

(defmethod source/fetch :webdriver
  [sys source-cfg discovered-item]
  (fetch-rendered sys source-cfg discovered-item))

(defmethod source/html-extraction-options :webdriver
  [source-cfg]
  (source/external-link-extraction-options source-cfg))
