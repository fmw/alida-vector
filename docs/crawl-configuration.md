# Crawl Configuration

Alida Vector reads crawl configuration from YAML. A configuration contains one or more indexes, and every index contains one or more sources. Sources discover documents; the shared crawl pipeline then fetches, extracts, cleans, chunks, embeds, stores, compares, and verifies them.

This guide covers source selection and crawl behavior. For embedding, verification, and storage examples, start with [`resources/example-config.yml`](../resources/example-config.yml).

## Configuration Shape

Sources belong below `indexes[].sources[]`; `sources` is not a top-level key.
This is a complete configuration for a free tuning crawl of a sitemap-backed
website:

```yaml
storage:
  metadata:
    type: postgres
    jdbc_url: ${ALIDA_DATABASE_URL}
    user: ${ALIDA_DATABASE_USER}
    password: ${ALIDA_DATABASE_PASSWORD}
  vectors:
    type: pgvector

verification:
  enabled: false
  deterministic_thresholds:
    max_removed_absolute: 25
    max_removed_percentage: 0.2
    max_changed_percentage: 0.5
    max_item_failure_percentage: 0.05
    max_empty_or_short_document_percentage: 0.05

indexes:
  - name: documentation
    auto_activate: false
    languages:
      allowed: [en]
      fallback: en
    embedding:
      provider: noop
      embedding_dimensions: 1536
    chunking:
      max_input_tokens: 8192
      max_tokens: 6550
      safety_multiplier: 1.2
    sources:
      - id: public-docs
        type: website
        sitemap_url: https://docs.example.com/sitemap.xml
        allowed_url_prefixes:
          - https://docs.example.com/
        denied_url_prefixes:
          - https://docs.example.com/search/
        remove_selectors:
          - header
          - footer
          - nav
        language:
          mode: auto
```

The `noop` embedding provider and disabled LLM verification make this suitable for extraction tuning, but noop runs cannot be activated. Use a real embedding provider and enable verification for production refreshes. See [Crawl Tuning](tuning.md).

Source maps are strict: an unknown, misspelled, or source-inappropriate key causes configuration loading to fail instead of being silently ignored. Keep source IDs stable and unique within an index. The source ID forms part of a document's identity, so changing it makes existing documents appear removed and new documents appear added in the next diff.

## Run History Retention

Automatic pruning is opt-in. Add a top-level retention policy to remove
eligible non-live history after fully successful crawls:

```yaml
retention:
  max_age_days: 30
```

Omit `retention` to keep history until it is pruned manually. See
[Crawl History Retention](deployment.md#crawl-history-retention) for lifecycle
protections, failure behavior, and PostgreSQL maintenance guidance.

## Choose a Source Type

| Source type | Use it for | Required discovery setting |
| --- | --- | --- |
| `website` | HTML pages listed in one or more XML sitemaps | `sitemap_url` or `sitemap_urls` |
| `webdriver` | JavaScript-rendered sites discovered by following links | `url`, `start_url`, or `start_urls` |
| `jira-service-management` | Jira Service Management public knowledge bases | `url`, `start_url`, or `start_urls` |
| `local` | Explicit files or files below a mounted directory | `path`, `paths`, or `root` |
| `s3` | Files and exports in Amazon S3 | `bucket` |
| `gcs` | Files and exports in Google Cloud Storage | `bucket` |

Prefer `website` when a complete sitemap and server-rendered HTML are available. It is faster and less resource-intensive than a browser crawl. Use `webdriver` when relevant content or links appear only after JavaScript runs. Use the specialized Jira connector instead of generic WebDriver for Jira Service Management portals.

Indexes and their sources are processed in configuration order. Concurrency is applied within a source, not across sources or indexes.

## Shared Source Options

These settings participate in the shared crawl and extraction pipeline. Some limits are connector-specific; those exceptions are noted below.

| Setting | Purpose |
| --- | --- |
| `id` | Stable source identifier stored with documents and used in diffs. Required. |
| `language` | Controls HTML language metadata, detection, configured locale, and fallback behavior. |
| `remove_selectors` | CSS selectors removed before HTML text extraction. |
| `strip_text` | Exact repeated text fragments removed after extraction. |
| `json_extract` | Controls extraction from JSON files. See [File Sources](file-sources.md#json-html-field-extraction). |
| `dedupe_content` | When `true`, retains one document for identical normalized content. Defaults to `false`. |
| `dedupe_prefer_url_substrings` | Prefers duplicate documents whose URLs contain configured substrings. |
| `max_concurrency` | Controls shared fetch concurrency and, for browser crawls, the number of browser workers. Defaults vary by connector. |
| `inter_request_delay_ms` | Minimum delay between shared fetch starts for the same HTTP host. Defaults to `0`. |
| `max_pages` | Caps WebDriver, Jira, S3, and GCS discovery. Defaults to `1000` for those connectors. |

The shared fetch stage defaults to `max_concurrency: 20`. Generic WebDriver discovery instead defaults to one browser worker, while rendered Jira discovery defaults to five. An explicit `max_concurrency` is also used as the Jira API concurrency unless `api_max_concurrency` is configured separately.

`max_pages` is not currently applied by `website` or `local` sources. For a website, narrow `allowed_url_prefixes` or the sitemap itself. For local files, use explicit paths, `include_extensions`, and glob filters.

`inter_request_delay_ms` applies to the shared fetch stage. It does not throttle sitemap discovery, Jira API discovery, or browser navigation. Use connector concurrency settings to control those operations.

### HTTP request retries

`website` sources and the API path of `jira-service-management` sources retry
transient HTTP requests before reporting a fetch or discovery failure. The
retryable cases are HTTP `429`, HTTP `5xx`, and transport I/O failures.
`Retry-After` is honored when it asks for a longer delay, up to the configured
maximum delay. This bound prevents an invalid or hostile response header from
parking a crawl indefinitely.

| Setting | Default | Purpose |
| --- | --- | --- |
| `max_retries` | `3` | Maximum attempts, including the first request. |
| `retry_initial_ms` | `1000` | Delay before the second attempt; later delays use exponential backoff. |
| `retry_jitter_ms` | `250` | Maximum random jitter added to each delay. Use `0` to disable jitter. |
| `retry_max_delay_ms` | `60000` | Maximum sleep before any retry, including `Retry-After` and jitter. |

For example:

```yaml
sources:
  - id: documentation
    type: website
    sitemap_url: https://docs.example.com/sitemap.xml
    max_retries: 4
    retry_initial_ms: 1000
    retry_jitter_ms: 250
    retry_max_delay_ms: 60000
```

An individual page that still fails after these attempts remains a recoverable
crawl error. A fatal discovery request, such as a sitemap or Jira API listing,
ends the candidate run. Exhausted transient discovery failures are classified
as retryable so a status-aware scheduler can retry the complete crawl.

## URL Scope and Filtering

Web sources support three filters:

```yaml
allowed_url_prefixes:
  - https://docs.example.com/guides/
  - https://docs.example.com/reference/
denied_urls:
  - https://docs.example.com/guides/obsolete-page/
denied_url_prefixes:
  - https://docs.example.com/search/
  - https://docs.example.com/account/
```

These values are literal string prefixes, not regular expressions or globs. `denied_urls` matches exact URLs; `denied_url_prefixes` rejects everything below a prefix. Deny rules win over allow rules.

Always configure `allowed_url_prefixes` for `website` sources. When it is omitted, the website connector accepts every URL listed by the sitemap. An explicit allowlist both prevents accidental cross-domain crawling and limits the effect of an incorrect or compromised sitemap.

Generic WebDriver sources default their allowed prefixes to the origins of the configured start URLs. To crawl links on another host, add the intended URL prefixes to `allowed_url_prefixes`. Add the host to `internal_link_hosts` when it should also be treated as internal for link preservation and trusted iframe/profile navigation.

## Sitemap-Backed Websites

A website source discovers pages from XML `urlset` and `sitemapindex` documents. It does not follow links found in HTML pages.

```yaml
sources:
  - id: documentation
    type: website
    sitemap_urls:
      - https://docs.example.com/product-sitemap.xml
      - https://docs.example.com/blog-sitemap.xml
    allowed_url_prefixes:
      - https://docs.example.com/
    denied_url_prefixes:
      - https://docs.example.com/search/
    max_sitemap_depth: 10
    max_concurrency: 10
    inter_request_delay_ms: 100
    remove_selectors:
      - header
      - footer
      - nav
```

Use `sitemap_url` for one sitemap or `sitemap_urls` for several. Sitemap indexes may recursively reference child sitemaps on the same origin. The default `max_sitemap_depth` is `10`.

Discovered pages are fetched concurrently; shared fetch concurrency defaults to `20`. `inter_request_delay_ms` spaces page fetch starts per host, but does not delay sitemap requests. Website sources currently do not implement `max_pages`.

## JavaScript-Rendered Websites

The WebDriver connector starts Chromium at one or more seed URLs, renders each page, extracts links, and continues through allowed links until the queue is empty or `max_pages` is reached.

```yaml
sources:
  - id: application-help
    type: webdriver
    start_urls:
      - https://help.example.com/
    allowed_url_prefixes:
      - https://help.example.com/
    denied_url_prefixes:
      - https://help.example.com/login/
    max_pages: 500
    max_concurrency: 2
    content_wait_selectors:
      - main
      - article
    remove_selectors:
      - header
      - footer
      - nav
      - "[role=dialog]"
```

`url`, `start_url`, and `start_urls` are alternatives; when several are present, `start_urls` takes precedence, followed by `start_url`, then `url`.

Important WebDriver settings and defaults:

| Setting | Default | Purpose |
| --- | --- | --- |
| `max_pages` | `1000` | Maximum rendered pages returned by discovery. |
| `max_concurrency` | `1` | Parallel Chromium workers. Increase cautiously. |
| `page_load_timeout_seconds` | `30` | Browser page-load timeout. |
| `wait_timeout_ms` | `30000` | Maximum wait for page readiness and content. |
| `wait_interval_ms` | `100` | Readiness polling interval. |
| `content_wait_selectors` | none | Treat content as ready when any selector appears; otherwise wait for body text. |
| `browser_restart_after_pages` | `50` | Restart a worker after this many rendered pages. Use `0` to disable. |
| `browser_restart_after_failures` | `2` | Restart after consecutive rendering failures. Use `0` to disable. |
| `render_failure_retries` | `2` | Re-enqueue a failed URL this many times. |
| `progress_log_every_pages` | `25` | Emit progress after this many pages. Use `0` to disable. |
| `url_stabilization_ms` | `100` | Delay between URL stability checks after navigation. |
| `url_stabilization_attempts` | `20` | Maximum URL stability checks. |
| `url_stabilization_stable_count` | `2` | Equal consecutive URL observations required. |
| `preserve_external_links` | `true` | Preserve links to non-internal hosts as Markdown in extracted content without crawling them. |
| `internal_link_hosts` | start URL hosts | Additional trusted hosts that may be navigated. |
| `browser_args` | built-in headless arguments | Additional Chromium arguments appended to the defaults. |

Each browser worker needs its own Chromium process and consumes significant CPU and memory. Start with one or two workers and increase only after observing the runtime environment and target site.

`render_profile` selects a render adapter implemented in application code. Leave it unset for a generic WebDriver crawl; the Jira connector selects its specialized profile automatically.

## Jira Service Management

The Jira Service Management connector understands portal categories, topics, articles, and related Confluence-backed article URLs.

```yaml
sources:
  - id: support
    type: jira-service-management
    url: https://example.atlassian.net/servicedesk/customer/portal/1
    crawl_method: api
    allowed_url_prefixes:
      - https://example.atlassian.net/servicedesk/customer/portal/1/topic/
      - https://example.atlassian.net/servicedesk/customer/portal/1/article/
      - https://example.atlassian.net/plugins/servlet/servicedesk/customer/confluence/shim/
    max_pages: 1000
    api_max_concurrency: 20
    api_category_page_limit: 100
    dedupe_content: true
    dedupe_prefer_url_substrings:
      - /topic/
    remove_selectors:
      - footer
      - nav
      - "[role=dialog]"
```

`crawl_method` accepts:

- `api`: use the portal's API-backed discovery. This is the default and usually the fastest option.
- `webdriver`: render and discover the portal using Chromium.
- `auto`: try API discovery and fall back to Chromium when API discovery fails.

API discovery defaults to `api_max_concurrency: 20`,
`api_category_page_limit: 100`, and `max_pages: 1000`. If
`api_max_concurrency` is omitted, `max_concurrency` is used before falling back
to `20`. API mode expects an Atlassian Cloud-style portal origin whose
same-origin `/wiki/api/v2/pages/{id}` endpoint is anonymously readable. Custom
portal domains or instances that restrict that endpoint should use
`crawl_method: webdriver` or `crawl_method: auto`; `auto` falls back to the
rendered crawl when the page API is unavailable.

The Confluence page API supplies the indexed title and rendered body. Switching
an existing index from the legacy article representation can therefore produce
a one-time set of changed documents when page titles or rendered link content
differ, even though the portal URLs remain stable.

API and rendered Jira crawls preserve links to non-internal hosts as Markdown
by default, including standalone embedded-content links. Set
`preserve_external_links: false` to disable that Markdown conversion, or add
trusted hosts to `internal_link_hosts` when they should not be represented as
external links.

Rendered Jira crawls use the specialized Jira render profile and default to five browser workers. The generic WebDriver timeout, restart, retry, wait, cleanup, and URL-scope settings are also accepted. Use `content_wait_selectors` when a portal needs additional readiness signals. Related links inside Jira article iframes are given up to `iframe_related_links_timeout_ms` to stabilize; the default is `5000`.

As with website sources, configure `allowed_url_prefixes` explicitly. The Jira API connector does not derive an allowlist from the portal URL.

## Local, S3, and GCS Files

File sources support HTML, Markdown, plain text, and JSON. Local directories can be filtered by extension and root-relative globs; S3 and GCS object listings can be narrowed by prefix and object-key globs.

See [File Sources](file-sources.md) for configuration, credentials, glob behavior, supported content types, and JSON HTML-field extraction.

S3 and GCS discovery defaults to at most `1000` included objects. Configure `max_pages` to change that cap. Local sources have no `max_pages` cap; narrow a recursive `root` crawl using `include_extensions`, `include_globs`, and `exclude_globs`.

## Extraction and Cleanup

`remove_selectors` removes matching elements before HTML extraction. Alida already removes `script`, `style`, `noscript`, `meta`, `link`, `svg`, `canvas`, and `iframe` elements from ordinary HTML extraction. Add selectors for site-specific headers, navigation, footers, cookie dialogs, forms, and sidebars.

```yaml
remove_selectors:
  - header
  - footer
  - nav
  - form
  - aside
  - "[role=navigation]"
  - ".cookie-banner"
```

`strip_text` removes literal repeated fragments that remain after structural cleanup. It applies to HTML, Markdown, plain text, and JSON extraction. Prefer selectors where possible and keep text fragments specific:

```yaml
strip_text:
  - "Subscribe to our newsletter"
  - "Did this article help? Yes No"
```

## Language Configuration

Index-level settings constrain every source and provide a shared fallback:

```yaml
languages:
  allowed: [en, de, nl, fr]
  fallback: en
```

A source may narrow the allowed languages or choose how its language is found:

```yaml
language:
  mode: auto
  allowed: [en, nl]
  fallback: en
  html_selectors:
    - html[lang]
    - meta[property=og:locale]
```

Supported modes are:

- `auto` (default): use HTML language metadata, then content detection, then the
  configured fallback.
- `html`: use HTML or extracted file metadata, then the configured fallback.
- `detect`: detect the language from normalized content, then use the fallback.
- `configured`: assign `language.locale` to every document. `locale` is required
  in this mode.

Locale variants such as `en_US` and `en-GB` are normalized to their base language, such as `en`.

## Duplicate Content

Alida always collapses documents with the same connector-provided external ID. Set `dedupe_content: true` to also collapse documents whose normalized content is identical.

When several URLs represent the same document, use `dedupe_prefer_url_substrings` to choose the preferred URL:

```yaml
dedupe_content: true
dedupe_prefer_url_substrings:
  - /docs/canonical/
  - /topic/
```

The URL matching the most preferred substrings wins. Ties prefer the shorter URL.

## Environment Variables and Secrets

Every YAML string may contain `${VARIABLE_NAME}` references. Alida replaces them while loading the configuration and fails immediately if a referenced environment variable is missing.

```yaml
jdbc_url: ${ALIDA_DATABASE_URL}
api_key: ${OPENAI_API_KEY}
```

Keep credentials out of committed YAML. Supply them through the runtime environment, a container secret mechanism, an IAM role, workload identity, or Application Default Credentials as appropriate.

## Run and Inspect a Crawl

Commands use `alida.yml` by default. Pass `--config` when the file has another name or location. Run migrations once before the first crawl:

```bash
alida-vector migrate --config config.yml
alida-vector crawl --config config.yml --index documentation
alida-vector runs --config config.yml --index documentation
alida-vector report RUN_ID --config config.yml
```

Omit `--index` from `crawl` to run every configured index. Alida validates the entire configuration before executing a command, including indexes not selected by `--index`.

For a first real crawl, keep `auto_activate: false`. Inspect the report and search the candidate run before activating it:

```bash
alida-vector search-run RUN_ID "example question" --config config.yml
alida-vector activate RUN_ID --config config.yml
```

Noop-embedding runs are for tuning only and cannot be activated.

## Related Documentation

- [Crawl Tuning](tuning.md): iterate on filtering and extraction without paid API calls.
- [File Sources](file-sources.md): local, S3, GCS, glob, and JSON configuration.
- [Deployment](deployment.md): containers, runtime environment, and Kubernetes scheduling.
- [Live Query Contract](live-query-contract.md): query activated indexes from another application.
- [`resources/example-config.yml`](../resources/example-config.yml): a larger multi-source example.
