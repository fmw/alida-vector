# Live Query Contract

Alida stores crawled content in run-specific chunk tables and exposes activated
content through stable live views. Client applications should read from the live
views instead of selecting from run partitions directly.

## Live Views

Supported embedding dimensions currently have separate views:

- `alida_live_chunks_1536`
- `alida_live_chunks_3072`

Each view contains only chunks from the active run for each index. Activating a
new run updates `alida_indexes.live_run_id`; clients that query the live view do
not need to know the current run id.

Common columns:

| Column | Description |
|--------|-------------|
| `index_name` | Logical index name from config |
| `run_id` | Active run id backing the live row |
| `document_id` | Source document row id |
| `source_id` | Source id from config |
| `canonical_url` | Stable document URL or object URI |
| `title` | Extracted or configured title |
| `locale` | Normalized language locale, when known |
| `content_hash` | Hash of the chunk content |
| `content` | Text chunk to pass to a retriever or model |
| `embedding` | pgvector embedding |
| `metadata` | Chunk metadata JSON |
| `heading_path` | Heading context for the chunk |
| `estimated_tokens` | Estimated token count for the chunk |

## Embedding Compatibility

Clients must query the view that matches the embedding dimensionality of their
query vectors. For example, a 1536-dimensional query vector should use
`alida_live_chunks_1536`.

The client is also responsible for generating query embeddings with the same
provider/model configuration used by the active Alida index. Alida records an
embedding fingerprint on runs for its own CLI search checks, but external
clients should treat the embedding model as part of their integration contract.

## Basic Search

Use cosine distance through pgvector's `<=>` operator:

```sql
SELECT
  index_name,
  source_id,
  canonical_url,
  title,
  locale,
  heading_path,
  content,
  embedding <=> :query_embedding::vector AS distance,
  1 - (embedding <=> :query_embedding::vector) AS score
FROM alida_live_chunks_1536
WHERE index_name = :index_name
ORDER BY embedding <=> :query_embedding::vector
LIMIT :limit;
```

Use the same query vector for the selected columns and `ORDER BY`. Parameter
names above are illustrative; use the placeholder style of your database
client.

## Filtering

Filter by language when the user or conversation locale is known:

```sql
SELECT
  canonical_url,
  title,
  locale,
  content,
  1 - (embedding <=> :query_embedding::vector) AS score
FROM alida_live_chunks_1536
WHERE index_name = :index_name
  AND locale = :locale
ORDER BY embedding <=> :query_embedding::vector
LIMIT :limit;
```

Filter by source when an index combines multiple crawlers:

```sql
SELECT
  source_id,
  canonical_url,
  title,
  content,
  1 - (embedding <=> :query_embedding::vector) AS score
FROM alida_live_chunks_1536
WHERE index_name = :index_name
  AND source_id = :source_id
ORDER BY embedding <=> :query_embedding::vector
LIMIT :limit;
```

Filter by metadata when a source stores additional structured fields:

```sql
SELECT
  canonical_url,
  title,
  content,
  metadata,
  1 - (embedding <=> :query_embedding::vector) AS score
FROM alida_live_chunks_1536
WHERE index_name = :index_name
  AND metadata->>'section' = :section
ORDER BY embedding <=> :query_embedding::vector
LIMIT :limit;
```

## Pending Runs

For validation tooling, query a specific candidate run before it is activated:

```sql
SELECT
  r.index_name,
  c.run_id,
  c.source_id,
  d.canonical_url,
  d.title,
  d.locale,
  c.heading_path,
  c.content,
  1 - (c.embedding <=> :query_embedding::vector) AS score
FROM alida_chunks_1536 c
JOIN alida_runs r ON r.id = c.run_id
JOIN alida_documents d ON d.id = c.document_id
WHERE c.run_id = :run_id
ORDER BY c.embedding <=> :query_embedding::vector
LIMIT :limit;
```

Application traffic should normally use the live views. Run-specific queries are
best kept for validation, debugging, and release checks.

## Access Pattern

Recommended client behavior:

1. Generate a query embedding with the same embedding model as the target index.
2. Select the live view for the embedding dimension.
3. Filter by `index_name`.
4. Apply optional filters such as `locale`, `source_id`, or metadata.
5. Order by `embedding <=> query_embedding`.
6. Pass the returned `content`, `title`, `canonical_url`, and `heading_path` to
   the consuming application.

Keep the selected columns explicit. Avoid `SELECT *` in clients so new metadata
columns can be added without changing result shapes unexpectedly.

## Permissions

Read-only clients should get `SELECT` on:

- the relevant `alida_live_chunks_*` view

Depending on the database role model, deployments may also grant `SELECT` on
the underlying `alida_indexes`, `alida_documents`, and relevant
`alida_chunks_*` table. Validation tools that query non-live runs need
`SELECT` on `alida_runs` and the relevant run chunk table.
