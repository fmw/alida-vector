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
