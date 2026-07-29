# Changelog

## 0.1.3 - 2026-07-29

- Fetch Jira Service Management article titles and rendered bodies from the
  Confluence v2 pages API, preserving useful link content in indexed documents.
- Restrict Confluence short-link resolution to configured source origins.
- Detect a complete page-API collapse and let `auto` mode fall back to the
  rendered crawler.
- Focus LLM verification on crawl correctness and safety instead of editorial
  quality.
- Include LLM reasoning in Slack notifications for caution and fail verdicts.

## 0.1.2 - 2026-07-23

- Preserve document-level added, changed, and moved metadata across batched LLM
  verification prompts so large crawl diffs are evaluated consistently.
- Add opt-in age-based crawl-history retention after successful crawls.
- Allow manual pruning to be limited to one index with `--index`.
- Harden the container identity and example Kubernetes batch workloads with
  explicit non-root security contexts, a read-only root filesystem, dropped
  capabilities, writable-volume bounds, and resource limits.

## 0.1.1 - 2026-07-22

- Return exit status `75` when every failed crawl index exhausted a retryable
  provider or transport error, allowing an orchestrator to distinguish
  temporary failures from permanent failures.
- Document application-level and Kubernetes Job retry behavior.
- Expand the Kubernetes CronJob example with one bounded retry for temporary
  failures while failing permanent errors immediately.
- Update the PostgreSQL JDBC driver to 42.7.13 to address a channel-binding
  authentication downgrade vulnerability.

## 0.1.0 - 2026-07-21

First release of Alida Vector.

- Crawl websites, JavaScript-rendered pages, Jira Service Management knowledge
  bases, local files, S3, and Google Cloud Storage.
- Extract, normalize, chunk, and embed content into PostgreSQL with pgvector.
- Build isolated candidate indexes and compare them with the current live run.
- Apply deterministic checks and optional LLM verification before activation.
- Inspect, activate, reject, roll back, search, and prune runs from the CLI.
- Deploy as a container or Kubernetes CronJob.
- Try the pipeline locally with the API-key-free Docker Compose quick start.

This is an early release. Configuration and CLI details may change as the project develops.
