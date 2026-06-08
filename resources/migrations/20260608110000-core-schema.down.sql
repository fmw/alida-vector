DROP VIEW IF EXISTS alida_live_chunks_3072;
--;;
DROP VIEW IF EXISTS alida_live_chunks_1536;
--;;
DROP TABLE IF EXISTS alida_chunks_3072;
--;;
DROP TABLE IF EXISTS alida_chunks_1536;
--;;
DROP TABLE IF EXISTS alida_events;
--;;
DROP TABLE IF EXISTS alida_reports;
--;;
DROP TABLE IF EXISTS alida_verification_cache;
--;;
DROP TABLE IF EXISTS alida_verifications;
--;;
DROP TABLE IF EXISTS alida_run_diffs;
--;;
DROP TABLE IF EXISTS alida_documents;
--;;
DROP TABLE IF EXISTS alida_sources;
--;;

ALTER TABLE IF EXISTS alida_indexes
  DROP CONSTRAINT IF EXISTS alida_indexes_live_run_fk;
--;;

ALTER TABLE IF EXISTS alida_indexes
  DROP CONSTRAINT IF EXISTS alida_indexes_previous_live_run_fk;
--;;

DROP TABLE IF EXISTS alida_runs;
--;;
DROP TABLE IF EXISTS alida_indexes;
