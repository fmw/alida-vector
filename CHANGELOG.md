# Changelog

## Unreleased

- Add opt-in age-based crawl-history retention after successful crawls.
- Allow manual pruning to be limited to one index with `--index`.

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
