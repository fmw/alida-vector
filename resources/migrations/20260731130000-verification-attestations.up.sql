ALTER TABLE alida_verifications
  ADD COLUMN verification_input_hash text,
  ADD COLUMN llm_result_source text,
  ADD COLUMN attestation_attestor text;
--;;

CREATE INDEX alida_verifications_input_hash_idx
  ON alida_verifications(verification_input_hash);
--;;

CREATE TABLE alida_verification_attestations (
  verification_input_hash text NOT NULL,
  attestor text NOT NULL,
  provider text NOT NULL,
  model text NOT NULL,
  prompt_policy_version text NOT NULL DEFAULT '',
  deterministic_gate_version text NOT NULL DEFAULT '',
  verification_input_version text NOT NULL,
  llm_verdict text NOT NULL CHECK (llm_verdict IN ('pass', 'caution', 'fail')),
  reasoning text,
  llm_findings jsonb NOT NULL DEFAULT '[]'::jsonb,
  llm_security_findings jsonb NOT NULL DEFAULT '[]'::jsonb,
  raw_response jsonb NOT NULL DEFAULT '{}'::jsonb,
  created_at timestamptz NOT NULL DEFAULT now(),
  last_used_at timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (verification_input_hash, attestor)
);
--;;

CREATE INDEX alida_verification_attestations_created_idx
  ON alida_verification_attestations(created_at DESC);
