# Manual Fixture Verification

Automated tests should mock cloud providers, but source connectors also benefit
from occasional manual verification against real object storage. This guide
describes a generic lifecycle for those checks.

Keep manual fixture configuration in ignored files under `config/`. Do not
commit bucket names, local corpus paths, credential paths, test database URLs,
or private comparison data. PR descriptions should summarize manual verification
with generic terms such as "real S3 bucket", "real GCS bucket", "external test
database", and "external JSON fixture corpus".

## Checklist

1. Create or choose a disposable bucket.
2. Use a dedicated prefix, for example `fixtures/docs/`.
3. Grant the crawler read-only access to that bucket or prefix.
4. Sync a small fixture corpus.
5. Keep the crawl config under `config/`.
6. Run `migrate` against the chosen test database.
7. Run `crawl` with `auto_activate: false`.
8. Verify discovered object count, document count, chunk count, error count, and
   language distribution.
9. Compare extracted content volume with the expected source, if applicable.
10. Prune disposable runs and clean up cloud resources when the fixture is no
    longer needed.

## Storage Credentials

For S3, prefer an IAM role in production. For local manual checks, authenticate
with the AWS CLI and export temporary credentials for the JVM process when your
session uses SSO:

```bash
aws sts get-caller-identity --query 'Arn' --output text
eval "$(aws configure export-credentials --format env)"
```

For GCS, prefer a service account for production-shaped manual checks. Grant it
read-only bucket access and point the ignored config at its JSON key with
`credentials_path`. Workload identity or runtime service-account ADC is
preferred in production on Google Cloud.

## Fixture Data

Use fixture data that is representative of the real shape you need to crawl:

- direct files under the prefix
- nested files under the prefix
- files excluded by `exclude_globs`
- every file type expected in production
- JSON exports that exercise configured `json_extract` behavior

Keep fixture corpora small enough that manual runs are fast and cheap. Large or
private corpora should stay outside the repository and should be described
generically in PR text.

## Ignored Config

Manual configs commonly use noop embeddings and disabled LLM verification:

```yaml
verification:
  enabled: false
  provider: openai
  model: gpt-5.6-terra
  api_key: test
  reasoning_effort: low

indexes:
  - name: fixture-crawl
    auto_activate: false
    embedding:
      provider: noop
      embedding_dimensions: 1536
```

Use `auto_activate: false` for fixture runs. Noop vectors are useful for testing
the crawl, extraction, diff, and persistence path, but should not become the
live index.

## Commands

Run migrations once per test database:

```bash
clojure -M -m alida.main migrate --config config/alida-fixture.yml
```

Run the crawl:

```bash
clojure -M -m alida.main crawl --config config/alida-fixture.yml --index fixture-crawl
```

Inspect recent runs:

```bash
clojure -M -m alida.main runs --config config/alida-fixture.yml --index fixture-crawl
```

Print a run report:

```bash
clojure -M -m alida.main report --config config/alida-fixture.yml RUN_ID
```

Prune disposable noop runs:

```bash
clojure -M -m alida.main prune --config config/alida-fixture.yml --disabled-embeddings
```

## Result Summary

When reporting manual verification in a PR, keep it generic and include the
behavioral facts:

- provider and credential mode, for example GCS with `credentials_path`
- object count synced under the fixture prefix
- discovered count
- fetch error count
- document count
- chunk count
- empty-document count
- language distribution when language metadata matters
- whether the run was activated

Avoid public references to private bucket names, local source directories,
customer names, private database schemas, or private corpus titles.

## Cleanup

When the manual fixture is no longer needed:

- remove synced objects or delete the disposable bucket
- remove temporary service-account keys
- remove unnecessary IAM bindings or service accounts
- prune disposable Alida runs from the test database
- keep ignored local config files only if they are still useful
