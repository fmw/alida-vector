# GCS Fixture Crawl

This directory documents the manual verification flow for the GCS source
connector against a real Google Cloud Storage bucket.

The committed connector tests use mocked GCS calls. For a real-bucket test, use
an ignored config file under `config/` and sync whichever external fixture
directory you want to compare.

## Authentication

Use both CLI auth for bucket setup and Application Default Credentials for the
JVM process:

```bash
gcloud auth login
gcloud auth application-default login
gcloud config get-value project
```

## Bucket Setup

Pick a globally unique bucket name, a project, a region, and a prefix:

```bash
export ALIDA_GCS_FIXTURE_PROJECT=example-project
export ALIDA_GCS_FIXTURE_BUCKET=alida-gcs-fixtures-ACCOUNT_ID
export ALIDA_GCS_FIXTURE_LOCATION=us-central1
export ALIDA_GCS_FIXTURE_PREFIX=fixtures/docs/
export ALIDA_GCS_FIXTURE_SOURCE_DIR=/path/to/json/files
```

Check whether the bucket already exists:

```bash
gcloud storage buckets describe "gs://${ALIDA_GCS_FIXTURE_BUCKET}"
```

Create it only when the describe command reports that it does not exist:

```bash
gcloud storage buckets create "gs://${ALIDA_GCS_FIXTURE_BUCKET}" \
  --project="${ALIDA_GCS_FIXTURE_PROJECT}" \
  --location="${ALIDA_GCS_FIXTURE_LOCATION}" \
  --uniform-bucket-level-access
```

Check permissions:

```bash
gcloud storage ls "gs://${ALIDA_GCS_FIXTURE_BUCKET}/${ALIDA_GCS_FIXTURE_PREFIX}"
printf 'permission check\n' > /tmp/alida-gcs-permission-check.txt
gcloud storage cp /tmp/alida-gcs-permission-check.txt "gs://${ALIDA_GCS_FIXTURE_BUCKET}/${ALIDA_GCS_FIXTURE_PREFIX}.permission-check.txt"
gcloud storage cat "gs://${ALIDA_GCS_FIXTURE_BUCKET}/${ALIDA_GCS_FIXTURE_PREFIX}.permission-check.txt"
gcloud storage rm "gs://${ALIDA_GCS_FIXTURE_BUCKET}/${ALIDA_GCS_FIXTURE_PREFIX}.permission-check.txt"
```

Sync the fixture directory:

```bash
gcloud storage rsync --recursive --delete-unmatched-destination-objects \
  "${ALIDA_GCS_FIXTURE_SOURCE_DIR}" \
  "gs://${ALIDA_GCS_FIXTURE_BUCKET}/${ALIDA_GCS_FIXTURE_PREFIX}"
```

## Crawl

Create an ignored local config under `config/` for the manual crawl. Set:

```bash
export ALIDA_GCS_FIXTURE_PROJECT=example-project
export ALIDA_GCS_FIXTURE_BUCKET=alida-gcs-fixtures-ACCOUNT_ID
export ALIDA_GCS_FIXTURE_PREFIX=fixtures/docs/
export ALIDA_GCS_FIXTURE_DATABASE_URL=jdbc:postgresql://127.0.0.1:55432/alida
export ALIDA_GCS_FIXTURE_DATABASE_USER=fmw
export ALIDA_GCS_FIXTURE_DATABASE_PASSWORD=
```

Then run:

```bash
clojure -M -m alida.main migrate --config config/alida-gcs-fixture.yml
clojure -M -m alida.main crawl --config config/alida-gcs-fixture.yml --index gcs-fixtures
```
