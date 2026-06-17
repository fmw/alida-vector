# Kubernetes Deployment

These manifests are generic starting points for running Alida Vector as a
scheduled Kubernetes batch job.

## Files

- `configmap.example.yml`: non-secret Alida config placeholder.
- `secret.example.yml`: required secret keys.
- `migrate-job.yml`: one-shot schema migration job.
- `cronjob.yml`: recurring crawl job.

Copy the example manifests into your deployment repository and replace the
placeholder image, config, and secret values there. Keep deployment-specific
configuration outside this repository.

## Image

Use an immutable image digest instead of a mutable tag:

```text
ghcr.io/OWNER/alida-vector@sha256:...
```

If your cluster uses a private registry, mirror the image into that registry and
keep the digest pin in the workload manifest.

## Deployment Order

1. Create or update the ConfigMap and Secret.
2. Run `migrate-job.yml` once for the image/config version.
3. Apply `cronjob.yml`.

The CronJob uses `concurrencyPolicy: Forbid`. Alida also uses per-index
PostgreSQL advisory locks, which protects against accidental overlapping crawls
from another scheduler or manual run.

The manifests rely on the container's writable filesystem for temporary and
cache files. If your deployment sets `readOnlyRootFilesystem: true`, add
writable volumes for the required paths and set an appropriate pod security
context, such as `fsGroup`, so the non-root container user can write to them.
