-- =====================================================================
-- Tune productivity norms to realistic values per activity family.
-- Generated 2026-05-24 after observing actual DPR throughput vs initial norms.
-- Brings Capacity Utilization % into demo-realistic range (mostly 50-200%)
-- =====================================================================
-- Run AFTER fix_demo_v2.py / create_norms_only.py have set up the 66 norms.

-- ===== Manpower per-activity-family =====
UPDATE resource.productivity_norms SET
  output_per_man_per_day = CASE
    -- Excavation / earthwork — Helper-dominated, bulk qty (set high so Helper ~80-100% util)
    WHEN wa.code LIKE '2.3%' OR wa.code LIKE '2.4%' OR wa.code LIKE '2.6%'
      OR wa.code LIKE '2.7%' OR wa.code LIKE '2.8%' THEN 350
    -- GSB / ABC layers — Helper + Foreman moderate
    WHEN wa.code LIKE '3.2%' OR wa.code LIKE '3.3%' THEN 100
    -- Concrete / steel — Mason/Carpenter low throughput, Steel Fixer moderate
    WHEN wa.code LIKE '5%' OR wa.code LIKE '13.1%' THEN 70
    -- Drainage / pavement / service-ducts
    WHEN wa.code LIKE '9.1%' OR wa.code LIKE '18%' THEN 30
    -- Preliminaries (site establishment, mobilization)
    WHEN wa.code IN ('1', '1.1', '1.2') THEN 100
    -- Setting out / layout
    WHEN wa.code LIKE '2.1%' THEN 50
    ELSE 50
  END,
  output_per_day = CASE
    WHEN wa.code LIKE '2.3%' OR wa.code LIKE '2.4%' THEN 1800   -- crew of ~5 × 350
    WHEN wa.code LIKE '5%' THEN 350                              -- crew of 5 × 70
    ELSE 500
  END
FROM resource.work_activities wa
WHERE resource.productivity_norms.work_activity_id = wa.id
  AND resource.productivity_norms.norm_type = 'MANPOWER'
  AND wa.id IN (
    SELECT work_activity_id FROM activity.activities
    WHERE project_id = (SELECT id FROM project.projects WHERE code='KHASAB-2026')
  );

-- ===== Equipment per-activity-family =====
UPDATE resource.productivity_norms SET
  output_per_hour = CASE
    -- Excavation: bulk earthwork — high throughput per machine-hour
    WHEN wa.code LIKE '2.3%' OR wa.code LIKE '2.4%' OR wa.code LIKE '2.6%'
      OR wa.code LIKE '2.7%' OR wa.code LIKE '2.8%' THEN 80
    -- GSB/ABC + preliminaries
    WHEN wa.code LIKE '3.%' OR wa.code = '1' OR wa.code IN ('1.1','1.2') THEN 18
    -- Concrete (small batches)
    WHEN wa.code LIKE '5%' THEN 5
    -- Drainage / pavement / barriers / service-ducts
    WHEN wa.code LIKE '9.1%' OR wa.code LIKE '13.1%' OR wa.code LIKE '18%' THEN 10
    ELSE 15
  END,
  output_per_day = CASE
    WHEN wa.code LIKE '2.3%' OR wa.code LIKE '2.4%' THEN 640
    WHEN wa.code LIKE '3.%' OR wa.code = '1' OR wa.code IN ('1.1','1.2') THEN 150
    WHEN wa.code LIKE '5%' THEN 40
    ELSE 120
  END
FROM resource.work_activities wa
WHERE resource.productivity_norms.work_activity_id = wa.id
  AND resource.productivity_norms.norm_type = 'EQUIPMENT'
  AND wa.id IN (
    SELECT work_activity_id FROM activity.activities
    WHERE project_id = (SELECT id FROM project.projects WHERE code='KHASAB-2026')
  );

-- ===== Verify =====
\echo '=== Norm averages by family ==='
SELECT
  CASE WHEN wa.code LIKE '2.3%' OR wa.code LIKE '2.4%' THEN 'Excavation'
       WHEN wa.code LIKE '5%' THEN 'Concrete'
       WHEN wa.code IN ('1','1.1','1.2') THEN 'Preliminaries'
       WHEN wa.code LIKE '3.%' THEN 'GSB/ABC'
       WHEN wa.code LIKE '13.1%' THEN 'Barriers'
       WHEN wa.code LIKE '18%' THEN 'Service ducts'
       ELSE 'Other' END AS family,
  pn.norm_type,
  COUNT(*) AS norms,
  ROUND(AVG(output_per_man_per_day)::numeric, 1) AS per_man_day,
  ROUND(AVG(output_per_hour)::numeric, 1) AS per_hour,
  ROUND(AVG(output_per_day)::numeric, 1) AS per_day
FROM resource.productivity_norms pn
JOIN resource.work_activities wa ON wa.id = pn.work_activity_id
WHERE wa.id IN (
  SELECT work_activity_id FROM activity.activities
  WHERE project_id=(SELECT id FROM project.projects WHERE code='KHASAB-2026')
)
GROUP BY family, pn.norm_type
ORDER BY family, pn.norm_type;
