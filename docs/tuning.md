# Crawl Tuning

Before using Alida Vector for a production refresh, it is usually worth tuning the crawl configuration. The goal is to make sure the crawler indexes useful content and avoids navigation, footers, duplicate pages, forms, cookie banners, search pages, and other boilerplate.

For the complete configuration hierarchy, source selection, and connector
settings, see [Crawl Configuration](crawl-configuration.md). YAML examples in
this guide that begin with `sources:` are fragments belonging below an index's
`sources` key.

During this tuning phase, you can save time and API cost by disabling paid embedding and LLM verification calls. This should be temporary: for production refreshes, keep real embeddings and LLM verification enabled. Even trusted sources can change unexpectedly or be compromised.

## Disable Costly API Calls

Use the `noop` embedding provider while tuning extraction. It stores placeholder vectors with the configured dimensions, so the normal crawl, storage, diff, and report paths still run. Runs created this way cannot be activated.

Disable LLM verification with `verification.enabled: false`. The deterministic gate still runs and is stored, but no LLM API call is made.

```yaml
verification:
  enabled: false
  deterministic_thresholds:
    max_removed_absolute: 25
    max_removed_percentage: 0.2
    max_changed_percentage: 0.5
    max_item_failure_percentage: 0.05
    max_empty_or_short_document_percentage: 0.05

indexes:
  - name: docs-tuning
    auto_activate: false
    embedding:
      provider: noop
      embedding_dimensions: 1536
    chunking:
      max_input_tokens: 8192
      max_tokens: 6550
      safety_multiplier: 1.2
    sources:
      - id: website
        type: website
        sitemap_url: https://example.com/sitemap.xml
        allowed_url_prefixes:
          - https://example.com/
```

Run the crawl as usual:

```bash
alida-vector crawl --config config.yml --index docs-tuning
alida-vector report RUN_ID --config config.yml
```

## Configure Verifier Model Parameters

OpenAI and Azure OpenAI verification requests accept optional generation
parameters. Alida sends `temperature: 0` when no sampling or reasoning control
is configured. Some reasoning models only accept their default temperature;
set `temperature: null` to omit the parameter, or set the provider-supported
default explicitly. Configuring `reasoning_effort` or `verbosity` without a
temperature also leaves the temperature to the provider.

The [current GPT-5.6 family](https://developers.openai.com/api/docs/models)
uses `gpt-5.6-sol` for highest capability, `gpt-5.6-terra` for balanced
cost and performance, and `gpt-5.6-luna` for cost-sensitive, high-volume
workloads. Confirm the selected model in the
[Azure OpenAI model catalog](https://learn.microsoft.com/en-us/azure/foundry/foundry-models/concepts/models-sold-directly-by-azure?pivots=azure-openai)
before creating its deployment because availability varies by region.

```yaml
verification:
  provider: azure-openai
  endpoint: ${AZURE_OPENAI_ENDPOINT}
  deployment_name: reasoning-model
  # Semantic model identity used for verification attestation hashes. Keep this
  # stable across environments whose deployment aliases serve the same model.
  model: gpt-5.6-sol
  api_key: ${AZURE_OPENAI_API_KEY}
  temperature: null
  max_completion_tokens: 2048
  reasoning_effort: low
  verbosity: low
```

Supported parameters are `temperature` (0–2), `top_p` (0–1),
`max_completion_tokens`, `reasoning_effort` (`none`, `minimal`, `low`,
`medium`, `high`, or `xhigh`), and `verbosity` (`low`, `medium`, or `high`).
Configure either `temperature` or `top_p`, not both. Model support varies; the
provider returns an error when a configured parameter is unsupported by the
selected model. Set `max_completion_tokens` generously for reasoning models:
hidden reasoning tokens count against the limit and can exhaust it before the
model emits the JSON verdict.

## Blacklist Low-Value URLs

Use `denied_urls` for exact URLs that should never be indexed. Use `denied_url_prefixes` for groups of pages, such as internal search results, account flows, thank-you pages, or duplicate archive pages.

```yaml
sources:
  - id: website
    type: website
    sitemap_url: https://example.com/sitemap.xml
    allowed_url_prefixes:
      - https://example.com/docs/
      - https://example.com/blog/
    denied_urls:
      - https://example.com/docs/not-for-indexing/
      - https://example.com/blog/subscribed/
    denied_url_prefixes:
      - https://example.com/search/
      - https://example.com/account/
      - https://example.com/thank-you/
```

Prefer blacklisting pages that are clearly not useful for retrieval. Do not use broad prefixes until you have checked that they do not remove valuable content.

## Strip Page Chrome and Boilerplate

Use `remove_selectors` to remove HTML elements before text extraction. This is best for navigation, footers, cookie banners, forms, sidebars, and other repeated page chrome.

```yaml
sources:
  - id: website
    type: website
    sitemap_url: https://example.com/sitemap.xml
    remove_selectors:
      - header
      - footer
      - nav
      - form
      - aside
      - "[role=navigation]"
      - ".cookie-banner"
      - ".newsletter-signup"
      - ".site-search"
      - ".language-switcher"
```

Use `strip_text` for repeated text fragments that survive selector cleanup. Keep these entries specific enough that they cannot remove useful article text by accident.

```yaml
sources:
  - id: website
    type: website
    sitemap_url: https://example.com/sitemap.xml
    strip_text:
      - "Subscribe to our newsletter"
      - "Did this article help? Yes No"
      - "This site uses cookies"
```

A practical workflow is:

1. Crawl with `provider: noop` and `verification.enabled: false`.
2. Read the crawl report and inspect representative chunks.
3. Add blacklist rules for pages that should not be indexed.
4. Add `remove_selectors` for structural boilerplate.
5. Add `strip_text` only for repeated text that cannot be removed structurally.
6. Repeat until chunks contain coherent, useful content.
7. Switch back to a real embedding provider and enable LLM verification for the production run.

## Clean Up Tuning Runs

Tuning runs with noop embeddings are disposable. They use placeholder vectors and cannot be activated, but they still create run, document, chunk, report, and event rows.

After tuning, prune these runs:

```bash
alida-vector prune --config config.yml --disabled-embeddings
```

You can combine this with normal pruning criteria:

```bash
alida-vector prune --config config.yml --disabled-embeddings --older-than 7d
```

The prune command protects live runs, previous live runs, and in-progress runs. For disabled-embedding tuning runs, it removes the run data and drops the per-run vector partition.

Use `--index NAME` to limit manual pruning to one index. For recurring
production cleanup, see the opt-in
[crawl history retention](deployment.md#crawl-history-retention) setting.
