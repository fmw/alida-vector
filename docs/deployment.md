# Deployment

Alida Vector is designed to run as a scheduled batch job. The Docker image contains the application jar, the `alida-vector` wrapper, a Java 21 runtime, Chromium, and Chromedriver for browser-backed source connectors.

## Build the Image

The Dockerfile builds the application jar in a builder stage and copies only the
jar plus runtime wrapper into the final image:

```bash
docker build -t alida-vector:local .
```

Base images are pinned by digest. Refresh those digests intentionally during
dependency maintenance so a rebuild cannot silently pick up a retargeted base
tag.

Use your registry tag when building for deployment:

```bash
docker build -t registry.example.com/alida-vector:2026-06-11 .
```

The GitHub Actions image workflow publishes images to GHCR on `main` and
version tags. Prefer deploying an immutable image digest instead of a mutable
tag:

```text
ghcr.io/OWNER/alida-vector@sha256:...
```

The image runs as a non-root `alida` user. The default command is:

```bash
alida-vector crawl --config /config/alida.yml
```

Pass explicit command arguments to override the default:

```bash
docker run --rm alida-vector:local help
docker run --rm -v "$PWD/config:/config:ro" alida-vector:local migrate --config /config/alida.yml
docker run --rm -v "$PWD/config:/config:ro" alida-vector:local crawl --config /config/alida.yml
```

Mount a config file or directory at `/config`. The image also creates writable
`/var/cache/alida-vector` and `/tmp/alida-vector` directories for runtime use.
The image points `HOME` and `TMPDIR` there, and its entrypoint recreates the
directory when a `/tmp` mount hides the image contents. ChromeDriver keeps each
session's temporary profile and cache below that directory and removes them when
the browser quits. Mount `/tmp/alida-vector` (or its `/tmp` parent) as writable
when the root filesystem is read-only.

## Runtime Environment

Secrets should come from environment variables or Kubernetes Secrets, never from committed YAML.

Common environment variables:

- `ALIDA_DATABASE_URL`
- `ALIDA_DATABASE_USER`
- `ALIDA_DATABASE_PASSWORD`
- `OPENAI_API_KEY`
- `AZURE_OPENAI_*`
- `GOOGLE_APPLICATION_CREDENTIALS`
- `ALIDA_SLACK_WEBHOOK_URL`

For file-source credential details, including S3 IAM roles and GCS service
accounts, see [File Sources](file-sources.md).

Container-specific environment variables:

- `ALIDA_VECTOR_JAR`: path to the jar used by the wrapper. Defaults to `/opt/alida-vector/alida-vector.jar` in the image.
- `JAVA_TOOL_OPTIONS`: JVM options, such as heap limits.
- `TMPDIR`: temporary-file directory. The image sets this to
  `/tmp/alida-vector` so Chromium and ChromeDriver stay inside the writable
  runtime directory.
- `ALIDA_CHROME_NO_SANDBOX`: explicitly disables Chromium's process sandbox
  when set to `true`. The supplied image sets this because its non-root
  container execution prevents Chromium's namespace and setuid sandboxes from
  starting, including under a default `docker run`. Set it to `false` only when
  the runtime supports Chromium's sandbox.
- `CHROME_BIN`: Chromium binary path.
- `CHROMEDRIVER_BIN`: Chromedriver binary path.

## Crawl History Retention

Run history is retained indefinitely by default. Opt in to automatic
age-based pruning with:

```yaml
retention:
  max_age_days: 30
```

After all selected indexes finish successfully, Alida removes eligible runs
whose `started_at` is older than the configured number of days. Automatic
pruning is limited to the indexes selected by that crawl, including when
`crawl --index NAME` is used. Omit `retention` entirely to keep automatic
pruning disabled.

The same protections as the manual `prune` command apply. Alida never removes:

- the current live run
- the previous live run retained for rollback
- a run that is still being crawled, embedded, or verified

Other terminal runs can become eligible, including completed candidates still
awaiting review, rejected runs, superseded runs, and failed runs. Choose
`max_age_days` long enough for the required review and incident-investigation
window. The per-run vector partition and the run's documents, diffs,
verifications, and reports are removed together. Audit events remain, with
their run reference cleared by the database foreign key.

Automatic pruning is skipped when any selected index fails, so it cannot
interfere with status `75` retry handling. If pruning itself fails after a
successful crawl, `crawl` returns status `1`; completed or activated runs are
not rolled back, and their successful summaries remain in the command output.
The example Kubernetes `podFailurePolicy` treats status `1` as permanent, so
the next scheduled crawl can attempt pruning again. A scheduler that retries
every non-zero status can instead repeat the completed crawl; use a
status-aware retry policy or disable whole-Job retries when that is undesirable.

Manual pruning remains available for one-off cleanup and supports additional
criteria:

```bash
alida-vector prune --config /config/alida.yml --older-than 30d
alida-vector prune --config /config/alida.yml --index docs --keep-last 10
```

Alida deliberately does not run `VACUUM` after pruning. The largest data is in
per-run partitions, and dropping an old partition releases that relation
directly while avoiding the vacuum overhead of a bulk delete. PostgreSQL
autovacuum should handle the smaller cascaded deletes in the metadata tables.
See PostgreSQL's guidance on
[partition maintenance](https://www.postgresql.org/docs/current/ddl-partitioning.html)
and [routine vacuuming](https://www.postgresql.org/docs/current/routine-vacuuming.html).

Do not use `VACUUM FULL` as routine post-crawl maintenance: it rewrites and
exclusively locks each target table. PostgreSQL also does not auto-analyze
partitioned parent tables, so deployments with changing data distributions
may separately schedule targeted `ANALYZE` based on observed query plans. That
planner maintenance is independent of crawl-history pruning.

## Kubernetes CronJob

Run `migrate` during deployment before scheduling recurring crawls, or use an
init job controlled by your deployment system. The recurring CronJob should run
`crawl` against mounted config and injected secrets.

When multiple environments should reuse an identical LLM verification, migrate
every metadata database and configure the later environment as described in
[Verification Attestations](verification-attestations.md). Give the later
environment read-only credentials for each trusted metadata database.

Generic Kubernetes manifests are available in
[`deploy/kubernetes`](../deploy/kubernetes). Copy them into your deployment
repository and replace the placeholder image digest, config, and secret values
there.

Use `concurrencyPolicy: Forbid` so the scheduler does not start overlapping crawls. Alida also takes per-index PostgreSQL advisory locks, which protects against manual or multi-cluster overlap.

### Retry layers and exit status

Alida makes retryable external HTTP calls in three phases:

| Phase | External call | State when the call fails |
| --- | --- | --- |
| Crawling | Fetch website sitemaps/pages or Jira knowledge-base data. | The candidate has not yet been persisted. Individual page failures can be recorded as crawl errors; fatal discovery failures stop the run. |
| Embedding | Create vectors for chunks that could not be reused. | The candidate has not yet been persisted. |
| Verification | Ask an LLM to review the completed candidate and its diff. | Documents and vectors are persisted, but the candidate is not activated. |

A provider `429` can therefore come from either embedding or LLM verification;
it does not necessarily come from fetching source documents. In particular, a
verification `429` occurs near the end of a run, after crawling and persistence
but before the live index can change.

Source HTTP, embedding, and verification calls are retried independently inside
Alida before `crawl` returns. HTTP `429` and `5xx` responses, along with
transport I/O failures, use exponential backoff. Alida honors a `Retry-After`
response header when it asks for a longer delay. Configure each call site's
maximum attempt count, initial delay, jitter, and maximum delay with
`max_retries`, `retry_initial_ms`, `retry_jitter_ms`, and
`retry_max_delay_ms`. The maximum delay defaults to 60 seconds and also bounds
untrusted `Retry-After` values. Source request settings live on a `website` or
`jira-service-management` source; provider request settings live in the
corresponding embedding or verification section.

After those retries are exhausted, `crawl` returns one of these statuses:

| Status | Meaning |
| --- | --- |
| `0` | Every selected index completed successfully. |
| `75` | At least one index failed and every failure was classified as retryable. |
| `1` | At least one failure was permanent or could not be classified as retryable. |

Status `75` follows the conventional `EX_TEMPFAIL` value. It lets a scheduler
retry temporary source, provider, or network failures without retrying invalid
configuration or other permanent processing failures. When multiple indexes run
together, any permanent or unclassified failure takes precedence and produces
status `1`.

A Kubernetes retry starts `crawl` again from the beginning and creates a new
candidate run; it does not resume the failed phase. The current live index stays
unchanged when a candidate fails. Keep whole-Job retries bounded, since each
replacement Pod repeats source discovery and processing even when embeddings
can be reused.

The example CronJob retries status `75` once. Its `podFailurePolicy` immediately
fails the Job for any other non-zero status:

```yaml
jobTemplate:
  spec:
    backoffLimit: 1
    podFailurePolicy:
      rules:
        - action: Ignore
          onPodConditions:
            - type: DisruptionTarget
        - action: FailJob
          onExitCodes:
            containerName: alida-vector
            operator: NotIn
            values: [75]
```

Policy rules are evaluated in order. The first rule replaces a Pod marked as a
`DisruptionTarget` (for example during a node drain) without consuming the
application retry budget. The second rule fails permanent application errors
immediately. Exit status `75` matches neither rule, so the Job controller counts
it toward `backoffLimit` and retries it once.

If the cluster does not support `podFailurePolicy`, `backoffLimit: 1` still
provides one bounded retry, but it applies to every non-zero exit status.

### Kubernetes operational bounds

The Pod template must use `restartPolicy: Never` with `podFailurePolicy`.
Retries are replacement Pods in the same Job, so configure the Job and CronJob
as a unit:

- Keep `backoffLimit` small. External calls already exhausted their in-process
  retries before status `75` was returned.
- Set `activeDeadlineSeconds` high enough to cover every Pod attempt and the Job
  controller's backoff, but low enough to terminate a genuinely stuck Job.
- Use `concurrencyPolicy: Forbid` to prevent the next schedule from overlapping
  a Job that is still running or retrying.
- Set `startingDeadlineSeconds` when a crawl should not start long after its
  scheduled time following controller downtime.
- Set `timeZone` explicitly when the schedule is tied to a local operational
  window; otherwise interpret the schedule according to the cluster's CronJob
  timezone behavior.
- Use `ttlSecondsAfterFinished` together with successful and failed Job history
  limits to retain enough evidence for debugging without keeping Pods forever.
- Size container memory and `JAVA_TOOL_OPTIONS` together so the JVM heap leaves
  room for Chromium and native allocations when browser-backed sources are in
  use.

The generic manifest provides conservative retry and security defaults,
including a read-only root filesystem, explicit non-root UID/GID, dropped
capabilities, bounded writable volumes, and starter resource requests and
limits. Tune workload-specific deadlines, resources, and volume limits in the
deployment repository.
