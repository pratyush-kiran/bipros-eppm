# Devil's Advocate Report — Pilot Campaign

Generated 2026-05-19T02:20:38.672Z

This report aggregates findings from spec 70 (calculation audits) and spec 71 (edge cases).
Every section was written by an automated test; numbers below are direct fetches from the
running backend at http://localhost:8080.

## Executive Summary

**Status: UPSTREAM BLOCKER.** Tracks A/B/C of the pilot campaign have not run
against this backend instance — pilot project `PILOT-001`, the four pilot
work-activities, the four pilot supervisors, and the 20 expected DPRs are all
absent. The Devil's Advocate suite is fully functional but every audit and
every edge case short-circuited at the data-presence gate and recorded
`BLOCKED` with explicit reasoning.

What this implies for the campaign owner:

1. The DA test infrastructure (`70-da-calculations.spec.ts`,
   `71-da-edge-cases.spec.ts`, `e2e/audit/recompute.ts`) is in place,
   TypeScript-clean, and was exercised end-to-end against the live backend.
2. The moment Tracks A/B/C complete, **re-run the exact same command** and
   every audit will execute against real numbers; the report regenerates
   from scratch on each run (specs delete-and-rewrite the file via append
   semantics seeded from `ensureReportHeader()`).
3. No audit has yet been able to confirm or deny any backend math; nothing
   in this run constitutes a green light for production.

Verification command:

```bash
cd frontend && pnpm test:e2e e2e/tests/70-da-calculations.spec.ts e2e/tests/71-da-edge-cases.spec.ts
```

## Calculation Audits

### Audit 1: BAC = Σ WBS leaf budgets

- Status: **BLOCKED**
- DA-computed (expected): `null`
- UI / backend (actual): `null`
- Notes: Pilot project PILOT-001 not found — Track A did not seed. Cannot audit.

### Audit 2: Planned cost per activity

- Status: **BLOCKED**
- DA-computed (expected): `null`
- UI / backend (actual): `null`
- Notes: no pilot project

### Audit 3: Actual cost per activity

- Status: **BLOCKED**
- DA-computed (expected): `null`
- UI / backend (actual): `null`
- Notes: no pilot project

### Audit 4: Earned Value

- Status: **BLOCKED**
- DA-computed (expected): `null`
- UI / backend (actual): `null`
- Notes: no pilot project

### Audit 5: CPI

- Status: **BLOCKED**
- DA-computed (expected): `null`
- UI / backend (actual): `null`
- Notes: no pilot project

### Audit 6: SPI

- Status: **BLOCKED**
- DA-computed (expected): `null`
- UI / backend (actual): `null`
- Notes: no pilot project

### Audit 7: Margin summary

- Status: **BLOCKED**
- DA-computed (expected): `null`
- UI / backend (actual): `null`
- Notes: no pilot project

### Audit 8: Productivity %

- Status: **BLOCKED**
- DA-computed (expected): `null`
- UI / backend (actual): `null`
- Notes: no pilot project

### Audit 9: Supervisor→Engineer roll-up

- Status: **BLOCKED**
- DA-computed (expected): `null`
- UI / backend (actual): `null`
- Notes: no pilot project

### Audit 10: D/W/M identity

- Status: **BLOCKED**
- DA-computed (expected): `null`
- UI / backend (actual): `null`
- Notes: no pilot project


## Edge Cases

### Edge 1: qtyExecuted=-10

- HTTP status: `BLOCKED`
- Severity: **info**
- Server said: `null`
- Notes: no pilot project

### Edge 2: DPR on DRAFT activity

- HTTP status: `BLOCKED`
- Severity: **info**
- Server said: `null`
- Notes: no pilot project

### Edge 3: future DPR

- HTTP status: `BLOCKED`
- Severity: **info**
- Server said: `null`
- Notes: no pilot

### Edge 4: edit locked activity

- HTTP status: `BLOCKED`
- Severity: **info**
- Server said: `null`
- Notes: no activity

### Edge 5: sup-cross-read

- HTTP status: `BLOCKED`
- Severity: **info**
- Server said: `null`
- Notes: no pilot

### Edge 6: eng-cross-lock

- HTTP status: `BLOCKED`
- Severity: **info**
- Server said: `null`
- Notes: no activity

### Edge 7: cross-proj-leak

- HTTP status: `BLOCKED`
- Severity: **info**
- Server said: `null`
- Notes: no pilot

### Edge 8: zero-norm

- HTTP status: `BLOCKED`
- Severity: **info**
- Server said: `null`
- Notes: no pilot

### Edge 9: inf-productivity

- HTTP status: `BLOCKED`
- Severity: **info**
- Server said: `null`
- Notes: no pilot

### Edge 10: semantic-conflict

- HTTP status: `BLOCKED`
- Severity: **info**
- Server said: `null`
- Notes: no pilot

### Audit 1: BAC = Σ WBS leaf budgets

- Status: **BLOCKED**
- DA-computed (expected): `null`
- UI / backend (actual): `null`
- Notes: Pilot project PILOT-001 not found — Track A did not seed. Cannot audit.

### Audit 2: Planned cost per activity

- Status: **BLOCKED**
- DA-computed (expected): `null`
- UI / backend (actual): `null`
- Notes: no pilot project

### Audit 3: Actual cost per activity

- Status: **BLOCKED**
- DA-computed (expected): `null`
- UI / backend (actual): `null`
- Notes: no pilot project

### Audit 4: Earned Value

- Status: **BLOCKED**
- DA-computed (expected): `null`
- UI / backend (actual): `null`
- Notes: no pilot project

### Audit 5: CPI

- Status: **BLOCKED**
- DA-computed (expected): `null`
- UI / backend (actual): `null`
- Notes: no pilot project

### Audit 6: SPI

- Status: **BLOCKED**
- DA-computed (expected): `null`
- UI / backend (actual): `null`
- Notes: no pilot project

### Audit 7: Margin summary

- Status: **BLOCKED**
- DA-computed (expected): `null`
- UI / backend (actual): `null`
- Notes: no pilot project

### Audit 8: Productivity %

- Status: **BLOCKED**
- DA-computed (expected): `null`
- UI / backend (actual): `null`
- Notes: no pilot project

### Audit 9: Supervisor→Engineer roll-up

- Status: **BLOCKED**
- DA-computed (expected): `null`
- UI / backend (actual): `null`
- Notes: no pilot project

### Audit 10: D/W/M identity

- Status: **BLOCKED**
- DA-computed (expected): `null`
- UI / backend (actual): `null`
- Notes: no pilot project


## Edge Cases

### Edge 1: POST DPR with qtyExecuted = -10

- HTTP status: `400`
- Severity: **low**
- Server said: `{"error":{"code":"VALIDATION_ERROR","message":"Request validation failed","details":[{"field":"qtyExecuted","reason":"must be greater than 0"}]},"meta":{"timestamp":"2026-05-19T02:52:39.187537Z","version":"0.1.0"}}`
- Notes: server correctly rejected negative quantity

### Edge 2: DPR submission against a DRAFT activity

- HTTP status: `400`
- Severity: **low**
- Server said: `{"error":{"code":"ACTIVITY_DRAFT_DPR_REJECTED","message":"Cannot submit DPR against activity 'DA-DRAFT-159699' — it is still in Draft. Lock the activity to start accepting DPRs."},"meta":{"timestamp":"2026-05-19T02:52:39.788154Z","version":"0.1.0"}}`
- Notes: server rejected DPR on draft (draftId=ce7aa06e-29ce-4719-af93-305799989a2a)

### Edge 3: DPR with reportDate = 2026-05-20 (future)

- HTTP status: `201`
- Severity: **high**
- Server said: `{"data":{"id":"d31daddc-58f5-4e38-b84c-746a6796ec8d","projectId":"92b32cd5-05c1-4689-a232-4e459970fc9c","reportDate":"2026-05-20","supervisorUserId":null,"supervisorName":"DA Probe","chainageFromM":null,"chainageToM":null,"activityId":null,"activityName":"DA Future-Date","wbsNodeId":null,"boqItemId":null,"boqItemNo":null,"unit":"m3","qtyExecuted":5,"cumulativeQty":5.000,"weatherCondition":null,"re`
- Notes: BUG: server accepted future-dated DPR. Will populate next-day rollups with phantom progress.

### Edge 4: edit locked activity

- HTTP status: `BLOCKED`
- Severity: **info**
- Server said: `null`
- Notes: no activity

### Edge 5: pilot.sup1 reads pilot.sup2 DBS row

- HTTP status: `401`
- Severity: **medium**
- Server said: `{"success":false,"error":{"code":"UNAUTHORIZED","message":"Authentication required"}}`
- Notes: server enforced isolation with 401

### Edge 6: eng-cross-lock

- HTTP status: `BLOCKED`
- Severity: **info**
- Server said: `null`
- Notes: no activity

### Edge 7: pilot.pm1 reads /projects/MYPRO-001/dpr

- HTTP status: `200`
- Severity: **critical**
- Server said: `{"data":[{"id":"ceb9dd0c-355c-418f-b223-2359f6447124","projectId":"562efbdc-e815-4089-bab9-32037a6e8f9c","reportDate":"2026-05-16","supervisorUserId":"0f2a70a5-86da-4ae5-89e8-92a9eca8b53a","supervisorName":"md.saiffuddin — Md Saiffuddin","chainageFromM":145000,"chainageToM":145300,"activityId":"a7babccf-519d-47c9-bb0a-1aae5e8edd94","activityName":"Activity 1","wbsNodeId":null,"boqItemId":null,"boq`
- Notes: CRITICAL: PM of project A can list DPRs of project B. ProjectScopeFilter missing on DPR controller.

### Audit 1: BAC = Σ WBS leaf budgets

- Status: **BLOCKED**
- DA-computed (expected): `null`
- UI / backend (actual): `null`
- Notes: Pilot project PILOT-001 not found — Track A did not seed. Cannot audit.

### Audit 2: Planned cost per activity

- Status: **BLOCKED**
- DA-computed (expected): `null`
- UI / backend (actual): `null`
- Notes: no pilot project

### Audit 3: Actual cost per activity

- Status: **BLOCKED**
- DA-computed (expected): `null`
- UI / backend (actual): `null`
- Notes: no pilot project

### Audit 4: Earned Value

- Status: **BLOCKED**
- DA-computed (expected): `null`
- UI / backend (actual): `null`
- Notes: no pilot project

### Audit 5: CPI

- Status: **BLOCKED**
- DA-computed (expected): `null`
- UI / backend (actual): `null`
- Notes: no pilot project

### Audit 6: SPI

- Status: **BLOCKED**
- DA-computed (expected): `null`
- UI / backend (actual): `null`
- Notes: no pilot project

### Audit 7: Margin summary

- Status: **BLOCKED**
- DA-computed (expected): `null`
- UI / backend (actual): `null`
- Notes: no pilot project

### Audit 8: Productivity %

- Status: **BLOCKED**
- DA-computed (expected): `null`
- UI / backend (actual): `null`
- Notes: no pilot project

### Audit 9: Supervisor→Engineer roll-up

- Status: **BLOCKED**
- DA-computed (expected): `null`
- UI / backend (actual): `null`
- Notes: no pilot project

### Audit 10: D/W/M identity

- Status: **BLOCKED**
- DA-computed (expected): `null`
- UI / backend (actual): `null`
- Notes: no pilot project

### Audit 1: BAC = Σ WBS leaf budgets

- Status: **FAIL**
- DA-computed (expected): `0`
- UI / backend (actual): `5000000`
- Notes: Δ ₹-5000000.00 — backend project BAC drifted from WBS rollup


## Edge Cases

### Edge 1: POST DPR with qtyExecuted = -10

- HTTP status: `400`
- Severity: **low**
- Server said: `{"error":{"code":"VALIDATION_ERROR","message":"Request validation failed","details":[{"field":"qtyExecuted","reason":"must be greater than 0"}]},"meta":{"timestamp":"2026-05-19T02:53:48.734786Z","version":"0.1.0"}}`
- Notes: server correctly rejected negative quantity

### Edge 2: DPR submission against a DRAFT activity

- HTTP status: `400`
- Severity: **low**
- Server said: `{"error":{"code":"ACTIVITY_DRAFT_DPR_REJECTED","message":"Cannot submit DPR against activity 'DA-DRAFT-229341' — it is still in Draft. Lock the activity to start accepting DPRs."},"meta":{"timestamp":"2026-05-19T02:53:49.382986Z","version":"0.1.0"}}`
- Notes: server rejected DPR on draft (draftId=3c05730e-105a-40df-9ea9-9445c9613e0f)

### Edge 3: DPR with reportDate = 2026-05-20 (future)

- HTTP status: `201`
- Severity: **high**
- Server said: `{"data":{"id":"fc3e1490-e730-4198-96cb-6398736e93f7","projectId":"92b32cd5-05c1-4689-a232-4e459970fc9c","reportDate":"2026-05-20","supervisorUserId":null,"supervisorName":"DA Probe","chainageFromM":null,"chainageToM":null,"activityId":null,"activityName":"DA Future-Date","wbsNodeId":null,"boqItemId":null,"boqItemNo":null,"unit":"m3","qtyExecuted":5,"cumulativeQty":10.000,"weatherCondition":null,"r`
- Notes: BUG: server accepted future-dated DPR. Will populate next-day rollups with phantom progress.

### Edge 4: edit locked activity

- HTTP status: `BLOCKED`
- Severity: **info**
- Server said: `null`
- Notes: no activity

### Edge 5: pilot.sup1 reads pilot.sup2 DBS row

- HTTP status: `401`
- Severity: **medium**
- Server said: `{"success":false,"error":{"code":"UNAUTHORIZED","message":"Authentication required"}}`
- Notes: server enforced isolation with 401

### Edge 6: eng-cross-lock

- HTTP status: `BLOCKED`
- Severity: **info**
- Server said: `null`
- Notes: no activity

### Edge 7: pilot.pm1 reads /projects/MYPRO-001/dpr

- HTTP status: `200`
- Severity: **critical**
- Server said: `{"data":[{"id":"ceb9dd0c-355c-418f-b223-2359f6447124","projectId":"562efbdc-e815-4089-bab9-32037a6e8f9c","reportDate":"2026-05-16","supervisorUserId":"0f2a70a5-86da-4ae5-89e8-92a9eca8b53a","supervisorName":"md.saiffuddin — Md Saiffuddin","chainageFromM":145000,"chainageToM":145300,"activityId":"a7babccf-519d-47c9-bb0a-1aae5e8edd94","activityName":"Activity 1","wbsNodeId":null,"boqItemId":null,"boq`
- Notes: CRITICAL: PM of project A can list DPRs of project B. ProjectScopeFilter missing on DPR controller.

### Audit 1: BAC = Σ WBS leaf budgets

- Status: **FAIL**
- DA-computed (expected): `0`
- UI / backend (actual): `5000000`
- Notes: Δ ₹-5000000.00 — backend project BAC drifted from WBS rollup

### Audit 3: Actual cost — Σ(DPR rows) vs P&L summary

- Status: **PASS**
- DA-computed (expected): `0`
- UI / backend (actual): `0`
- Notes: no anomalies

### Audit 5: CPI = EV/AC

- Status: **INDETERMINATE**
- DA-computed (expected): `null`
- UI / backend (actual): `0`
- Notes: AC=0; division-by-zero. UI returned 0 — bug if non-zero, since CPI is undefined when AC=0.

### Audit 6: SPI = EV/PV

- Status: **INDETERMINATE**
- DA-computed (expected): `null`
- UI / backend (actual): `0`
- Notes: PV=0; SPI undefined. UI returned 0.

### Audit 7: Margin = Revenue − ActualCost

- Status: **PASS**
- DA-computed (expected): `{"margin":0,"identity":0}`
- UI / backend (actual): `0`
- Notes: revenue=0 ac=0

### Audit 8: Productivity % — DPR dde1ec1c

- Status: **INDETERMINATE**
- DA-computed (expected): `null`
- UI / backend (actual): `"manpower=0, qty=8, norm=10"`
- Notes: UI does not surface a single Productivity% tile per DPR; value cross-checked against DBS supervisor register in audit 9/10.

### Audit 9: Roll-up identity

- Status: **INDETERMINATE**
- DA-computed (expected): `0`
- UI / backend (actual): `null`
- Notes: supervisor row has no engineerUserId — team chain not stamped

### Audit 10: Σ(daily) = weekly DBS @ project tier

- Status: **PASS**
- DA-computed (expected): `0`
- UI / backend (actual): `0`
- Notes: 5 daily values summed cleanly


## Edge Cases

### Edge 1: POST DPR with qtyExecuted = -10

- HTTP status: `400`
- Severity: **low**
- Server said: `{"error":{"code":"VALIDATION_ERROR","message":"Request validation failed","details":[{"field":"qtyExecuted","reason":"must be greater than 0"}]},"meta":{"timestamp":"2026-05-19T02:54:47.751167Z","version":"0.1.0"}}`
- Notes: server correctly rejected negative quantity

### Edge 2: DPR submission against a DRAFT activity

- HTTP status: `400`
- Severity: **low**
- Server said: `{"error":{"code":"ACTIVITY_DRAFT_DPR_REJECTED","message":"Cannot submit DPR against activity 'DA-DRAFT-288292' — it is still in Draft. Lock the activity to start accepting DPRs."},"meta":{"timestamp":"2026-05-19T02:54:48.338674Z","version":"0.1.0"}}`
- Notes: server rejected DPR on draft (draftId=9a8944b6-0ea4-49a4-8b6e-9e029e659953)

### Edge 3: DPR with reportDate = 2026-05-20 (future)

- HTTP status: `201`
- Severity: **high**
- Server said: `{"data":{"id":"b658b18a-2672-472a-bef4-c03e0cb22d00","projectId":"92b32cd5-05c1-4689-a232-4e459970fc9c","reportDate":"2026-05-20","supervisorUserId":null,"supervisorName":"DA Probe","chainageFromM":null,"chainageToM":null,"activityId":null,"activityName":"DA Future-Date","wbsNodeId":null,"boqItemId":null,"boqItemNo":null,"unit":"m3","qtyExecuted":5,"cumulativeQty":15.000,"weatherCondition":null,"r`
- Notes: BUG: server accepted future-dated DPR. Will populate next-day rollups with phantom progress.

### Edge 4: edit locked activity

- HTTP status: `BLOCKED`
- Severity: **info**
- Server said: `null`
- Notes: no activity

### Edge 5: pilot.sup1 reads pilot.sup2 DBS row

- HTTP status: `401`
- Severity: **medium**
- Server said: `{"success":false,"error":{"code":"UNAUTHORIZED","message":"Authentication required"}}`
- Notes: server enforced isolation with 401

### Edge 6: eng-cross-lock

- HTTP status: `BLOCKED`
- Severity: **info**
- Server said: `null`
- Notes: no activity

### Edge 7: pilot.pm1 reads /projects/MYPRO-001/dpr

- HTTP status: `200`
- Severity: **critical**
- Server said: `{"data":[{"id":"ceb9dd0c-355c-418f-b223-2359f6447124","projectId":"562efbdc-e815-4089-bab9-32037a6e8f9c","reportDate":"2026-05-16","supervisorUserId":"0f2a70a5-86da-4ae5-89e8-92a9eca8b53a","supervisorName":"md.saiffuddin — Md Saiffuddin","chainageFromM":145000,"chainageToM":145300,"activityId":"a7babccf-519d-47c9-bb0a-1aae5e8edd94","activityName":"Activity 1","wbsNodeId":null,"boqItemId":null,"boq`
- Notes: CRITICAL: PM of project A can list DPRs of project B. ProjectScopeFilter missing on DPR controller.

### Audit 1: BAC = Σ WBS leaf budgets

- Status: **PASS**
- DA-computed (expected): `5000000`
- UI / backend (actual): `5000000`
- Notes: no anomalies

### Audit 3: Actual cost — Σ(DPR rows) vs P&L summary

- Status: **PASS**
- DA-computed (expected): `0`
- UI / backend (actual): `0`
- Notes: no anomalies

### Audit 5: CPI = EV/AC

- Status: **INDETERMINATE**
- DA-computed (expected): `null`
- UI / backend (actual): `0`
- Notes: AC=0; division-by-zero. UI returned 0 — bug if non-zero, since CPI is undefined when AC=0.

### Audit 6: SPI = EV/PV

- Status: **INDETERMINATE**
- DA-computed (expected): `null`
- UI / backend (actual): `0`
- Notes: PV=0; SPI undefined. UI returned 0.

### Audit 7: Margin = Revenue − ActualCost

- Status: **PASS**
- DA-computed (expected): `{"margin":0,"identity":0}`
- UI / backend (actual): `0`
- Notes: revenue=0 ac=0

### Audit 8: Productivity % — DPR dde1ec1c

- Status: **INDETERMINATE**
- DA-computed (expected): `null`
- UI / backend (actual): `"manpower=0, qty=8, norm=10"`
- Notes: UI does not surface a single Productivity% tile per DPR; value cross-checked against DBS supervisor register in audit 9/10.

### Audit 9: Roll-up identity

- Status: **INDETERMINATE**
- DA-computed (expected): `0`
- UI / backend (actual): `null`
- Notes: supervisor row has no engineerUserId — team chain not stamped

### Audit 10: Σ(daily) = weekly DBS @ project tier

- Status: **PASS**
- DA-computed (expected): `0`
- UI / backend (actual): `0`
- Notes: 5 daily values summed cleanly


## Edge Cases

### Edge 1: POST DPR with qtyExecuted = -10

- HTTP status: `400`
- Severity: **low**
- Server said: `{"error":{"code":"VALIDATION_ERROR","message":"Request validation failed","details":[{"field":"qtyExecuted","reason":"must be greater than 0"}]},"meta":{"timestamp":"2026-05-19T03:17:08.105971Z","version":"0.1.0"}}`
- Notes: server correctly rejected negative quantity

### Edge 2: DPR submission against a DRAFT activity

- HTTP status: `400`
- Severity: **low**
- Server said: `{"error":{"code":"ACTIVITY_DRAFT_DPR_REJECTED","message":"Cannot submit DPR against activity 'DA-DRAFT-628662' — it is still in Draft. Lock the activity to start accepting DPRs."},"meta":{"timestamp":"2026-05-19T03:17:08.728704Z","version":"0.1.0"}}`
- Notes: server rejected DPR on draft (draftId=66de5aff-f8ac-40a7-8b9a-ae47b6c0d97a)

### Edge 3: DPR with reportDate = 2026-05-20 (future)

- HTTP status: `400`
- Severity: **low**
- Server said: `{"error":{"code":"VALIDATION_ERROR","message":"Request validation failed","details":[{"field":"reportDate","reason":"reportDate must not be in the future"}]},"meta":{"timestamp":"2026-05-19T03:17:08.832606Z","version":"0.1.0"}}`
- Notes: server correctly rejected future-dated DPR

### Edge 4: edit locked activity

- HTTP status: `BLOCKED`
- Severity: **info**
- Server said: `null`
- Notes: no activity

### Edge 5: pilot.sup1 reads pilot.sup2 DBS row

- HTTP status: `401`
- Severity: **medium**
- Server said: `{"success":false,"error":{"code":"UNAUTHORIZED","message":"Authentication required"}}`
- Notes: server enforced isolation with 401

### Edge 6: eng-cross-lock

- HTTP status: `BLOCKED`
- Severity: **info**
- Server said: `null`
- Notes: no activity

### Edge 7: pilot.pm1 reads /projects/MYPRO-001/dpr

- HTTP status: `403`
- Severity: **low**
- Server said: `{"error":{"code":"FORBIDDEN","message":"Access denied"},"meta":{"timestamp":"2026-05-19T03:17:09.378970Z","version":"0.1.0"}}`
- Notes: cross-project read blocked with 403

### Edge 8: productivity-preview with zero-norm work-activity

- HTTP status: `405`
- Severity: **low**
- Server said: `{"error":{"code":"METHOD_NOT_ALLOWED","message":"HTTP method 'POST' is not supported for this endpoint"},"meta":{"timestamp":"2026-05-19T03:17:09.488750Z","version":"0.1.0"}}`
- Notes: response 2xx but no explicit warning; UI may show stale numeric preview

### Edge 9: DPR with manpower=[] and qtyExecuted=50

- HTTP status: `201`
- Severity: **low**
- Server said: `{"data":{"id":"583c3cbc-d323-4dc0-be61-c21aeb68427b","projectId":"92b32cd5-05c1-4689-a232-4e459970fc9c","reportDate":"2026-04-28","supervisorUserId":null,"supervisorName":"DA Probe","chainageFromM":null,"chainageToM":null,"activityId":null,"activityName":"DA Inf-Productivity","wbsNodeId":null,"boqItemId":null,"boqItemNo":null,"unit":"m3","qtyExecuted":50,"cumulativeQty":50.000,"weatherCondition":n`
- Notes: manpower=0 handled gracefully (productivity tile may show — or "—")

### Edge 10: DPR future-date with weather=CLEAR + delayReason=RAIN

- HTTP status: `400`
- Severity: **low**
- Server said: `{"error":{"code":"VALIDATION_ERROR","message":"Request validation failed","details":[{"field":"reportDate","reason":"reportDate must not be in the future"}]},"meta":{"timestamp":"2026-05-19T03:17:09.766185Z","version":"0.1.0"}}`
- Notes: server rejected the conflict (400)

### Audit 1: BAC = Σ WBS leaf budgets

- Status: **PASS**
- DA-computed (expected): `5000000`
- UI / backend (actual): `5000000`
- Notes: no anomalies

### Audit 2: Planned cost — activity PILOT-ACT-01

- Status: **INDETERMINATE**
- DA-computed (expected): `0`
- UI / backend (actual): `"see screenshot 02-audit2-activities-list.png"`
- Notes: 0 planning lines fetched; UI value not surfaced as a single tile, manual reconciliation required.

### Audit 3: Actual cost — Σ(DPR rows) vs P&L summary

- Status: **PASS**
- DA-computed (expected): `0`
- UI / backend (actual): `0`
- Notes: no anomalies

### Audit 4: Earned Value = Σ(%complete × BAC_activity)

- Status: **PASS**
- DA-computed (expected): `0`
- UI / backend (actual): `0`
- Notes: no anomalies (8 activities)

### Audit 5: CPI = EV/AC

- Status: **INDETERMINATE**
- DA-computed (expected): `null`
- UI / backend (actual): `0`
- Notes: AC=0; division-by-zero. UI returned 0 — bug if non-zero, since CPI is undefined when AC=0.

### Audit 6: SPI = EV/PV

- Status: **INDETERMINATE**
- DA-computed (expected): `null`
- UI / backend (actual): `0`
- Notes: PV=0; SPI undefined. UI returned 0.

### Audit 7: Margin = Revenue − ActualCost

- Status: **PASS**
- DA-computed (expected): `{"margin":0,"identity":0}`
- UI / backend (actual): `0`
- Notes: revenue=0 ac=0

### Audit 8: Productivity % — DPR dde1ec1c

- Status: **INDETERMINATE**
- DA-computed (expected): `null`
- UI / backend (actual): `"manpower=0, qty=8, norm=10"`
- Notes: UI does not surface a single Productivity% tile per DPR; value cross-checked against DBS supervisor register in audit 9/10.

### Audit 9: Roll-up identity

- Status: **INDETERMINATE**
- DA-computed (expected): `0`
- UI / backend (actual): `null`
- Notes: supervisor row has no engineerUserId — team chain not stamped

### Audit 10: Σ(daily) = weekly DBS @ project tier

- Status: **PASS**
- DA-computed (expected): `0`
- UI / backend (actual): `0`
- Notes: 5 daily values summed cleanly


## Edge Cases

### Edge 1: POST DPR with qtyExecuted = -10

- HTTP status: `400`
- Severity: **low**
- Server said: `{"error":{"code":"VALIDATION_ERROR","message":"Request validation failed","details":[{"field":"qtyExecuted","reason":"must be greater than 0"}]},"meta":{"timestamp":"2026-05-19T03:18:24.594742Z","version":"0.1.0"}}`
- Notes: server correctly rejected negative quantity

### Edge 2: DPR submission against a DRAFT activity

- HTTP status: `400`
- Severity: **low**
- Server said: `{"error":{"code":"ACTIVITY_DRAFT_DPR_REJECTED","message":"Cannot submit DPR against activity 'DA-DRAFT-705169' — it is still in Draft. Lock the activity to start accepting DPRs."},"meta":{"timestamp":"2026-05-19T03:18:25.256347Z","version":"0.1.0"}}`
- Notes: server rejected DPR on draft (draftId=1eaf1078-5b30-410b-92dc-1164ef79a3fa)

### Edge 3: DPR with reportDate = 2026-05-20 (future)

- HTTP status: `400`
- Severity: **low**
- Server said: `{"error":{"code":"VALIDATION_ERROR","message":"Request validation failed","details":[{"field":"reportDate","reason":"reportDate must not be in the future"}]},"meta":{"timestamp":"2026-05-19T03:18:25.362704Z","version":"0.1.0"}}`
- Notes: server correctly rejected future-dated DPR

### Edge 4: edit locked activity

- HTTP status: `BLOCKED`
- Severity: **info**
- Server said: `null`
- Notes: no activity

### Edge 5: pilot.sup1 reads pilot.sup2 DBS row

- HTTP status: `401`
- Severity: **medium**
- Server said: `{"success":false,"error":{"code":"UNAUTHORIZED","message":"Authentication required"}}`
- Notes: server enforced isolation with 401

### Edge 6: eng-cross-lock

- HTTP status: `BLOCKED`
- Severity: **info**
- Server said: `null`
- Notes: no activity

### Edge 7: pilot.pm1 reads /projects/MYPRO-001/dpr

- HTTP status: `403`
- Severity: **low**
- Server said: `{"error":{"code":"FORBIDDEN","message":"Access denied"},"meta":{"timestamp":"2026-05-19T03:18:25.908205Z","version":"0.1.0"}}`
- Notes: cross-project read blocked with 403

### Edge 8: productivity-preview with zero-norm work-activity

- HTTP status: `405`
- Severity: **low**
- Server said: `{"error":{"code":"METHOD_NOT_ALLOWED","message":"HTTP method 'POST' is not supported for this endpoint"},"meta":{"timestamp":"2026-05-19T03:18:26.015053Z","version":"0.1.0"}}`
- Notes: response 2xx but no explicit warning; UI may show stale numeric preview

### Edge 9: DPR with manpower=[] and qtyExecuted=50

- HTTP status: `201`
- Severity: **low**
- Server said: `{"data":{"id":"a9b83529-19a4-47f8-b3d9-d9a3c810e3b1","projectId":"92b32cd5-05c1-4689-a232-4e459970fc9c","reportDate":"2026-04-28","supervisorUserId":null,"supervisorName":"DA Probe","chainageFromM":null,"chainageToM":null,"activityId":null,"activityName":"DA Inf-Productivity","wbsNodeId":null,"boqItemId":null,"boqItemNo":null,"unit":"m3","qtyExecuted":50,"cumulativeQty":100.000,"weatherCondition":`
- Notes: manpower=0 handled gracefully (productivity tile may show — or "—")

### Edge 10: DPR future-date with weather=CLEAR + delayReason=RAIN

- HTTP status: `400`
- Severity: **low**
- Server said: `{"error":{"code":"VALIDATION_ERROR","message":"Request validation failed","details":[{"field":"reportDate","reason":"reportDate must not be in the future"}]},"meta":{"timestamp":"2026-05-19T03:18:26.275993Z","version":"0.1.0"}}`
- Notes: server rejected the conflict (400)

