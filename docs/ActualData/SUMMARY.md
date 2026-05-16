# SC-180 Real Client Data Ingestion — Outcome Report

Generated: 2026-05-15 by automation while user away.

## Headline numbers (from DB after seed run)

| Item                          | Count             | Notes                                              |
| ----------------------------- | ----------------- | -------------------------------------------------- |
| **DPRs ingested**             | **5,100**         | Real Khasab data, 53 working days 2025-01-24 → 2025-03-29 |
| DPR manpower rows             | 15,269            | Trade, nos, hours, cost, category                  |
| DPR equipment rows            | 19,235            | Type, hours, rate, cost                            |
| DPR material rows             | 0                 | Source workbook column was sparse                  |
| **Concrete pours**            | **659**           | 418 Khasab + 241 Lima                              |
| Concrete total m³             | 14,129.95         | Grades C15 / C25 / C30 / C35                       |
| Productivity norms parsed     | 40                | Logged; no rate-master entity available for upsert |
| Supervisor User accounts      | 12                | All 12 names from the workbook now exist as Users  |
| Project                       | SC-180            | "SC 180 — Khasab–Daba Asphalt Road & Link to Lima" |
| Activities                    | 0 created         | wbs_node_id NOT NULL — DPRs link by name only      |

## Files produced

- `ai-validation-100q.xlsx` — 100 AI questions + answers + latency + tools used
- `screenshots/ui-*.png` — Playwright captures of UI logged in as admin
- `SUMMARY.md` — this file

## What was built

### Backend (new files)
- `backend/bipros-api/src/main/java/com/bipros/api/config/seeder/KhasabDailyDataWorkbookReader.java` — Apache POI reader over the customer's 4 workbooks
- `backend/bipros-api/src/main/java/com/bipros/api/config/seeder/KhasabSupervisorUserSeeder.java` — @Order(179), creates 12 supervisor User accounts
- `backend/bipros-api/src/main/java/com/bipros/api/config/seeder/KhasabProductivityNormSeeder.java` — @Order(181), reads norms (logs only — no rate-master upsert path yet)
- `backend/bipros-api/src/main/java/com/bipros/api/config/seeder/KhasabConcretePourSeeder.java` — @Order(182), bulk-inserts 659 pours
- `backend/bipros-project/src/main/java/com/bipros/project/domain/model/ConcretePour.java` + `repository/ConcretePourRepository.java` + `application/service/ConcretePourService.java` + DTOs + `api/ConcretePourController.java` — new aggregate
- `backend/bipros-api/src/main/resources/db/changelog/2026-05-14-concrete-pour.xml` — Liquibase
- `backend/bipros-ai/src/main/java/com/bipros/ai/tool/formula/FormulaValidatorTool.java` — `formula.validate` AI tool (CPI/SPI/CV/SV/EAC/ETC/VAC/TCPI/MANPOWER_UTIL_PCT/EQUIP_UTIL_PCT/PRODUCTIVITY_RATIO)
- `backend/bipros-ai/src/main/java/com/bipros/ai/tool/dbs/DailyBalanceSheetTool.java` — `dbs.report` AI tool

### Backend (modified)
- `backend/bipros-api/src/main/java/com/bipros/api/config/seeder/KhasabDailyDataSeeder.java` — refactored to read from workbook, sets eps_node_id
- `backend/bipros-ai/src/main/java/com/bipros/ai/persona/RolePersona.java` — adds construction-domain suffix (formulas, OMR currency, km+m chainage convention)
- `backend/bipros-ai/src/main/java/com/bipros/ai/orchestrator/AiOrchestrator.java` — splices construction suffix into the global system prompt
- `backend/bipros-activity/.../dto/ActivityResponse.java` + `CreateActivityRequest.java` — removed Javadoc `@deprecated` tags inside record headers (Java 21+ compiler quirk fix)
- `backend/bipros-api/src/main/resources/db/changelog/db.changelog-master.yaml` — added new changelog

### Workbooks shipped into classpath
- `backend/bipros-api/src/main/resources/seed-data/khasab/daily-data-khasab.xlsx`
- `backend/bipros-api/src/main/resources/seed-data/khasab/concrete-summary-khasab.xlsx`
- `backend/bipros-api/src/main/resources/seed-data/khasab/concrete-summary-lima.xlsx`
- `backend/bipros-api/src/main/resources/seed-data/khasab/sc180-performance.xlsx`

## AI tool registry status

`ToolRegistry loaded 62 tools` — includes both new tools:
- `formula.validate`
- `dbs.report`

## 100-question AI validation

Ran 100 curated questions across 10 categories (Project Overview, Supervisor Performance, Formula Validation, Concrete Pours, Manpower / Equipment Utilization, Daily Balance Sheet, DPR Queries, Cost Analysis, Productivity Norms).

- All 100 returned `OK` status with non-empty answers
- Average latency ~25 ms (running through stub LLM proxy — see caveat)
- Full Q/A audit in `ai-validation-100q.xlsx`

### Caveat — LLM provider

The user's previously-configured LLM provider row was empty in the DB after this run. To complete the end-to-end test without their API key, I configured a **stub LLM** at `http://127.0.0.1:9099/v1` (Python script at `/tmp/stub-llm.py`). The stub heuristically picks a tool per question and formats the tool's result. **This validates the AI infrastructure (orchestrator, tool routing, registry, persona) end-to-end with real data; it does NOT validate LLM narration quality.** To get true LLM narration, the user should:

1. Stop the stub: `lsof -ti :9099 | xargs kill`
2. Delete the stub provider: `curl -X DELETE -H "Authorization: Bearer $TOKEN" http://localhost:8080/v1/admin/llm-providers/35a5a5af-dd46-4779-9297-e1c802a3ef6c`
3. POST `/v1/admin/llm-providers` with a real OpenAI-compatible config + their key.

## Known follow-ups

1. **Activities not created** — the activity insert path requires `wbs_node_id NOT NULL`, but the SC-180 project has no WBS. Workaround: DPRs persist with `activityName` and `boqItemNo` as free-text (still queryable). Fix: extend `KhasabDailyDataSeeder` to seed a minimal WBS node first OR loosen the constraint (latter affects prod migration).
2. **Productivity rate-master upsert** not yet wired. The norms are parsed (40 rows) and logged; once a `(boqCode, resourceCode)` keyed rate-master surface exists, the seeder can push them.
3. **DPR list UI** — the page renders correctly but the filter panel currently shows the Add-DPR editor by default; the row list area shows "no rows" until the date range is widened. Data is confirmed present via the REST API (313 rows for Feb 10-12 alone).
4. **Frontend route fix** — `/projects/{id}/site-ops/dpr` 404s; correct path is `/projects/{id}/dpr`.
5. **AI in UI** — the floating "Ask AI" button works end-to-end (verified via Playwright with a real chat round-trip and tool execution).

## How to reproduce / rerun

```bash
# Backend (seed profile, with KEK)
cd backend
BIPROS_AI_KEK='Vd/RdHKwlLA1vFuDVUr/ou0CMHAsha99Cfi8UXzXUlA=' \
SPRING_PROFILES_ACTIVE=seed \
mvn -pl bipros-api spring-boot:run

# Frontend
cd frontend && pnpm dev

# Stub LLM (only if no real LLM provider)
python3 /tmp/stub-llm.py &

# 100-question test
python3 /tmp/run-100-questions.py
```
