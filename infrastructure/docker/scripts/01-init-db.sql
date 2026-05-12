-- infrastructure/docker/scripts/01-init-db.sql
-- Runs as the postgres superuser during Docker image initialization.
-- Executed once when the postgres data directory is empty.
--
-- Responsibilities:
--   1. Create the application user (bipros)
--   2. Grant database ownership
--   3. Enable the PostGIS extension (required by bipros-gis WBS polygon columns)
--   4. Create all bounded-context schemas with correct ownership

-- ── 1. Application user ───────────────────────────────────────────────────
DO $$
BEGIN
  CREATE USER bipros WITH PASSWORD 'bipros_dev' CREATEDB;
  RAISE NOTICE 'Created user bipros';
EXCEPTION
  WHEN duplicate_object THEN
    RAISE NOTICE 'User bipros already exists — skipping creation';
END
$$;

-- ── 2. Database ownership and privileges ──────────────────────────────────
GRANT ALL PRIVILEGES ON DATABASE bipros TO bipros;
ALTER DATABASE bipros OWNER TO bipros;

-- ── 3. PostGIS extension ─────────────────────────────────────────────────
-- postgis/postgis:17-3.5 ships the extension files; we just need to enable it.
-- Must run as superuser (postgres), which is the current session user.
CREATE EXTENSION IF NOT EXISTS postgis;
CREATE EXTENSION IF NOT EXISTS postgis_topology;

-- ── 4. Schemas — one per bounded context ─────────────────────────────────
CREATE SCHEMA IF NOT EXISTS project     AUTHORIZATION bipros;
CREATE SCHEMA IF NOT EXISTS activity    AUTHORIZATION bipros;
CREATE SCHEMA IF NOT EXISTS scheduling  AUTHORIZATION bipros;
CREATE SCHEMA IF NOT EXISTS resource    AUTHORIZATION bipros;
CREATE SCHEMA IF NOT EXISTS cost        AUTHORIZATION bipros;
CREATE SCHEMA IF NOT EXISTS evm         AUTHORIZATION bipros;
CREATE SCHEMA IF NOT EXISTS baseline    AUTHORIZATION bipros;
CREATE SCHEMA IF NOT EXISTS udf         AUTHORIZATION bipros;
CREATE SCHEMA IF NOT EXISTS risk        AUTHORIZATION bipros;
CREATE SCHEMA IF NOT EXISTS portfolio   AUTHORIZATION bipros;
CREATE SCHEMA IF NOT EXISTS contract    AUTHORIZATION bipros;
CREATE SCHEMA IF NOT EXISTS document    AUTHORIZATION bipros;
CREATE SCHEMA IF NOT EXISTS gis         AUTHORIZATION bipros;

-- ── 5. Public schema grants ───────────────────────────────────────────────
GRANT ALL ON SCHEMA public TO bipros;
GRANT ALL PRIVILEGES ON ALL TABLES    IN SCHEMA public TO bipros;
GRANT ALL PRIVILEGES ON ALL SEQUENCES IN SCHEMA public TO bipros;
ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT ALL ON TABLES    TO bipros;
ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT ALL ON SEQUENCES TO bipros;

-- ── 6. Grant usage on all schemas to the application user ────────────────
DO $$
DECLARE
  sch TEXT;
BEGIN
  FOR sch IN SELECT schema_name FROM information_schema.schemata
             WHERE schema_name NOT IN ('pg_catalog','information_schema','pg_toast')
  LOOP
    EXECUTE format('GRANT USAGE ON SCHEMA %I TO bipros', sch);
    EXECUTE format('GRANT ALL PRIVILEGES ON ALL TABLES    IN SCHEMA %I TO bipros', sch);
    EXECUTE format('GRANT ALL PRIVILEGES ON ALL SEQUENCES IN SCHEMA %I TO bipros', sch);
    EXECUTE format(
      'ALTER DEFAULT PRIVILEGES IN SCHEMA %I GRANT ALL ON TABLES    TO bipros', sch);
    EXECUTE format(
      'ALTER DEFAULT PRIVILEGES IN SCHEMA %I GRANT ALL ON SEQUENCES TO bipros', sch);
  END LOOP;
END
$$;
