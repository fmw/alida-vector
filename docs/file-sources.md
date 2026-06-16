# File Sources

Alida can crawl files from local paths, Amazon S3, and Google Cloud Storage.
These sources share the same fetch, content-type, text extraction, chunking, and
storage pipeline as website crawls.

Supported file content types are inferred from the object key or file
extension:

- `html`, `htm`: HTML extraction
- `md`, `markdown`: Markdown extraction
- `txt`: plain text extraction
- `json`: JSON extraction

Generic object-store content types such as `application/octet-stream` are
ignored when a known extension is present.

## Local Files

Use a local source for development fixtures, exported content, or mounted
volumes:

```yaml
sources:
  - id: local-docs
    type: local
    root: /data/docs
    include_extensions: [html, md, txt, json]
    include_globs:
      - public/*.json
      - public/**/*.json
      - public/*.md
      - public/**/*.md
    exclude_globs:
      - private/**
```

`path` and `paths` can be used for explicit files. `root` recursively discovers
files below a directory. For `root`, glob filters are evaluated against paths
relative to the root.

`include_extensions` is local-only and acts as a coarse extension filter before
glob filtering. When omitted, local `root` crawls include only HTML files.

## S3

S3 sources use AWS SDK default credentials. In production, prefer an IAM role
attached to the workload. For local development with AWS SSO or `aws login`,
export temporary credentials for the JVM process:

```bash
eval "$(aws configure export-credentials --format env)"
```

Example:

```yaml
sources:
  - id: s3-docs
    type: s3
    bucket: example-docs
    region: us-east-1
    prefix: docs/
    include_globs:
      - docs/*.html
      - docs/**/*.html
      - docs/*.md
      - docs/**/*.md
      - docs/*.json
      - docs/**/*.json
    exclude_globs:
      - docs/private/**
```

The crawler needs permission to list objects under the configured prefix and
read included objects.

## Google Cloud Storage

GCS sources use Application Default Credentials unless explicit credentials are
configured. In production on Google Cloud, prefer workload identity or the
runtime service account through ADC. Outside Google Cloud, point
`credentials_path` at a service-account JSON key:

```yaml
sources:
  - id: gcs-docs
    type: gcs
    bucket: example-docs
    project_id: example-project
    credentials_path: ${ALIDA_GCS_CREDENTIALS_PATH}
    prefix: docs/
    include_globs:
      - docs/*.html
      - docs/**/*.html
      - docs/*.md
      - docs/**/*.md
      - docs/*.json
      - docs/**/*.json
    exclude_globs:
      - docs/private/**
```

For short local debugging sessions, `access_token` can be used instead:

```yaml
access_token: ${ALIDA_GCS_ACCESS_TOKEN}
```

The crawler needs permission to list objects under the configured prefix and
read included objects. `roles/storage.objectViewer` on the bucket is sufficient
for read-only crawls.

## Glob Filters

`include_globs` and `exclude_globs` are shared by local, S3, and GCS sources.
For object stores, globs are evaluated against object keys. For local `root`
crawls, globs are evaluated against the root-relative file path.

When `include_globs` is empty or omitted, every discovered file or object is
included unless it matches `exclude_globs`.

Globs use Java `PathMatcher` semantics. A pattern such as `docs/**/*.json`
matches files in nested directories like `docs/guides/a.json`, but not direct
children like `docs/a.json`. Include both `docs/*.json` and `docs/**/*.json`
when you want both.

Use the source `prefix` for S3/GCS to reduce listing scope, then use globs for
file-type or subdirectory selection:

```yaml
prefix: docs/
include_globs:
  - docs/*.json
  - docs/**/*.json
exclude_globs:
  - docs/private/**
  - docs/drafts/**
```

## JSON HTML-Field Extraction

By default, JSON files are flattened into headings and key/value text. For JSON
exports that contain HTML fragments, use `json_extract.mode: html-fields` to
extract only matching typed fields and run their HTML through the normal HTML
extractor:

```yaml
sources:
  - id: exported-docs
    type: gcs
    bucket: example-docs
    prefix: exports/
    include_globs:
      - exports/*.json
      - exports/**/*.json
    language:
      mode: html
    json_extract:
      mode: html-fields
      field_type_key: type
      field_type_value: content_text
      html_field: content
      title_path: [title]
      locale_from_filename:
        pattern: "^([A-Z]{2})-"
        mappings:
          EN: en_US
          DE: de_DE
          FR: fr_FR
          NL: nl_NL
```

This configuration recursively scans each JSON document for maps where
`field_type_key` equals `field_type_value`, extracts the string in
`html_field`, strips HTML, and indexes the resulting text blocks.

`title_path` is optional and reads a document title from the parsed JSON.
`locale_from_filename` is optional and can provide language metadata from a
filename convention; use `language.mode: html` to prefer that metadata.

## Manual Fixture Config

Manual crawl configs often contain local paths, bucket names, credentials paths,
or test database URLs. Keep those files under the ignored `config/` directory
and commit only generic examples.
