-- ─────────────────────────────────────────────────────────────────────────────
-- Post-deploy schema + data patches that the backend assumes exist but no
-- Liquibase changeset / @PostConstruct ever creates. Idempotent — safe to
-- re-run.
--
-- Loaded by deploy.sh during the master-bootstrap stage (after Hibernate
-- has created the base schema with ddl-auto: update).
-- ─────────────────────────────────────────────────────────────────────────────

-- ─── 1. hds.hds_chunk: tsv tsvector column + GIN index ──────────────────────
--
-- HybridSearchRepository.searchByKeyword() runs:
--   SELECT id FROM hds.hds_chunk
--   WHERE hds_version_id = ANY(?) AND tsv @@ plainto_tsquery('english', ?)
--   ORDER BY ts_rank(tsv, plainto_tsquery('english', ?)) DESC
--
-- The `tsv` column is referenced only in the integration test setup
-- (HybridSearchRepositoryIT.java) — there's no production migration. Every
-- AI search call fails with "column tsv does not exist" until this runs.

DO $$
BEGIN
  IF NOT EXISTS (
    SELECT 1 FROM information_schema.columns
    WHERE table_schema = 'hds' AND table_name = 'hds_chunk' AND column_name = 'tsv'
  ) THEN
    EXECUTE
      'ALTER TABLE hds.hds_chunk
         ADD COLUMN tsv tsvector
         GENERATED ALWAYS AS (to_tsvector(''english'', coalesce(content, ''''))) STORED';
    RAISE NOTICE 'Added hds.hds_chunk.tsv (generated tsvector)';
  ELSE
    RAISE NOTICE 'hds.hds_chunk.tsv already exists — skipping';
  END IF;
END$$;

-- GIN index for fast @@ matching. CREATE INDEX IF NOT EXISTS is its own
-- idempotency.
CREATE INDEX IF NOT EXISTS idx_hds_chunk_tsv ON hds.hds_chunk USING GIN (tsv);


-- ─── 2. public.global_settings: seed default UI theme rows ──────────────────
--
-- The frontend calls /v1/admin/settings/key/ui.active_theme and
-- /v1/admin/settings/key/ui.custom_themes on every page load. The backend
-- controller returns 404 (logged as "GlobalSetting not found") when the rows
-- are missing — noisy but not user-blocking. Seed harmless defaults.

INSERT INTO public.global_settings
  (id, created_at, updated_at, version, setting_key, setting_value, description, category)
VALUES
  (gen_random_uuid(), NOW(), NOW(), 0,
   'ui.active_theme',
   'default',
   'Active UI theme name (one of the keys in ui.custom_themes, or built-in)',
   'ui'),
  (gen_random_uuid(), NOW(), NOW(), 0,
   'ui.custom_themes',
   '[]',
   'JSON array of user-defined themes; empty by default',
   'ui')
ON CONFLICT (setting_key) DO NOTHING;
