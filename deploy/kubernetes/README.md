# Kubernetes Deployment

These manifests are generic starting points for running Alida Vector as a
scheduled Kubernetes batch job.

## Files

- `configmap.example.yml`: non-secret Alida config placeholder.
- `secret.example.yml`: required secret keys.
- `namespace.yml`: dedicated namespace used by the example resources.
- `migrate-job.yml`: one-shot schema migration job.
- `cronjob.yml`: recurring crawl job.

Copy the example manifests into your deployment repository and replace the
placeholder image, config, and secret values there. Keep deployment-specific
configuration outside this repository.

## Image

Use an immutable image digest instead of a mutable tag:

```text
ghcr.io/fmw/alida-vector@sha256:...
```

If your cluster uses a private registry, mirror the image into that registry and
keep the digest pin in the workload manifest.

## Deployment Order

1. Create `namespace.yml`.
2. Create or update the ConfigMap and Secret.
3. Run `migrate-job.yml` once for the image/config version.
4. Apply `cronjob.yml`.

The CronJob uses `concurrencyPolicy: Forbid`. Alida also uses per-index
PostgreSQL advisory locks, which protects against accidental overlapping crawls
from another scheduler or manual run.

## Retry Policy

Alida retries transient embedding and LLM verification requests internally. A
provider `429` may occur in either phase and does not imply that fetching source
documents was rate-limited. If every failed index is still classified as
retryable after those attempts, `crawl` exits with status `75` (`EX_TEMPFAIL`).
Permanent or unclassified failures exit with status `1`.

The example CronJob combines `backoffLimit: 1` with a `podFailurePolicy`. This
retries the complete crawl once for status `75` and fails the Job immediately
for other non-zero statuses. Pods marked as a `DisruptionTarget`, such as during
a node drain, are replaced without consuming that application retry. A
replacement Pod starts a new candidate run; it does not resume the failed crawl
phase. See
[Deployment](../../docs/deployment.md#retry-layers-and-exit-status) for the full
behavior and fallback guidance for clusters without `podFailurePolicy` support.

Keep any deployment-specific `activeDeadlineSeconds` large enough to cover both
Pod attempts and their backoff. Consider setting `timeZone`,
`startingDeadlineSeconds`, and `ttlSecondsAfterFinished` explicitly in the
deployment repository as described in the main deployment guide.

The example workloads run as UID/GID `10001`, drop Linux capabilities, disable
privilege escalation and service-account token mounting, use the runtime-default
seccomp profile, and make the root filesystem read-only. Bounded `emptyDir`
volumes keep `/tmp` and `/var/cache/alida-vector` writable. Tune the example
resource requests, limits, and volume size limits for the configured crawl and
its browser concurrency before deployment.
