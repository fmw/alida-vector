# S3 Fixture Crawl

This directory contains small fixture documents for manually verifying the S3
source connector against a real S3 bucket.

## Sync Fixtures

Authenticate with AWS first:

```bash
aws login
aws sts get-caller-identity --query 'Arn' --output text
```

Then create or update the fixture bucket:

```bash
bin/sync-s3-fixtures alida-vector-fixtures-ACCOUNT_ID us-east-1 alida-vector-fixtures/
```

The script:

- creates the bucket when it does not exist
- skips creation when the bucket already exists and is accessible
- checks list, write, read, and delete permissions
- syncs `dev-fixtures/s3/content/` to the configured S3 prefix

## Crawl Fixtures

The ignored local config `config/alida-s3-fixture.yml` is intended for manual
verification. It expects these environment variables:

```bash
export ALIDA_S3_FIXTURE_BUCKET=alida-vector-fixtures-ACCOUNT_ID
export ALIDA_S3_FIXTURE_REGION=us-east-1
export ALIDA_S3_FIXTURE_PREFIX=alida-vector-fixtures/
export ALIDA_S3_FIXTURE_DATABASE_URL=jdbc:postgresql://127.0.0.1:55432/alida
export ALIDA_S3_FIXTURE_DATABASE_USER=fmw
export ALIDA_S3_FIXTURE_DATABASE_PASSWORD=
```

If AWS CLI authentication is backed by AWS SSO or `aws login`, export temporary
credentials for the JVM process:

```bash
eval "$(aws configure export-credentials --format env)"
```

Then run:

```bash
clojure -M -m alida.main migrate --config config/alida-s3-fixture.yml
clojure -M -m alida.main crawl --config config/alida-s3-fixture.yml --index s3-fixtures
```

Expected result: 4 documents and 4 chunks. The `docs/private/ignored.md`
fixture should be excluded by `exclude_globs`.
