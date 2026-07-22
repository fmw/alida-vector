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
- `CHROME_BIN`: Chromium binary path.
- `CHROMEDRIVER_BIN`: Chromedriver binary path.

## Kubernetes CronJob

Run `migrate` during deployment before scheduling recurring crawls, or use an
init job controlled by your deployment system. The recurring CronJob should run
`crawl` against mounted config and injected secrets.

Generic Kubernetes manifests are available in
[`deploy/kubernetes`](../deploy/kubernetes). Copy them into your deployment
repository and replace the placeholder image digest, config, and secret values
there.

Use `concurrencyPolicy: Forbid` so the scheduler does not start overlapping crawls. Alida also takes per-index PostgreSQL advisory locks, which protects against manual or multi-cluster overlap.

### Retry layers and exit status

Alida can call external model providers in two separate phases:

| Phase | Provider call | State when the call fails |
| --- | --- | --- |
| Embedding | Create vectors for chunks that could not be reused. | The candidate has not yet been persisted. |
| Verification | Ask an LLM to review the completed candidate and its diff. | Documents and vectors are persisted, but the candidate is not activated. |

A provider `429` can therefore come from either embedding or LLM verification;
it does not necessarily come from fetching source documents. In particular, a
verification `429` occurs near the end of a run, after crawling and persistence
but before the live index can change.

Embedding and verification calls are retried independently inside Alida before
`crawl` returns. HTTP `429` and `5xx` responses, along with transport I/O
failures, use exponential backoff. Alida honors a provider's `Retry-After`
response header when it asks for a longer delay. Configure each provider's
maximum attempt count, initial delay, and jitter with `max_retries`,
`retry_initial_ms`, and `retry_jitter_ms` in the corresponding embedding or
verification section.

After those retries are exhausted, `crawl` returns one of these statuses:

| Status | Meaning |
| --- | --- |
| `0` | Every selected index completed successfully. |
| `75` | At least one index failed and every failure was classified as retryable. |
| `1` | At least one failure was permanent or could not be classified as retryable. |

Status `75` follows the conventional `EX_TEMPFAIL` value. It lets a scheduler
retry temporary provider or network failures without retrying invalid
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
        - action: FailJob
          onExitCodes:
            containerName: alida-vector
            operator: NotIn
            values: [75]
```

If the cluster does not support `podFailurePolicy`, `backoffLimit: 1` still
provides one bounded retry, but it applies to every non-zero exit status.

### Kubernetes operational bounds

The Pod template must use `restartPolicy: Never` with `podFailurePolicy`.
Retries are replacement Pods in the same Job, so configure the Job and CronJob
as a unit:

- Keep `backoffLimit` small. Provider calls already exhausted their in-process
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

The generic manifest intentionally provides conservative retry behavior but
leaves workload-specific deadlines and resources to the deployment repository.
