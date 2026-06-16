# GCS Fixture Crawl

This directory documents the manual verification flow for the GCS source
connector against a real Google Cloud Storage bucket.

The committed connector tests use mocked GCS calls. For a real-bucket test, use
an ignored config file under `config/` and sync whichever external fixture
directory you want to compare.

## Authentication

Use CLI auth for bucket setup:

```bash
gcloud auth login
gcloud config get-value project
```

For the JVM crawl process, prefer a service account. Grant it read access to
the bucket and point the ignored config at its JSON key with `credentials_path`:

```bash
export ALIDA_GCS_FIXTURE_SERVICE_ACCOUNT=alida-gcs-crawler
export ALIDA_GCS_FIXTURE_SERVICE_ACCOUNT_EMAIL="${ALIDA_GCS_FIXTURE_SERVICE_ACCOUNT}@${ALIDA_GCS_FIXTURE_PROJECT}.iam.gserviceaccount.com"
export ALIDA_GCS_FIXTURE_CREDENTIALS_PATH="${PWD}/config/alida-gcs-fixture-service-account.json"

gcloud iam service-accounts create "${ALIDA_GCS_FIXTURE_SERVICE_ACCOUNT}" \
  --project="${ALIDA_GCS_FIXTURE_PROJECT}" \
  --display-name="Alida GCS fixture crawler"

gcloud storage buckets add-iam-policy-binding "gs://${ALIDA_GCS_FIXTURE_BUCKET}" \
  --member="serviceAccount:${ALIDA_GCS_FIXTURE_SERVICE_ACCOUNT_EMAIL}" \
  --role="roles/storage.objectViewer"

gcloud iam service-accounts keys create "${ALIDA_GCS_FIXTURE_CREDENTIALS_PATH}" \
  --iam-account="${ALIDA_GCS_FIXTURE_SERVICE_ACCOUNT_EMAIL}" \
  --project="${ALIDA_GCS_FIXTURE_PROJECT}"
```

If Application Default Credentials are available in your runtime, omitting both
`credentials_path` and `access_token` also works:

```bash
gcloud auth application-default login
```

For quick local debugging only, a short-lived user access token can be supplied
with `access_token: ${ALIDA_GCS_FIXTURE_ACCESS_TOKEN}`.

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
export ALIDA_GCS_FIXTURE_CREDENTIALS_PATH="${PWD}/config/alida-gcs-fixture-service-account.json"
```

Then run:

```bash
clojure -M -m alida.main migrate --config config/alida-gcs-fixture.yml
clojure -M -m alida.main crawl --config config/alida-gcs-fixture.yml --index gcs-fixtures
```
