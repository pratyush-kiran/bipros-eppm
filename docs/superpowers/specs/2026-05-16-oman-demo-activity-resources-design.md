# Oman-Demo Activity Resources Seeder — Design

**Date:** 2026-05-16
**Branch:** `hemendra-seeder-oman-demo`
**Author:** Hemendra + Claude
**Status:** Design — pending plan + implementation

## Problem

The `OMAN-DEMO-KHASAB` project's activities currently have **zero** `ResourceAssignment` rows. As a result:

- The Activity Resources tab in the UI is empty for every Oman-Demo activity.
- The AI tool `list_activity_resources` returns "No resources are assigned to this activity yet."
- EVM / cost rollups that lean on `resource_assignments` (e.g. `SummarizeActivityResourcesTool`, `GetActivityFullContextTool`, `CompareResourcesAcrossProjectsTool`) have nothing to aggregate.

Meanwhile the source workbook (`docs/ActualData/1. Daily Data-Khasab Jan, Feb, Mar 2026.xlsx`) carries rich per-day per-activity manpower / equipment / material usage that `OmanDemoDailyDataSeeder` loads into DPR child rows (`DprManpower`, `DprEquipment`, `DprMaterial`) but never rolls up onto the Activity.

## Goal

Aggregate every workbook DPR row by `(activityCode, kind, normalised_name)` summing `nos` and `working_hours` (and `quantity` for materials), then create one `ResourceAssignment` per bucket on the matching `Activity`.

After the seeder runs:

- Activity `2.3.6(i)b` should have ~9-12 ResourceAssignment rows: one per distinct trade (Foreman, Helper, Chargehand, Supervisor) and one per distinct PMV type (Tipper, Excavator, Wheel Loader).
- `ListActivityResourcesTool` returns those rows.
- The `project_resources` pool for `OMAN-DEMO-KHASAB` contains every Resource referenced by any assignment.

## Non-Goals

- No changes to the DPR loader or to the multi-supervisor model.
- No new master-data UI. Resources are created on-demand by the seeder via `SeederResourceFactory`.
- No retroactive recompute when DPR rows are added later. This is a seed-time roll-up only.
- No changes to other projects' resource seeding (Oman-Road, ICPMS, etc. keep their current seeders).

## Component

**New file:** `backend/bipros-api/src/main/java/com/bipros/api/config/seeder/OmanDemoActivityResourceSeeder.java`

- Profile: `seed`
- `@Order(207)` — after `OmanDemoDailyDataSeeder` (206), before `OmanDemoAnalyticsBackfillSeeder` (210).
- Implements `CommandLineRunner`.
- `@Transactional` on `run` so all assignments commit together (or roll back as one).

**Dependencies (injected):**

- `ProjectRepository`, `ActivityRepository`
- `ResourceRepository`, `ResourceAssignmentRepository`, `ProjectResourceRepository`
- `ResourceEquipmentDetailsRepository`, `ResourceMaterialDetailsRepository`
- `SeederResourceFactory` (existing — provides `requireType` + `ensureRole`)
- `OmanDemoWorkbookReader`

No new repository methods needed.

## Data Flow

```
OmanDemoWorkbookReader.readAllDailyRows()
   ↓
group by (activityCode, kind in {MANPOWER, EQUIPMENT, MATERIAL}, normalisedName)
   ↓
agg[(code, kind, name)] = { sumNos, sumHours, sumQty, sumCost, firstUnit }
   ↓
for each activityCode → fetch Activity (project = OMAN-DEMO-KHASAB)
   for each (kind, name) bucket on that activity:
      Resource res = ensureResource(kind, name, firstUnit)
      ensurePoolEntry(projectId, res.id)
      save ResourceAssignment(activityId, res.id, projectId,
          headcount=sumNos, duration=sumHours or quantity=sumQty,
          plannedUnits, actualUnits, plannedCost, actualCost,
          plannedStart=activity.plannedStartDate,
          plannedFinish=activity.plannedFinishDate,
          unit=firstUnit or "Hour",
          rateType="STANDARD",
          effectiveRate=sumCost/(sumHours or sumQty) when computable)
```

## Resource Catalogue Strategy

Resources are **shared** across activities. One `Resource` row per `(kind, normalised name)`:

- Manpower: code = `OMD-LAB-{SLUG(name)}` (e.g. `OMD-LAB-FOREMAN`, `OMD-LAB-HELPER`).
- Equipment: code = `OMD-EQ-{SLUG(name)}` (e.g. `OMD-EQ-TIPPER`, `OMD-EQ-EXCAVATOR`, `OMD-EQ-WHEEL-LOADER`).
- Material: code = `OMD-MAT-{SLUG(name)}` (e.g. `OMD-MAT-AGGREGATE-20MM`).

`SLUG()` = `name.trim().toUpperCase().replace(/[^A-Z0-9]+/g, "-").replace(/^-|-$/g, "")` capped at 40 chars (so the total stays ≤ 50 char limit on `Resource.code`).

The `OMD-` prefix isolates this catalogue from other projects' resources (which use prefixes like `EQP-`, `LAB-`, `MAT-`). Existing Resources with matching codes are reused; missing ones are created.

### Role / type mapping (cached `Map<String, String>` keyed by normalised name)

| Workbook name (lowercase, contains) | ResourceRole code | ResourceType |
|---|---|---|
| `foreman` | `FOREMAN` | MANPOWER |
| `chargehand` | `SKILLED_LABOUR` | MANPOWER |
| `supervisor` (manpower row, not the DPR supervisor) | `SUPERVISOR` (created on demand) | MANPOWER |
| `helper`, `cleaner` | `UNSKILLED_LABOUR` | MANPOWER |
| `mason`, `carpenter`, `steel fixer`, `plumber`, `painter` | `SKILLED_LABOUR` | MANPOWER |
| `operator` | `OPERATOR` | MANPOWER |
| `driver` | `DRIVER` | MANPOWER |
| `welder` | `WELDER` | MANPOWER |
| `electrician` | `ELECTRICIAN` | MANPOWER |
| `bankman`, `rigger`, `scaffolder`, `survey helper` | `SKILLED_LABOUR` | MANPOWER |
| `watchman`, `tyre man` | `UNSKILLED_LABOUR` | MANPOWER |
| (anything else, MANPOWER kind) | `IMPORTED-MANPOWER` (factory default) | MANPOWER |
| `tipper`, `dumper`, `truck` | `TRANSPORT_VEHICLES` | EQUIPMENT |
| `excavator`, `wheel loader`, `loader`, `dozer`, `bulldozer`, `backhoe`, `jcb` | `EARTH_MOVING` | EQUIPMENT |
| `grader`, `roller`, `paver` | `PAVING_EQUIPMENT` | EQUIPMENT |
| `crane` | `CRANES_LIFTING` | EQUIPMENT |
| `transit mixer`, `mixer`, `batching plant` | `CONCRETE_EQUIPMENT` | EQUIPMENT |
| (anything else, EQUIPMENT kind) | `IMPORTED-EQUIPMENT` | EQUIPMENT |
| (any material) | `IMPORTED-MATERIAL` | MATERIAL |

`SeederResourceFactory.ensureRole(code, typeCode)` creates the role if it doesn't exist — same pattern as `IcpmsPhaseDSeeder`.

### Type detail rows

When creating a new Resource:
- `EQUIPMENT` → save `ResourceEquipmentDetails(resourceId, quantityAvailable=null)`.
- `MATERIAL` → save `ResourceMaterialDetails(resourceId, baseUnit=firstUnit)`.
- `MANPOWER` → no detail table required.

This mirrors `IcpmsPhaseDSeeder.seedResource()` and `ExcelMasterDataLoader.loadResources()`.

## ResourceAssignment Field Mapping

| Field | Manpower / Equipment | Material |
|---|---|---|
| `projectId` | OMAN-DEMO-KHASAB project id | same |
| `activityId` | resolved by activity code | same |
| `resourceId` | Resource we just ensured | same |
| `headcount` | `sumNos` | `null` |
| `duration` | `BigDecimal.valueOf(sumHours)` | `null` |
| `quantity` | `null` | `sumQty` |
| `plannedUnits` | `sumHours` (as Double) | `sumQty.doubleValue()` |
| `actualUnits` | same as `plannedUnits` (workbook = actuals) | same |
| `remainingUnits` | `0.0` | `0.0` |
| `atCompletionUnits` | `plannedUnits` | `plannedUnits` |
| `plannedCost` | `sumCost` when > 0 else null | same |
| `actualCost` | same as `plannedCost` | same |
| `plannedStartDate` | `activity.plannedStartDate` | same |
| `plannedFinishDate` | `activity.plannedFinishDate` | same |
| `rateType` | `"STANDARD"` | same |
| `unit` | `"Hour"` | first material unit seen, fallback `"Each"` |
| `effectiveRate` | `sumCost / sumHours` when both > 0 | `sumCost / sumQty` |

`budgetedUnits` / `budgetedCost` mirror `planned*` (same convention as `IcpmsResourceAssignmentsSeeder`).

The variant FKs (`manpowerRoleRateId`, `equipmentRoleVariantId`, `materialRoleVariantId`) stay null — they belong to the role-rate-master model and aren't required for plain assignment reads.

## Idempotency

```
if assignmentRepository.countByProjectId(project.getId()) > 0:
   log "[oman-demo resources] already seeded, skipping"
   return
```

Uses `ResourceAssignmentRepository.countByProjectId(projectId)` — add this method if it doesn't exist (it's a simple derived query). To rebuild, delete `resource.resource_assignments WHERE project_id = …`.

## ProjectResource Pool

For every distinct `resourceId` referenced by a new assignment, ensure one `ProjectResource(projectId, resourceId)` exists. Pseudocode:

```
Set<UUID> ensuredPoolResourceIds = new HashSet<>();
…
if (ensuredPoolResourceIds.add(resourceId)
    && !projectResourceRepository.existsByProjectIdAndResourceId(projectId, resourceId)) {
    projectResourceRepository.save(ProjectResource.builder()
        .projectId(projectId).resourceId(resourceId).build());
}
```

Add `existsByProjectIdAndResourceId` to `ProjectResourceRepository` if it isn't already there (derived query).

`ProjectResourcePoolBackfill` (Order 960) still runs at the end but is now a no-op for Oman-Demo since the rows are already there. Its `count() > 0` guard means it skips entirely if our seeder ran — that's fine for the demo because the other projects either populate `project_resources` via their own seeders or run only on the `legacy-demo` profile.

## Logging

At end:
```
log.info("[oman-demo resources] seeded {} ResourceAssignment rows "
    + "({} manpower, {} equipment, {} material) "
    + "across {} activities; ensured {} Resources and {} ProjectResource pool rows",
    totalAssignments, mpAssignments, eqAssignments, matAssignments,
    activitiesTouched, resourcesEnsured, poolEntriesEnsured);
```

WARN on:
- Activity code in workbook with no matching Activity (skip the bucket, count once).
- ResourceAssignment save failure (continue with next bucket).

## Failure Modes

| Risk | Mitigation |
|---|---|
| Workbook not on classpath | `reader.dailyDataAvailable()` guard, log + skip. |
| Activity for code not yet seeded | Skip bucket, increment `unknownActivity` counter. |
| Duplicate `Resource.code` collision with another project's catalogue | `OMD-` prefix isolates the namespace; if a code already exists, reuse it. |
| `ResourceType` not seeded yet | `SeederResourceFactory.requireType` throws — surfaces missing master data loud and early; should be caught at boot of the `seed` profile. |
| Same `(project, resource)` pool row already exists | `existsByProjectIdAndResourceId` short-circuits before insert. |
| Pre-existing `ResourceAssignment` rows on activities (e.g. from a partial run) | The seed-level guard `countByProjectId > 0` returns immediately. |

## Verification

Manual SQL after seed:

```sql
-- Total assignments per kind for the Oman-Demo project
SELECT rt.code AS kind, COUNT(*) AS rows
FROM resource.resource_assignments ra
JOIN resource.resources r ON r.id = ra.resource_id
JOIN resource.resource_types rt ON rt.id = r.resource_type_id
JOIN project.projects p ON p.id = ra.project_id
WHERE p.code = 'OMAN-DEMO-KHASAB'
GROUP BY rt.code;

-- Resources for activity 2.3.6(i)b
SELECT r.code, r.name, ra.headcount, ra.duration, ra.quantity, ra.planned_cost
FROM resource.resource_assignments ra
JOIN resource.resources r ON r.id = ra.resource_id
JOIN activity.activities a ON a.id = ra.activity_id
WHERE a.code = '2.3.6(i)b'
ORDER BY r.code;
```

Expected for `2.3.6(i)b`: rows for `OMD-LAB-FOREMAN`, `OMD-LAB-HELPER`, `OMD-LAB-CHARGEHAND`, `OMD-LAB-SUPERVISOR`, `OMD-EQ-TIPPER`, `OMD-EQ-EXCAVATOR`, `OMD-EQ-WHEEL-LOADER` (and any materials when the workbook has them for that code).

AI tool check via `/v1/ai/chat`:

> "What resources are deployed on activity 2.3.6(i)b in Oman-Demo?"

Should return the full list with headcount + hours, not "no resources assigned yet."

## File Touch List

- **New:** `backend/bipros-api/src/main/java/com/bipros/api/config/seeder/OmanDemoActivityResourceSeeder.java`
- **Maybe edited:** `backend/bipros-resource/src/main/java/com/bipros/resource/domain/repository/ResourceAssignmentRepository.java` — add `countByProjectId(UUID projectId)` if missing.
- **Maybe edited:** `backend/bipros-resource/src/main/java/com/bipros/resource/domain/repository/ProjectResourceRepository.java` — add `existsByProjectIdAndResourceId(UUID, UUID)` if missing.

No frontend changes — the existing Activity Resources view + AI tool render the new rows automatically.

## Out of Scope (won't do)

- Liquibase changeset — DDL is unchanged.
- Test seeding for other projects (Khasab SC-180, Oman-Road, ICPMS) — they have their own seeders.
- Activity Resources UI changes — read-only consumer; no changes needed.
- Touching the materials sheet beyond what's already in the workbook reader.
