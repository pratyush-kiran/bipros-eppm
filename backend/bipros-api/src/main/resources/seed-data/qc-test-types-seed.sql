-- QC Test Types Master Data Seeder
-- ============================================================
-- Run this against the `bipros` database after the application
-- has booted once (so the `activity.qc_test_types` table exists).
--
-- Replace :project_id with your actual project UUID, e.g.:
--   'a1b2c3d4-e5f6-7890-abcd-ef1234567890'
--
-- These thresholds are illustrative IRC / MORTH spec values for
-- typical road construction tests. Adjust per project / clause.

INSERT INTO activity.qc_test_types
    (id, created_at, updated_at, created_by, updated_by, version, project_id, name, unit, irc_threshold, active)
VALUES
    (gen_random_uuid(), NOW(), NOW(), 'seeder', 'seeder', 0, :project_id, 'Compaction (Core Cutter)',       '%',      98.0000, true),
    (gen_random_uuid(), NOW(), NOW(), 'seeder', 'seeder', 0, :project_id, 'Compaction (Sand Replacement)',  '%',      98.0000, true),
    (gen_random_uuid(), NOW(), NOW(), 'seeder', 'seeder', 0, :project_id, 'Aggregate Impact Value',         '%',       30.0000, true),
    (gen_random_uuid(), NOW(), NOW(), 'seeder', 'seeder', 0, :project_id, 'Aggregate Crushing Value',       '%',       30.0000, true),
    (gen_random_uuid(), NOW(), NOW(), 'seeder', 'seeder', 0, :project_id, 'Flakiness Index',                '%',       25.0000, true),
    (gen_random_uuid(), NOW(), NOW(), 'seeder', 'seeder', 0, :project_id, 'Elongation Index',               '%',       25.0000, true),
    (gen_random_uuid(), NOW(), NOW(), 'seeder', 'seeder', 0, :project_id, 'Bitumen Content (Extraction)',   '%',        5.0000, true),
    (gen_random_uuid(), NOW(), NOW(), 'seeder', 'seeder', 0, :project_id, 'Marshall Stability',             'kN',       8.0000, true),
    (gen_random_uuid(), NOW(), NOW(), 'seeder', 'seeder', 0, :project_id, 'Marshall Flow',                  'mm',       2.0000, true),
    (gen_random_uuid(), NOW(), NOW(), 'seeder', 'seeder', 0, :project_id, 'CBR (Soil)',                     '%',        8.0000, true),
    (gen_random_uuid(), NOW(), NOW(), 'seeder', 'seeder', 0, :project_id, 'CBR (Sub-base)',                 '%',       30.0000, true),
    (gen_random_uuid(), NOW(), NOW(), 'seeder', 'seeder', 0, :project_id, 'Liquid Limit',                   '%',       50.0000, true),
    (gen_random_uuid(), NOW(), NOW(), 'seeder', 'seeder', 0, :project_id, 'Plastic Limit',                  '%',       25.0000, true),
    (gen_random_uuid(), NOW(), NOW(), 'seeder', 'seeder', 0, :project_id, 'Plasticity Index',               '%',       20.0000, true),
    (gen_random_uuid(), NOW(), NOW(), 'seeder', 'seeder', 0, :project_id, 'Sieve Analysis (Gradation)',     '%',      100.0000, true),
    (gen_random_uuid(), NOW(), NOW(), 'seeder', 'seeder', 0, :project_id, 'Water Absorption',               '%',        2.0000, true),
    (gen_random_uuid(), NOW(), NOW(), 'seeder', 'seeder', 0, :project_id, 'Specific Gravity (Coarse)',      'g/cc',     2.6000, true),
    (gen_random_uuid(), NOW(), NOW(), 'seeder', 'seeder', 0, :project_id, 'Specific Gravity (Fine)',        'g/cc',     2.7000, true),
    (gen_random_uuid(), NOW(), NOW(), 'seeder', 'seeder', 0, :project_id, 'Softening Point',                '°C',      45.0000, true),
    (gen_random_uuid(), NOW(), NOW(), 'seeder', 'seeder', 0, :project_id, 'Penetration',                    '0.1mm',   60.0000, true),
    (gen_random_uuid(), NOW(), NOW(), 'seeder', 'seeder', 0, :project_id, 'Ductility',                      'cm',      75.0000, true),
    (gen_random_uuid(), NOW(), NOW(), 'seeder', 'seeder', 0, :project_id, 'Layer Thickness (DBM)',          'mm',      50.0000, true),
    (gen_random_uuid(), NOW(), NOW(), 'seeder', 'seeder', 0, :project_id, 'Layer Thickness (BC)',           'mm',      40.0000, true),
    (gen_random_uuid(), NOW(), NOW(), 'seeder', 'seeder', 0, :project_id, 'Layer Thickness (WMM)',          'mm',     225.0000, true),
    (gen_random_uuid(), NOW(), NOW(), 'seeder', 'seeder', 0, :project_id, 'Layer Thickness (GSB)',          'mm',     200.0000, true),
    (gen_random_uuid(), NOW(), NOW(), 'seeder', 'seeder', 0, :project_id, 'Roughness (IRI)',                'm/km',     2.0000, true),
    (gen_random_uuid(), NOW(), NOW(), 'seeder', 'seeder', 0, :project_id, 'Skid Resistance',                'BPN',     55.0000, true),
    (gen_random_uuid(), NOW(), NOW(), 'seeder', 'seeder', 0, :project_id, 'Benkelman Beam Deflection',      'mm',       1.5000, true),
    (gen_random_uuid(), NOW(), NOW(), 'seeder', 'seeder', 0, :project_id, 'Permeability (Drainage)',        'sec',      300.0000, true),
    (gen_random_uuid(), NOW(), NOW(), 'seeder', 'seeder', 0, :project_id, 'Compressive Strength (Concrete)','MPa',     30.0000, true),
    (gen_random_uuid(), NOW(), NOW(), 'seeder', 'seeder', 0, :project_id, 'Split Tensile Strength',         'MPa',      2.5000, true),
    (gen_random_uuid(), NOW(), NOW(), 'seeder', 'seeder', 0, :project_id, 'Flexural Strength',              'MPa',      4.5000, true),
    (gen_random_uuid(), NOW(), NOW(), 'seeder', 'seeder', 0, :project_id, 'Rebound Hammer (Concrete)',      'MPa',     25.0000, true),
    (gen_random_uuid(), NOW(), NOW(), 'seeder', 'seeder', 0, :project_id, 'Cover Meter (Rebar)',            'mm',      40.0000, true);

-- Verify
SELECT id, name, unit, irc_threshold FROM activity.qc_test_types WHERE project_id = :project_id ORDER BY name;
