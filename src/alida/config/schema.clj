(ns alida.config.schema)

(def DeterministicThresholds
  [:map
   [:max_removed_absolute {:optional true} :int]
   [:max_removed_percentage {:optional true} :double]
   [:max_changed_percentage {:optional true} :double]
   [:max_item_failure_percentage {:optional true} :double]
   [:max_empty_or_short_document_percentage {:optional true} :double]])

(def Chunking
  [:map {:closed true}
   [:max_input_tokens :int]
   [:max_tokens :int]
   [:safety_multiplier :double]])

(def IndexLanguages
  [:map {:closed true}
   [:allowed {:optional true} [:sequential :string]]
   [:fallback {:optional true} :string]])

(def SourceLanguage
  [:map {:closed true}
   [:mode {:optional true} [:enum "auto" "html" "detect" "configured"]]
   [:allowed {:optional true} [:sequential :string]]
   [:locale {:optional true} :string]
   [:fallback {:optional true} :string]
   [:html_selectors {:optional true} [:sequential :string]]])

(def Embedding
  [:map
   [:provider [:enum "openai" "azure-openai" "vertex-ai" "noop"]]
   [:model {:optional true} :string]
   [:deployment_name {:optional true} :string]
   [:endpoint {:optional true} :string]
   [:api_version {:optional true} :string]
   [:project {:optional true} :string]
   [:location {:optional true} :string]
   [:credentials_path {:optional true} :string]
   [:access_token {:optional true} :string]
   [:embedding_dimensions :int]
   [:max_batch_size {:optional true} :int]
   [:max_retries {:optional true} :int]
   [:retry_initial_ms {:optional true} :int]
   [:retry_jitter_ms {:optional true} :int]
   [:inter_batch_delay_ms {:optional true} :int]
   [:api_key {:optional true} :string]])

;; Per-type source schemas. Each is a closed map so that a misspelled or
;; type-inappropriate key (e.g. `sitemap_urll`, or `sitemap_url` on a local
;; source) fails validation at load time instead of being silently ignored.
;; The "at least one of url/start_url/start_urls" and sitemap/path requirements
;; are enforced in alida.config because they are OR-groups, not single keys.

(def ^:private common-source-entries
  ;; Keys read for every source type by the shared crawl pipeline.
  [[:id :string]
   [:language {:optional true} SourceLanguage]
   [:remove_selectors {:optional true} [:sequential :string]]
   [:strip_text {:optional true} [:sequential :string]]
   [:dedupe_content {:optional true} :boolean]
   [:dedupe_prefer_url_substrings {:optional true} [:sequential :string]]
   [:max_pages {:optional true} :int]
   [:max_concurrency {:optional true} :int]
   [:inter_request_delay_ms {:optional true} :int]])

(def ^:private url-crawl-entries
  [[:allowed_url_prefixes {:optional true} [:sequential :string]]
   [:denied_urls {:optional true} [:sequential :string]]
   [:denied_url_prefixes {:optional true} [:sequential :string]]])

(def ^:private start-url-entries
  [[:url {:optional true} :string]
   [:start_url {:optional true} :string]
   [:start_urls {:optional true} [:sequential :string]]])

(def ^:private webdriver-entries
  [[:content_wait_selectors {:optional true} [:sequential :string]]
   [:browser_args {:optional true} [:sequential :string]]
   [:browser_restart_after_pages {:optional true} :int]
   [:browser_restart_after_failures {:optional true} :int]
   [:progress_log_every_pages {:optional true} :int]
   [:internal_link_hosts {:optional true} [:sequential :string]]
   [:preserve_external_links {:optional true} :boolean]
   [:render_profile {:optional true} :string]
   [:page_load_timeout_seconds {:optional true} :int]
   [:wait_timeout_ms {:optional true} :int]
   [:wait_interval_ms {:optional true} :int]
   [:iframe_related_links_timeout_ms {:optional true} :int]
   [:url_stabilization_ms {:optional true} :int]
   [:url_stabilization_attempts {:optional true} :int]
   [:url_stabilization_stable_count {:optional true} :int]])

(defn- source-schema
  [type-values & entry-groups]
  (into [:map {:closed true}
         (into [:type] [(into [:enum] type-values)])]
        (apply concat entry-groups)))

(def WebsiteSource
  (source-schema ["website"]
                 common-source-entries
                 url-crawl-entries
                 [[:sitemap_url {:optional true} :string]
                  [:sitemap_urls {:optional true} [:sequential :string]]
                  [:max_sitemap_depth {:optional true} :int]]))

(def JiraServiceManagementSource
  (source-schema ["jira-service-management"]
                 common-source-entries
                 url-crawl-entries
                 start-url-entries
                 webdriver-entries
                 [[:crawl_method {:optional true} [:enum "api" "webdriver" "auto"]]
                  [:api_max_concurrency {:optional true} :int]
                  [:api_category_page_limit {:optional true} :int]]))

(def WebdriverSource
  (source-schema ["webdriver"]
                 common-source-entries
                 url-crawl-entries
                 start-url-entries
                 webdriver-entries))

(def LocalSource
  (source-schema ["local"]
                 common-source-entries
                 [[:path {:optional true} :string]
                  [:paths {:optional true} [:sequential :string]]
                  [:root {:optional true} :string]
                  [:include_extensions {:optional true} [:sequential :string]]]))

(def ObjectStorageSource
  (source-schema ["s3" "gcs"]
                 common-source-entries
                 [[:bucket {:optional true} :string]
                  [:prefix {:optional true} :string]
                  [:region {:optional true} :string]
                  [:project_id {:optional true} :string]
                  [:include_globs {:optional true} [:sequential :string]]
                  [:exclude_globs {:optional true} [:sequential :string]]]))

(def Source
  [:multi {:dispatch :type}
   ["website" WebsiteSource]
   ["jira-service-management" JiraServiceManagementSource]
   ["webdriver" WebdriverSource]
   ["local" LocalSource]
   ["s3" ObjectStorageSource]
   ["gcs" ObjectStorageSource]])

(def Verification
  [:map
   [:enabled {:optional true} :boolean]
   [:provider {:optional true} [:enum "openai" "azure-openai" "vertex-ai"]]
   [:model {:optional true} :string]
   [:deployment_name {:optional true} :string]
   [:endpoint {:optional true} :string]
   [:api_version {:optional true} :string]
   [:project {:optional true} :string]
   [:location {:optional true} :string]
   [:credentials_path {:optional true} :string]
   [:access_token {:optional true} :string]
   [:api_key {:optional true} :string]
   [:max_prompt_tokens {:optional true} :int]
   [:prompt_policy_version {:optional true} :string]
   [:deterministic_gate_version {:optional true} :string]
   [:deterministic_thresholds {:optional true} DeterministicThresholds]])

(def Notifications
  [:map {:closed true}
   [:slack_webhook_url {:optional true} :string]])

(def Database
  [:map
   [:jdbc_url :string]
   [:user {:optional true} :string]
   [:username {:optional true} :string]
   [:password {:optional true} :string]
   [:max_pool_size {:optional true} :int]])

(def MetadataStorage
  [:map
   [:type [:enum "postgres"]]
   [:jdbc_url :string]
   [:user {:optional true} :string]
   [:username {:optional true} :string]
   [:password {:optional true} :string]
   [:max_pool_size {:optional true} :int]])

(def VectorStorage
  [:map
   [:type [:enum "pgvector"]]])

(def Storage
  [:map
   [:metadata MetadataStorage]
   [:vectors VectorStorage]])

(def Index
  [:map {:closed true}
   [:name :string]
   [:auto_activate {:optional true} :boolean]
   [:languages {:optional true} IndexLanguages]
   [:embedding Embedding]
   [:chunking Chunking]
   [:sources [:sequential Source]]])

(def Config
  [:map {:closed true}
   [:database {:optional true} Database]
   [:storage {:optional true} Storage]
   [:verification Verification]
   [:notifications {:optional true} Notifications]
   [:indexes [:sequential Index]]])
