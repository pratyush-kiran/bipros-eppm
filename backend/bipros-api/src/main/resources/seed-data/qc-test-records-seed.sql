-- ============================================================
-- QC Test Records Seeder for Project Activities
-- ============================================================
-- Prerequisites:
--   1. Run this AFTER the app has booted (tables exist).
--   2. Set :project_id to your project UUID.
--   3. Set :activity_map as a CTE or substitute real activity UUIDs.
--
-- This script:
--   A. Inserts 14 missing test types needed for this project
--   B. Provides templated INSERTs for qc_test_records per activity
-- ============================================================

\set project_id 'YOUR-PROJECT-UUID-HERE'

-- ============================================================
-- A. INSERT MISSING TEST TYPES
-- ============================================================

INSERT INTO activity.qc_test_types
    (id, created_at, updated_at, created_by, updated_by, version, project_id, name, unit, irc_threshold, active)
VALUES
    (gen_random_uuid(), NOW(), NOW(), 'seeder', 'seeder', 0, :project_id, 'Pile Load Test',                                                     'kN',        1500.0000, true),
    (gen_random_uuid(), NOW(), NOW(), 'seeder', 'seeder', 0, :project_id, 'Non-destructive Integrity Test (NDT)',                               NULL,        NULL,      true),
    (gen_random_uuid(), NOW(), NOW(), 'seeder', 'seeder', 0, :project_id, 'Core Recovery / RQD',                                                '%',         50.0000,   true),
    (gen_random_uuid(), NOW(), NOW(), 'seeder', 'seeder', 0, :project_id, 'Vertical Load Capacity (Bearings)',                                  'kN',        1400.0000, true),
    (gen_random_uuid(), NOW(), NOW(), 'seeder', 'seeder', 0, :project_id, 'Joint Movement Test',                                                'mm',        NULL,      true),
    (gen_random_uuid(), NOW(), NOW(), 'seeder', 'seeder', 0, :project_id, 'Compressive Strength (Interlocking Blocks)',                         'MPa',       35.0000,   true),
    (gen_random_uuid(), NOW(), NOW(), 'seeder', 'seeder', 0, :project_id, 'Water Absorption (Blocks)',                                          '%',         6.0000,    true),
    (gen_random_uuid(), NOW(), NOW(), 'seeder', 'seeder', 0, :project_id, 'Water Absorption (Stone)',                                           '%',         0.5000,    true),
    (gen_random_uuid(), NOW(), NOW(), 'seeder', 'seeder', 0, :project_id, 'Flexural Strength (Stone)',                                          'MPa',       10.0000,   true),
    (gen_random_uuid(), NOW(), NOW(), 'seeder', 'seeder', 0, :project_id, 'Retroreflectivity',                                                  'cd/lx/m²',  300.0000,  true),
    (gen_random_uuid(), NOW(), NOW(), 'seeder', 'seeder', 0, :project_id, 'Galvanizing Thickness',                                              'µm',        85.0000,   true),
    (gen_random_uuid(), NOW(), NOW(), 'seeder', 'seeder', 0, :project_id, 'Coating Thickness',                                                  'µm',        NULL,      true),
    (gen_random_uuid(), NOW(), NOW(), 'seeder', 'seeder', 0, :project_id, 'Peel Strength (Composite)',                                          'N/25mm',    NULL,      true),
    (gen_random_uuid(), NOW(), NOW(), 'seeder', 'seeder', 0, :project_id, 'Insulation Resistance',                                              'MΩ',        1.0000,    true),
    (gen_random_uuid(), NOW(), NOW(), 'seeder', 'seeder', 0, :project_id, 'Earth Resistance',                                                   'Ω',         1.0000,    true)
ON CONFLICT DO NOTHING;

-- ============================================================
-- B. TEMPLATED QC TEST RECORD INSERTS
-- ============================================================
-- Replace :activity_id and :activity_name with real values.
-- Duplicate blocks as needed for each activity instance.

-- ----------------------------------------------------------
-- SOIL INVESTIGATION
-- ----------------------------------------------------------
INSERT INTO activity.qc_test_records
    (id, created_at, updated_at, project_id, activity_id, activity_name, test_type_id, test_type_name, test_date, chainage, sample_ref_no, test_result, required_irc, outcome, lab_inspector)
VALUES
    (gen_random_uuid(), NOW(), NOW(), :project_id, :activity_id, :activity_name, 'af2ca15a-0789-4aeb-a437-005c120ec137', 'CBR (Soil)',                '2025-01-10', NULL, 'SI-CBR-001',    9.50,  8.0,  'PASS', 'Soil Lab A'),
    (gen_random_uuid(), NOW(), NOW(), :project_id, :activity_id, :activity_name, 'e5fd08fc-e865-4ddb-a054-7729981c16ff', 'Liquid Limit',              '2025-01-10', NULL, 'SI-LL-001',    45.00, 50.0,  'PASS', 'Soil Lab A'),
    (gen_random_uuid(), NOW(), NOW(), :project_id, :activity_id, :activity_name, '6204513f-431a-4480-8df1-f2c6f7dba893', 'Plasticity Index',          '2025-01-10', NULL, 'SI-PI-001',    15.00, 20.0,  'PASS', 'Soil Lab A');

-- ----------------------------------------------------------
-- PILING (1000mm diameter for Pier Foundation)
-- ----------------------------------------------------------
-- Note: You need to query for the newly-inserted NDT test type UUID
-- or use a CTE. Below shows the pattern using your known Concrete test.

INSERT INTO activity.qc_test_records
    (id, created_at, updated_at, project_id, activity_id, activity_name, test_type_id, test_type_name, test_date, chainage, sample_ref_no, test_result, required_irc, outcome, lab_inspector)
VALUES
    (gen_random_uuid(), NOW(), NOW(), :project_id, :activity_id, :activity_name, 'b70c36bb-16d0-46c7-b262-9649f8c8b80d', 'Compressive Strength (Concrete)', '2025-01-15', 'Pier-1', 'PL-C30-001',   32.50, 30.0, 'PASS', 'Concrete Lab B'),
    (gen_random_uuid(), NOW(), NOW(), :project_id, :activity_id, :activity_name, '98b8e52b-b96b-45b6-8463-fd82041467ba', 'Rebound Hammer (Concrete)',       '2025-01-15', 'Pier-1', 'PL-RH-001',    28.00, 25.0, 'PASS', 'Site Engineer'),
    (gen_random_uuid(), NOW(), NOW(), :project_id, :activity_id, :activity_name, '1847c638-626a-4939-87eb-996e17e18ffb', 'Cover Meter (Rebar)',             '2025-01-15', 'Pier-1', 'PL-CM-001',    45.00, 40.0, 'PASS', 'Site Engineer');

-- ----------------------------------------------------------
-- STATIC LOAD TESTING (1000mm working piles)
-- ----------------------------------------------------------
-- Uses the NEWLY INSERTED 'Pile Load Test' type.
-- Query its UUID first:
-- SELECT id FROM activity.qc_test_types WHERE project_id = :project_id AND name = 'Pile Load Test';

-- INSERT INTO activity.qc_test_records (...)
-- VALUES (..., :project_id, :activity_id, :activity_name,
--         (SELECT id FROM activity.qc_test_types WHERE project_id = :project_id AND name = 'Pile Load Test'),
--         'Pile Load Test', '2025-01-20', 'Pier-1', 'SLT-001', 2250.00, 1500.0, 'PASS', 'Pile Test Lab');

-- ----------------------------------------------------------
-- CONCRETE CLASS 30 (retaining walls, bridge foundations, abutments, pier heads)
-- ----------------------------------------------------------
INSERT INTO activity.qc_test_records
    (id, created_at, updated_at, project_id, activity_id, activity_name, test_type_id, test_type_name, test_date, chainage, sample_ref_no, test_result, required_irc, outcome, lab_inspector)
VALUES
    (gen_random_uuid(), NOW(), NOW(), :project_id, :activity_id, :activity_name, 'b70c36bb-16d0-46c7-b262-9649f8c8b80d', 'Compressive Strength (Concrete)', '2025-02-01', NULL, 'C30-001', 34.00, 30.0, 'PASS', 'Concrete Lab B'),
    (gen_random_uuid(), NOW(), NOW(), :project_id, :activity_id, :activity_name, '98b8e52b-b96b-45b6-8463-fd82041467ba', 'Rebound Hammer (Concrete)',       '2025-02-01', NULL, 'C30-RH-001', 27.50, 25.0, 'PASS', 'Site Engineer'),
    (gen_random_uuid(), NOW(), NOW(), :project_id, :activity_id, :activity_name, '1847c638-626a-4939-87eb-996e17e18ffb', 'Cover Meter (Rebar)',             '2025-02-01', NULL, 'C30-CM-001', 42.00, 40.0, 'PASS', 'Site Engineer');

-- ----------------------------------------------------------
-- GSB (Class B) — EXTRACTION & SCREENING
-- ----------------------------------------------------------
INSERT INTO activity.qc_test_records
    (id, created_at, updated_at, project_id, activity_id, activity_name, test_type_id, test_type_name, test_date, chainage, sample_ref_no, test_result, required_irc, outcome, lab_inspector)
VALUES
    (gen_random_uuid(), NOW(), NOW(), :project_id, :activity_id, :activity_name, '62cd21dc-5f92-4a96-a385-ca2865e67d52', 'Aggregate Crushing Value', '2025-02-05', NULL, 'GSB-ACV-001',  26.00, 30.0, 'PASS', 'Aggregate Lab'),
    (gen_random_uuid(), NOW(), NOW(), :project_id, :activity_id, :activity_name, '5ffeb993-90cf-4333-a2a4-16443b0d932e', 'Aggregate Impact Value',   '2025-02-05', NULL, 'GSB-AIV-001',  24.00, 30.0, 'PASS', 'Aggregate Lab'),
    (gen_random_uuid(), NOW(), NOW(), :project_id, :activity_id, :activity_name, '58e02001-e11d-4607-bbc8-e4d4ffcc3f26', 'Water Absorption',         '2025-02-05', NULL, 'GSB-WA-001',    1.50,  2.0, 'PASS', 'Aggregate Lab'),
    (gen_random_uuid(), NOW(), NOW(), :project_id, :activity_id, :activity_name, 'b01b609e-f8e9-4fa5-ba5c-5fb66d0d1546', 'Flakiness Index',          '2025-02-05', NULL, 'GSB-FI-001',    22.00, 25.0, 'PASS', 'Aggregate Lab'),
    (gen_random_uuid(), NOW(), NOW(), :project_id, :activity_id, :activity_name, '9a2d9936-410c-487a-96b6-d3e905703092', 'Elongation Index',         '2025-02-05', NULL, 'GSB-EI-001',    20.00, 25.0, 'PASS', 'Aggregate Lab');

-- ----------------------------------------------------------
-- GSB (Class B) / ABC (Class A) — COMPACTION
-- ----------------------------------------------------------
INSERT INTO activity.qc_test_records
    (id, created_at, updated_at, project_id, activity_id, activity_name, test_type_id, test_type_name, test_date, chainage, sample_ref_no, test_result, required_irc, outcome, lab_inspector)
VALUES
    (gen_random_uuid(), NOW(), NOW(), :project_id, :activity_id, :activity_name, '1b4ae985-6dce-451b-ab05-fdd6010c2f53', 'Compaction (Core Cutter)',       '2025-02-10', '10+000', 'GSB-CC-001',  98.50, 98.0, 'PASS', 'Field Lab'),
    (gen_random_uuid(), NOW(), NOW(), :project_id, :activity_id, :activity_name, 'fc8eb8eb-924d-4f37-bf4c-2ec0fcf46729', 'Compaction (Sand Replacement)',  '2025-02-10', '10+050', 'GSB-SR-001',  97.80, 98.0, 'FAIL', 'Field Lab');

-- ----------------------------------------------------------
-- BITUMINOUS TACK COAT / PRIME COAT
-- ----------------------------------------------------------
INSERT INTO activity.qc_test_records
    (id, created_at, updated_at, project_id, activity_id, activity_name, test_type_id, test_type_name, test_date, chainage, sample_ref_no, test_result, required_irc, outcome, lab_inspector)
VALUES
    (gen_random_uuid(), NOW(), NOW(), :project_id, :activity_id, :activity_name, '73857d87-1127-462b-a8d9-9bdb2fb0072f', 'Bitumen Content (Extraction)', '2025-02-15', '12+200', 'TC-BC-001', 4.80, 5.0, 'PASS', 'Bitumen Lab'),
    (gen_random_uuid(), NOW(), NOW(), :project_id, :activity_id, :activity_name, '73857d87-1127-462b-a8d9-9bdb2fb0072f', 'Bitumen Content (Extraction)', '2025-02-15', '12+400', 'PC-BC-001', 5.20, 5.0, 'PASS', 'Bitumen Lab');

-- ----------------------------------------------------------
-- PRECAST CONCRETE ITEMS (curbs, parapets, crash barriers)
-- ----------------------------------------------------------
INSERT INTO activity.qc_test_records
    (id, created_at, updated_at, project_id, activity_id, activity_name, test_type_id, test_type_name, test_date, chainage, sample_ref_no, test_result, required_irc, outcome, lab_inspector)
VALUES
    (gen_random_uuid(), NOW(), NOW(), :project_id, :activity_id, :activity_name, 'b70c36bb-16d0-46c7-b262-9649f8c8b80d', 'Compressive Strength (Concrete)', '2025-02-20', NULL, 'PC-C30-001', 35.00, 30.0, 'PASS', 'Precast Lab');

-- ----------------------------------------------------------
-- HAND HOLE / DRAW PIT
-- ----------------------------------------------------------
INSERT INTO activity.qc_test_records
    (id, created_at, updated_at, project_id, activity_id, activity_name, test_type_id, test_type_name, test_date, chainage, sample_ref_no, test_result, required_irc, outcome, lab_inspector)
VALUES
    (gen_random_uuid(), NOW(), NOW(), :project_id, :activity_id, :activity_name, 'b70c36bb-16d0-46c7-b262-9649f8c8b80d', 'Compressive Strength (Concrete)', '2025-02-25', NULL, 'HH-C30-001', 31.50, 30.0, 'PASS', 'Site Engineer');

-- ----------------------------------------------------------
-- EXCAVATION / BACKFILL / TEST PIT
-- ----------------------------------------------------------
INSERT INTO activity.qc_test_records
    (id, created_at, updated_at, project_id, activity_id, activity_name, test_type_id, test_type_name, test_date, chainage, sample_ref_no, test_result, required_irc, outcome, lab_inspector)
VALUES
    (gen_random_uuid(), NOW(), NOW(), :project_id, :activity_id, :activity_name, '1b4ae985-6dce-451b-ab05-fdd6010c2f53', 'Compaction (Core Cutter)',       '2025-03-01', '5+000', 'EX-CC-001',  96.50, 95.0, 'PASS', 'Field Lab'),
    (gen_random_uuid(), NOW(), NOW(), :project_id, :activity_id, :activity_name, 'fc8eb8eb-924d-4f37-bf4c-2ec0fcf46729', 'Compaction (Sand Replacement)',  '2025-03-01', '5+100', 'EX-SR-001',  99.20, 98.0, 'PASS', 'Field Lab');

-- ============================================================
-- C. ACTIVITIES WITH NO QC TEST (for reference — do NOT insert)
-- ============================================================
--
-- UPVC split pipe 150mm dia for Electrical LT cable
-- Setting up at each pile 900mm & 1000mm diameter -pile location
-- Provision of 5 sets of photographs (294X210mm)
-- Provision of two sets of Video recordings as specified
-- GSB (Class B)-Transportation
-- GSB (Class B)-Hauling and compaction  [NOTE: if this is COMPACTION work, use Compaction tests above]
-- Opening ceremony including all facilities
-- Fabricate and fix ... [visual / dimensional only — no material test]
-- Paving, concrete interlocking blocks [needs NEW test types]
-- 20mm Natural Omani non polished Lime stone [needs NEW test types]
-- Removal of ... [demolition — no QC]
-- Clear site of all trees
-- Relocate existing ... [utility diversion — no QC]
-- Service ducts ... [supply only — no QC]
-- Cable marker
-- Lighting column ... [concrete test already covered]
-- 11kv pole mounted transformer ... [needs NEW electrical tests]
-- Feeder pillar ... [needs NEW electrical tests]
-- Supply & Installation of cables ... [needs NEW electrical tests]
-- Single core standard insulated cable ... [needs NEW electrical tests]
-- Prime coat [visual only]
-- Relocate existing water line ... [utility diversion — no QC]

-- ============================================================
-- D. VERIFICATION QUERIES
-- ============================================================

-- View all test types for this project
-- SELECT id, name, unit, irc_threshold FROM activity.qc_test_types WHERE project_id = :project_id AND active = true ORDER BY name;

-- View QC record counts by outcome
-- SELECT outcome, COUNT(*) FROM activity.qc_test_records WHERE project_id = :project_id GROUP BY outcome;

-- View recent FAILs
-- SELECT * FROM activity.qc_test_records WHERE project_id = :project_id AND outcome = 'FAIL' ORDER BY test_date DESC LIMIT 10;

-- View dashboard summary
-- SELECT
--     COUNT(*) FILTER (WHERE outcome = 'PASS')   AS pass_count,
--     COUNT(*) FILTER (WHERE outcome = 'FAIL')   AS fail_count,
--     COUNT(*) FILTER (WHERE outcome = 'REPEAT') AS repeat_count,
--     COUNT(*)                                   AS total_tests
-- FROM activity.qc_test_records WHERE project_id = :project_id;
