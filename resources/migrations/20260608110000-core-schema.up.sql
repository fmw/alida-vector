CREATE EXTENSION IF NOT EXISTS vector;
--;;
CREATE EXTENSION IF NOT EXISTS pgcrypto;
--;;

CREATE TABLE alida_indexes (
  name text PRIMARY KEY,
  live_run_id uuid,
  previous_live_run_id uuid,
  embedding_dimensions integer NOT NULL CHECK (embedding_dimensions > 0),
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now()
);
--;;

CREATE TABLE alida_runs (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  index_name text NOT NULL REFERENCES alida_indexes(name),
  lifecycle_status text NOT NULL CHECK (
    lifecycle_status IN ('created', 'crawling', 'embedding', 'verifying', 'complete', 'error', 'activated', 'rejected', 'superseded')
  ),
  verification_verdict text CHECK (verification_verdict IN ('pass', 'caution', 'fail')),
  embedding_dimensions integer NOT NULL CHECK (embedding_dimensions > 0),
  structural_config_hash text NOT NULL,
  started_at timestamptz NOT NULL DEFAULT now(),
  finished_at timestamptz,
  activated_at timestamptz,
  rejected_at timestamptz,
  error_summary text,
  metadata jsonb NOT NULL DEFAULT '{}'::jsonb
);
--;;

ALTER TABLE alida_indexes
  ADD CONSTRAINT alida_indexes_live_run_fk
  FOREIGN KEY (live_run_id) REFERENCES alida_runs(id);
--;;

ALTER TABLE alida_indexes
  ADD CONSTRAINT alida_indexes_previous_live_run_fk
  FOREIGN KEY (previous_live_run_id) REFERENCES alida_runs(id);
--;;

CREATE TABLE alida_sources (
  run_id uuid NOT NULL REFERENCES alida_runs(id) ON DELETE CASCADE,
  source_id text NOT NULL,
  source_type text NOT NULL,
  structural_config_hash text NOT NULL,
  document_count integer NOT NULL DEFAULT 0,
  error_count integer NOT NULL DEFAULT 0,
  metadata jsonb NOT NULL DEFAULT '{}'::jsonb,
  PRIMARY KEY (run_id, source_id)
);
--;;

CREATE TABLE alida_documents (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  run_id uuid NOT NULL REFERENCES alida_runs(id) ON DELETE CASCADE,
  source_id text NOT NULL,
  external_id text,
  canonical_url text NOT NULL,
  title text,
  locale text,
  normalized_content_hash text NOT NULL,
  raw_content_hash text,
  metadata jsonb NOT NULL DEFAULT '{}'::jsonb,
  created_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE (run_id, source_id, canonical_url)
);
--;;

CREATE INDEX alida_documents_run_source_idx
  ON alida_documents(run_id, source_id);
--;;

CREATE INDEX alida_documents_run_source_url_idx
  ON alida_documents(run_id, source_id, canonical_url);
--;;

CREATE INDEX alida_documents_normalized_hash_idx
  ON alida_documents(normalized_content_hash);
--;;

CREATE TABLE alida_run_diffs (
  run_id uuid PRIMARY KEY REFERENCES alida_runs(id) ON DELETE CASCADE,
  previous_run_id uuid REFERENCES alida_runs(id),
  summary jsonb NOT NULL DEFAULT '{}'::jsonb,
  added_urls jsonb NOT NULL DEFAULT '[]'::jsonb,
  removed_urls jsonb NOT NULL DEFAULT '[]'::jsonb,
  changed_urls jsonb NOT NULL DEFAULT '[]'::jsonb,
  moved_urls jsonb NOT NULL DEFAULT '[]'::jsonb,
  heuristic_security_findings jsonb NOT NULL DEFAULT '[]'::jsonb,
  created_at timestamptz NOT NULL DEFAULT now()
);
--;;

CREATE TABLE alida_verifications (
  run_id uuid PRIMARY KEY REFERENCES alida_runs(id) ON DELETE CASCADE,
  provider text NOT NULL,
  model text NOT NULL,
  deterministic_verdict text NOT NULL CHECK (deterministic_verdict IN ('pass', 'caution', 'fail')),
  deterministic_findings jsonb NOT NULL DEFAULT '[]'::jsonb,
  llm_verdict text NOT NULL CHECK (llm_verdict IN ('pass', 'caution', 'fail')),
  final_verdict text NOT NULL CHECK (final_verdict IN ('pass', 'caution', 'fail')),
  reasoning text,
  llm_security_findings jsonb NOT NULL DEFAULT '[]'::jsonb,
  raw_response jsonb NOT NULL DEFAULT '{}'::jsonb,
  created_at timestamptz NOT NULL DEFAULT now()
);
--;;

CREATE TABLE alida_verification_cache (
  normalized_content_hash text NOT NULL,
  verifier_provider text NOT NULL,
  verifier_model text NOT NULL,
  verifier_policy_version text NOT NULL,
  deterministic_gate_version text NOT NULL,
  alida_verification_version text NOT NULL,
  per_document_deterministic_findings jsonb NOT NULL DEFAULT '[]'::jsonb,
  llm_verdict text NOT NULL CHECK (llm_verdict IN ('pass', 'caution', 'fail')),
  llm_findings jsonb NOT NULL DEFAULT '[]'::jsonb,
  created_at timestamptz NOT NULL DEFAULT now(),
  last_used_at timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (
    normalized_content_hash,
    verifier_provider,
    verifier_model,
    verifier_policy_version,
    deterministic_gate_version,
    alida_verification_version
  )
);
--;;

CREATE TABLE alida_reports (
  run_id uuid PRIMARY KEY REFERENCES alida_runs(id) ON DELETE CASCADE,
  slack_summary text NOT NULL,
  full_report text NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now()
);
--;;

CREATE TABLE alida_events (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  run_id uuid REFERENCES alida_runs(id) ON DELETE SET NULL,
  index_name text,
  event_type text NOT NULL,
  actor text NOT NULL DEFAULT 'alida-vector',
  details jsonb NOT NULL DEFAULT '{}'::jsonb,
  created_at timestamptz NOT NULL DEFAULT now()
);
--;;

CREATE INDEX alida_events_run_idx
  ON alida_events(run_id);
--;;

CREATE INDEX alida_events_index_created_idx
  ON alida_events(index_name, created_at DESC);
--;;

CREATE TABLE alida_chunks_1536 (
  id uuid DEFAULT gen_random_uuid(),
  run_id uuid NOT NULL REFERENCES alida_runs(id) ON DELETE CASCADE,
  source_id text NOT NULL,
  document_id uuid NOT NULL REFERENCES alida_documents(id) ON DELETE CASCADE,
  chunk_index integer NOT NULL,
  chunk_count integer NOT NULL,
  content text NOT NULL,
  embedding vector(1536) NOT NULL,
  estimated_tokens integer NOT NULL,
  heading_path jsonb NOT NULL DEFAULT '[]'::jsonb,
  metadata jsonb NOT NULL DEFAULT '{}'::jsonb,
  created_at timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (run_id, id),
  UNIQUE (run_id, document_id, chunk_index)
) PARTITION BY LIST (run_id);
--;;

CREATE TABLE alida_chunks_3072 (
  id uuid DEFAULT gen_random_uuid(),
  run_id uuid NOT NULL REFERENCES alida_runs(id) ON DELETE CASCADE,
  source_id text NOT NULL,
  document_id uuid NOT NULL REFERENCES alida_documents(id) ON DELETE CASCADE,
  chunk_index integer NOT NULL,
  chunk_count integer NOT NULL,
  content text NOT NULL,
  embedding vector(3072) NOT NULL,
  estimated_tokens integer NOT NULL,
  heading_path jsonb NOT NULL DEFAULT '[]'::jsonb,
  metadata jsonb NOT NULL DEFAULT '{}'::jsonb,
  created_at timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (run_id, id),
  UNIQUE (run_id, document_id, chunk_index)
) PARTITION BY LIST (run_id);
--;;

CREATE VIEW alida_live_chunks_1536 AS
SELECT
  i.name AS index_name,
  c.run_id,
  c.document_id,
  c.source_id,
  c.content,
  c.embedding,
  c.metadata,
  c.heading_path,
  c.estimated_tokens
FROM alida_chunks_1536 c
JOIN alida_indexes i ON i.live_run_id = c.run_id;
--;;

CREATE VIEW alida_live_chunks_3072 AS
SELECT
  i.name AS index_name,
  c.run_id,
  c.document_id,
  c.source_id,
  c.content,
  c.embedding,
  c.metadata,
  c.heading_path,
  c.estimated_tokens
FROM alida_chunks_3072 c
JOIN alida_indexes i ON i.live_run_id = c.run_id;
