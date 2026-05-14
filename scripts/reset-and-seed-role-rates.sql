-- =============================================================================
-- Reset + Seed Role Rates
-- =============================================================================
-- Clears all activity-level resource assignments, all DPRs (and child rows),
-- then seeds default rate variants for every existing ResourceRole across the
-- three types (Manpower / Equipment / Material) so the role-based activity
-- demand UI can be tested end-to-end.
--
-- Safe to re-run: every INSERT uses ON CONFLICT DO NOTHING.
-- Run with: psql -h localhost -U postgres -d bipros -f scripts/reset-and-seed-role-rates.sql
-- =============================================================================

BEGIN;

-- -----------------------------------------------------------------------------
-- 0. Schema fix-up — drop the legacy `skill_level_id` column from
--    manpower_role_rates if it lingers (the rename happened post-080 apply).
-- -----------------------------------------------------------------------------
ALTER TABLE resource.manpower_role_rates
    DROP CONSTRAINT IF EXISTS uk_manpower_role_rate;

ALTER TABLE resource.manpower_role_rates
    DROP COLUMN IF EXISTS skill_level_id;

-- Re-add the constraint with the new column tuple if missing.
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'uk_manpower_role_rate'
    ) THEN
        ALTER TABLE resource.manpower_role_rates
            ADD CONSTRAINT uk_manpower_role_rate UNIQUE (role_id, category_id, grade_id);
    END IF;
END $$;

-- Same for productivity_norms — drop legacy skill_level_id column if present.
ALTER TABLE resource.productivity_norms
    DROP COLUMN IF EXISTS skill_level_id;

-- -----------------------------------------------------------------------------
-- 1. WIPE existing assignments + DPRs
-- -----------------------------------------------------------------------------
DELETE FROM project.dpr_issues;
DELETE FROM project.dpr_attachments;
DELETE FROM project.dpr_manpower;
DELETE FROM project.dpr_equipment;
DELETE FROM project.dpr_material;
DELETE FROM project.daily_progress_reports;

-- daily_activity_resource_outputs is the rollup ledger from DPRs
DELETE FROM project.daily_activity_resource_outputs;

-- Now wipe the assignments themselves
DELETE FROM resource.resource_assignments;

-- Also wipe any pre-existing role-rate variants so the seed below is deterministic
DELETE FROM resource.project_manpower_role_rate_override;
DELETE FROM resource.project_equipment_role_variant_override;
DELETE FROM resource.project_material_role_variant_override;
DELETE FROM resource.manpower_role_rates;
DELETE FROM resource.equipment_role_variants;
DELETE FROM resource.material_role_variants;

-- -----------------------------------------------------------------------------
-- 2. Ensure baseline master data (categories + grades) exists
-- -----------------------------------------------------------------------------

-- Categories (Skilled / Semi-Skilled / Unskilled / Staff)
INSERT INTO resource.manpower_category_master (id, code, name, sort_order, active, created_at, updated_at, version)
SELECT gen_random_uuid(), 'SKILLED', 'Skilled', 10, true, now(), now(), 0
WHERE NOT EXISTS (SELECT 1 FROM resource.manpower_category_master WHERE LOWER(name) = 'skilled');

INSERT INTO resource.manpower_category_master (id, code, name, sort_order, active, created_at, updated_at, version)
SELECT gen_random_uuid(), 'SEMI_SKILLED', 'Semi-Skilled', 20, true, now(), now(), 0
WHERE NOT EXISTS (SELECT 1 FROM resource.manpower_category_master WHERE LOWER(name) = 'semi-skilled');

INSERT INTO resource.manpower_category_master (id, code, name, sort_order, active, created_at, updated_at, version)
SELECT gen_random_uuid(), 'UNSKILLED', 'Unskilled', 30, true, now(), now(), 0
WHERE NOT EXISTS (SELECT 1 FROM resource.manpower_category_master WHERE LOWER(name) = 'unskilled');

INSERT INTO resource.manpower_category_master (id, code, name, sort_order, active, created_at, updated_at, version)
SELECT gen_random_uuid(), 'STAFF', 'Staff', 40, true, now(), now(), 0
WHERE NOT EXISTS (SELECT 1 FROM resource.manpower_category_master WHERE LOWER(name) = 'staff');

-- Grades A / B / C
INSERT INTO resource.grade_master (id, code, name, sort_order, active, created_at, updated_at, version)
SELECT gen_random_uuid(), 'A', 'A', 10, true, now(), now(), 0
WHERE NOT EXISTS (SELECT 1 FROM resource.grade_master WHERE code = 'A');

INSERT INTO resource.grade_master (id, code, name, sort_order, active, created_at, updated_at, version)
SELECT gen_random_uuid(), 'B', 'B', 20, true, now(), now(), 0
WHERE NOT EXISTS (SELECT 1 FROM resource.grade_master WHERE code = 'B');

INSERT INTO resource.grade_master (id, code, name, sort_order, active, created_at, updated_at, version)
SELECT gen_random_uuid(), 'C', 'C', 30, true, now(), now(), 0
WHERE NOT EXISTS (SELECT 1 FROM resource.grade_master WHERE code = 'C');

-- -----------------------------------------------------------------------------
-- 3. Seed Manpower Rates — for every LABOR role × every (category, grade)
-- -----------------------------------------------------------------------------
-- Rate matrix (₹/Day):
--                A      B      C
--   Skilled    1200   1000    850
--   Semi-Sk.    900    750    600
--   Unskilled   600    500    450
--   Staff      1500   1300   1100
-- -----------------------------------------------------------------------------

INSERT INTO resource.manpower_role_rates
    (id, role_id, category_id, grade_id, unit, rate, active,
     created_at, updated_at, created_by, updated_by, version)
SELECT
    gen_random_uuid(),
    r.id,
    cat.id,
    g.id,
    'Day',
    CASE
      WHEN LOWER(cat.name) = 'skilled' AND g.code = 'A' THEN 1200
      WHEN LOWER(cat.name) = 'skilled' AND g.code = 'B' THEN 1000
      WHEN LOWER(cat.name) = 'skilled' AND g.code = 'C' THEN 850
      WHEN LOWER(cat.name) = 'semi-skilled' AND g.code = 'A' THEN 900
      WHEN LOWER(cat.name) = 'semi-skilled' AND g.code = 'B' THEN 750
      WHEN LOWER(cat.name) = 'semi-skilled' AND g.code = 'C' THEN 600
      WHEN LOWER(cat.name) = 'unskilled' AND g.code = 'A' THEN 600
      WHEN LOWER(cat.name) = 'unskilled' AND g.code = 'B' THEN 500
      WHEN LOWER(cat.name) = 'unskilled' AND g.code = 'C' THEN 450
      WHEN LOWER(cat.name) = 'staff' AND g.code = 'A' THEN 1500
      WHEN LOWER(cat.name) = 'staff' AND g.code = 'B' THEN 1300
      WHEN LOWER(cat.name) = 'staff' AND g.code = 'C' THEN 1100
      ELSE 800
    END,
    true,
    now(), now(), 'role-rate-seed', 'role-rate-seed', 0
FROM resource.resource_roles r
JOIN resource.resource_types rt
    ON rt.id = r.resource_type_id AND rt.code IN ('LABOR', 'MANPOWER')
CROSS JOIN resource.manpower_category_master cat
CROSS JOIN resource.grade_master g
WHERE r.active = true AND cat.active = true AND g.active = true
ON CONFLICT (role_id, category_id, grade_id) DO NOTHING;

-- -----------------------------------------------------------------------------
-- 4. Seed Equipment Variants — for every EQUIPMENT role
-- -----------------------------------------------------------------------------
-- Each equipment role gets 3 variants. Rate is derived from a per-role seed
-- so different roles get different price points.
-- -----------------------------------------------------------------------------

-- Variant 1: "Unknown / Default" — base rate
INSERT INTO resource.equipment_role_variants
    (id, role_id, make, model, unit, rate, active,
     created_at, updated_at, created_by, updated_by, version)
SELECT
    gen_random_uuid(),
    r.id,
    'Unknown',
    'Default',
    'Day',
    1500 + (ABS(hashtext(r.code)) % 35) * 100,  -- 1500..4900
    true,
    now(), now(), 'role-rate-seed', 'role-rate-seed', 0
FROM resource.resource_roles r
JOIN resource.resource_types rt
    ON rt.id = r.resource_type_id AND rt.code = 'EQUIPMENT'
WHERE r.active = true
ON CONFLICT (role_id, make, model) DO NOTHING;

-- Variant 2: "Caterpillar / Standard" — premium rate
INSERT INTO resource.equipment_role_variants
    (id, role_id, make, model, unit, rate, active,
     created_at, updated_at, created_by, updated_by, version)
SELECT
    gen_random_uuid(),
    r.id,
    'Caterpillar',
    'Standard',
    'Day',
    2500 + (ABS(hashtext(r.code)) % 35) * 100,
    true,
    now(), now(), 'role-rate-seed', 'role-rate-seed', 0
FROM resource.resource_roles r
JOIN resource.resource_types rt
    ON rt.id = r.resource_type_id AND rt.code = 'EQUIPMENT'
WHERE r.active = true
ON CONFLICT (role_id, make, model) DO NOTHING;

-- Variant 3: "JCB / 3CX" — mid-tier rate, billed hourly
INSERT INTO resource.equipment_role_variants
    (id, role_id, make, model, unit, rate, active,
     created_at, updated_at, created_by, updated_by, version)
SELECT
    gen_random_uuid(),
    r.id,
    'JCB',
    '3CX',
    'Hour',
    250 + (ABS(hashtext(r.code)) % 20) * 25,  -- 250..725
    true,
    now(), now(), 'role-rate-seed', 'role-rate-seed', 0
FROM resource.resource_roles r
JOIN resource.resource_types rt
    ON rt.id = r.resource_type_id AND rt.code = 'EQUIPMENT'
WHERE r.active = true
ON CONFLICT (role_id, make, model) DO NOTHING;

-- -----------------------------------------------------------------------------
-- 5. Seed Material Variants — for every MATERIAL role
-- -----------------------------------------------------------------------------
-- Each material role gets 2 variants: Standard + Premium.
-- -----------------------------------------------------------------------------

INSERT INTO resource.material_role_variants
    (id, role_id, spec_grade, unit, rate, active,
     created_at, updated_at, created_by, updated_by, version)
SELECT
    gen_random_uuid(),
    r.id,
    'Standard',
    'MT',
    50 + (ABS(hashtext(r.code)) % 100) * 2,  -- 50..248
    true,
    now(), now(), 'role-rate-seed', 'role-rate-seed', 0
FROM resource.resource_roles r
JOIN resource.resource_types rt
    ON rt.id = r.resource_type_id AND rt.code = 'MATERIAL'
WHERE r.active = true
ON CONFLICT (role_id, spec_grade) DO NOTHING;

INSERT INTO resource.material_role_variants
    (id, role_id, spec_grade, unit, rate, active,
     created_at, updated_at, created_by, updated_by, version)
SELECT
    gen_random_uuid(),
    r.id,
    'Premium',
    'MT',
    80 + (ABS(hashtext(r.code)) % 100) * 3,  -- 80..377
    true,
    now(), now(), 'role-rate-seed', 'role-rate-seed', 0
FROM resource.resource_roles r
JOIN resource.resource_types rt
    ON rt.id = r.resource_type_id AND rt.code = 'MATERIAL'
WHERE r.active = true
ON CONFLICT (role_id, spec_grade) DO NOTHING;

-- -----------------------------------------------------------------------------
-- 6. Summary report
-- -----------------------------------------------------------------------------
SELECT
    (SELECT COUNT(*) FROM resource.manpower_role_rates) AS manpower_rates,
    (SELECT COUNT(*) FROM resource.equipment_role_variants) AS equipment_variants,
    (SELECT COUNT(*) FROM resource.material_role_variants) AS material_variants,
    (SELECT COUNT(*) FROM resource.resource_assignments) AS assignments_remaining,
    (SELECT COUNT(*) FROM project.daily_progress_reports) AS dprs_remaining;

COMMIT;
