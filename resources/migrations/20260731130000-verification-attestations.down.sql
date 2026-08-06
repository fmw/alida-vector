DROP TABLE IF EXISTS alida_verification_attestations;
--;;

DROP INDEX IF EXISTS alida_verifications_attestation_idx;
--;;

ALTER TABLE alida_verifications
  DROP COLUMN IF EXISTS attestation_attestor,
  DROP COLUMN IF EXISTS llm_result_source,
  DROP COLUMN IF EXISTS verification_input_hash;
