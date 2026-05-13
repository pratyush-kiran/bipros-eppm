# DPR Issues — API Reference

Field-issue log entries attached to a Daily Progress Report. Each DPR can carry 0..N issues.
Every endpoint is project-scoped; all responses are wrapped in the standard
`ApiResponse<T>` envelope:

```json
{
  "success": true,
  "data": { ... },
  "error": null,
  "timestamp": "2026-05-12T11:30:00Z"
}
```

All times are ISO-8601 UTC. All ids are UUIDs.

## Authentication & authorization

| Action | Required role(s) |
|---|---|
| `GET /dpr-issues` / `GET /dpr-issues/{id}` | any project-scoped reader (ADMIN, PROJECT_MANAGER, PROGRAMME_MANAGER, SITE_SUPERVISOR, TEAM_MEMBER, VIEWER) |
| `PATCH /dpr-issues/{id}` | ADMIN, PROJECT_MANAGER, SITE_SUPERVISOR |
| `DELETE /dpr-issues/{id}` | ADMIN, PROJECT_MANAGER |
| Create / bulk edit via DPR `POST` / `PUT /dpr` (issues piggyback) | ADMIN, PROJECT_MANAGER, SITE_SUPERVISOR |

JWT goes in the `Authorization: Bearer <token>` header — same as every other API in the project.

---

## Enums

### `IssueCategory` (reason)
`SAFETY`, `QUALITY`, `MATERIAL_SHORTAGE`, `EQUIPMENT_BREAKDOWN`, `MANPOWER_SHORTAGE`,
`WEATHER`, `DESIGN_CHANGE`, `LAND_ACCESS`, `UTILITY_CLASH`, `PERMIT_DELAY`, `SUBCONTRACTOR`,
`ENVIRONMENTAL`, `OTHER`

Deserialiser is tolerant — accepts aliases like `RFI` → `DESIGN_CHANGE`, `RAIN` → `WEATHER`,
mixed case, hyphens, spaces.

### `IssueSeverity`
`LOW`, `MEDIUM`, `HIGH`, `CRITICAL` (default: `MEDIUM`)

### `IssueStatus`
`OPEN`, `IN_PROGRESS`, `BLOCKED`, `RESOLVED`, `CLOSED`, `CANCELLED` (default: `OPEN`)

- `RESOLVED` and `CLOSED` are terminal — entering them auto-stamps `resolvedAt = now()`.
- Leaving them (e.g. `RESOLVED → IN_PROGRESS`) clears `resolvedAt`.
- `CANCELLED` is excluded from default list / rollup views; pass an explicit filter or
  `include_cancelled=true` (AI tool) to see them.

---

## Data shape — `DprIssueRow`

```json
{
  "id": "uuid",
  "title": "Aggregate truck broke down",
  "description": "Replacement vehicle ETA 14:00, work resumed at 14:45",
  "category": "MATERIAL_SHORTAGE",
  "severity": "HIGH",
  "status": "IN_PROGRESS",
  "supervisorResourceId": "uuid",
  "supervisorName": "Mohd Ismaila",
  "assignedToResourceId": "uuid",
  "assignedToName": "Mohd Ismaila",
  "openedAt": "2026-05-12T08:42:11.123Z",
  "resolvedAt": null,
  "resolutionNotes": null
}
```

**Field semantics**

| Field | Notes |
|---|---|
| `id` | `null` on insert. Non-null on update (used by merge-by-id). |
| `title` | Required, max 150 chars. |
| `description` | Free text, max 2000 chars. |
| `category` / `severity` / `status` | Required enum values. |
| `supervisorResourceId` | Who logged it. Defaults to parent DPR's supervisor on insert; editable via PATCH. |
| `assignedToResourceId` | "Who is looking into it." Defaults to the supervisor on insert. The AI's `by_supervisor` rollup keys off `supervisorResourceId`, not assignee. |
| `openedAt` | Set by the server on insert. Immutable. |
| `resolvedAt` | Server-managed only — do not send from the client. |

**Immutable after creation** (snapshots from the parent DPR): `dprId`, `projectId`, `activityId`,
`activityName`, `reportDate`, `chainageFromM`, `chainageToM`, `openedAt`. Editing the parent
DPR's activity does NOT re-sync these — issues stay anchored to the context they were logged in.

---

## 1. List issues for a project

```
GET /v1/projects/{projectId}/dpr-issues
```

**Query parameters** (all optional; AND-combined)

| Name | Type | Notes |
|---|---|---|
| `status` | `IssueStatus` | Filter exact match. |
| `severity` | `IssueSeverity` | Filter exact match. |
| `category` | `IssueCategory` | Filter exact match. |
| `supervisorResourceId` | UUID | Filter by who logged it. |
| `activityId` | UUID | Filter by the snapshotted activity. |
| `dateFrom` | `YYYY-MM-DD` | Inclusive lower bound on `reportDate`. |
| `dateTo` | `YYYY-MM-DD` | Inclusive upper bound on `reportDate`. |

> Unlike the AI tool, this endpoint does NOT exclude `CANCELLED` by default — pass
> `status=CANCELLED` to filter, or omit `status` to see everything.

**Example**

```bash
curl -s -H "Authorization: Bearer $JWT" \
  "$API/v1/projects/$P/dpr-issues?status=OPEN&severity=HIGH"
```

```json
{
  "success": true,
  "data": [
    {
      "id": "8ab9...",
      "title": "Material shortage",
      "description": "Aggregate truck broke down",
      "category": "MATERIAL_SHORTAGE",
      "severity": "HIGH",
      "status": "OPEN",
      "supervisorResourceId": "...",
      "supervisorName": "Mohd Ismaila",
      "assignedToResourceId": "...",
      "assignedToName": "Mohd Ismaila",
      "openedAt": "2026-05-12T08:42:11.123Z",
      "resolvedAt": null,
      "resolutionNotes": null
    }
  ]
}
```

Returned ordering: newest `openedAt` first (descending).

---

## 2. Get a single issue

```
GET /v1/projects/{projectId}/dpr-issues/{issueId}
```

Returns one `DprIssueRow`. Cross-project access yields HTTP `404` (the row is filtered through
`findByIdAndProjectId`, so it appears not to exist outside its project).

```bash
curl -s -H "Authorization: Bearer $JWT" \
  "$API/v1/projects/$P/dpr-issues/$ID"
```

---

## 3. Update an issue (post-save mutations)

```
PATCH /v1/projects/{projectId}/dpr-issues/{issueId}
Content-Type: application/json
```

Use this for status flips, reassignment, resolution notes — any change that should NOT
require re-saving the whole DPR. **All fields are optional**; only non-null fields are applied.

**Editable fields**

```json
{
  "title": "string?",
  "description": "string?",
  "category": "IssueCategory?",
  "severity": "IssueSeverity?",
  "status": "IssueStatus?",
  "supervisorResourceId": "uuid?",
  "supervisorName": "string?",
  "assignedToResourceId": "uuid?",
  "assignedToName": "string?",
  "resolutionNotes": "string?"
}
```

The service auto-manages `resolvedAt` on status transitions; clients must not set it.

**Example — resolve an issue with notes:**

```bash
curl -s -X PATCH -H "Authorization: Bearer $JWT" -H 'Content-Type: application/json' \
  -d '{
        "status": "RESOLVED",
        "resolutionNotes": "Replacement truck arrived 14:00; lost 3 hours."
      }' \
  "$API/v1/projects/$P/dpr-issues/$ID"
```

**Response** — the full updated `DprIssueRow`. The server populates `resolvedAt` automatically.

**Failure modes**

- `404` — issue not in this project (or doesn't exist).
- `409` — optimistic-lock conflict (`OptimisticLockException`): refetch and retry.
- `400` — validation (e.g. blank title).

---

## 4. Delete an issue

```
DELETE /v1/projects/{projectId}/dpr-issues/{issueId}
```

Hard delete. Restricted to `ADMIN` / `PROJECT_MANAGER`. Most operational "remove" flows
should use `PATCH` with `status=CANCELLED` instead — that keeps the audit trail.

```bash
curl -s -X DELETE -H "Authorization: Bearer $JWT" \
  "$API/v1/projects/$P/dpr-issues/$ID"
```

---

## 5. Issues via the parent DPR endpoints (create + bulk edit)

There is **no `POST /dpr-issues`** — issues are always created in the context of a DPR so no
orphan rows can exist. Both the DPR create (`POST /v1/projects/{p}/dpr`) and update
(`PUT /v1/projects/{p}/dpr/{id}`) payloads carry an optional `issues: DprIssueRow[]` list.

**Merge-by-id semantics on update** — important and divergent from manpower / equipment /
material (which are full-replace):

- Rows with `id` set are **updated in place** (preserves `openedAt`, version, audit fields).
- Rows with no `id` are **inserted** with snapshots stamped from the parent DPR.
- Rows present in DB but **absent** from this payload are **deleted**.
- Sending `null` or `[]` clears every issue for the DPR. There is no "leave alone" sentinel —
  clients must round-trip the latest server payload.
- An `id` that belongs to a different DPR is rejected with `409 BusinessRuleException`
  (rule code `DPR_ISSUE_NOT_FOUND`).

**Example — DPR update carrying two issues:**

```bash
curl -s -X PUT -H "Authorization: Bearer $JWT" -H 'Content-Type: application/json' \
  -d '{
        "reportDate": "2026-05-12",
        "supervisorName": "Mohd Ismaila",
        "activityId": "...",
        "activityName": "Bench Cutting",
        "unit": "Cum",
        "qtyExecuted": 80.0,
        "manpower": [...], "equipment": [...], "materials": [...],
        "issues": [
          {
            "id": "8ab9...",
            "title": "Material shortage",
            "category": "MATERIAL_SHORTAGE",
            "severity": "HIGH",
            "status": "IN_PROGRESS"
          },
          {
            "title": "Weather delay",
            "description": "Heavy rain 13:00-15:30",
            "category": "WEATHER",
            "severity": "MEDIUM",
            "status": "OPEN"
          }
        ]
      }' \
  "$API/v1/projects/$P/dpr/$DPR_ID"
```

The response is the full `DailyProgressReportResponse`, which now includes the same
`issues: DprIssueRow[]` array — newly inserted rows come back with their server-assigned ids.

---

## 6. AI chat — natural-language issue queries

The Global Chat AI knows about issues via two dedicated tools. **Prefer asking the AI** for
analytical questions ("which supervisor logs the most issues?") rather than implementing
the rollups in client code — the orchestrator handles aggregation, deduping, and the
verification pass.

```
POST /v1/ai/chat
```

```json
{
  "projectId": "uuid",
  "module": "dpr",
  "message": "How many open issues are on activity ACT-001 and what is the most common reason?"
}
```

The orchestrator's system prompt routes issue questions to:

- **`list_issues`** — JPA-backed, immediately consistent, returns rows + rollups by activity /
  supervisor / category / severity / status. Used for: "how many issues on X", "which activity
  has the most", "which supervisor logged the most", "what is the reason for issues on X".
- **`get_issue_details`** — drill-down by `issue_id`, returns the issue plus the parent DPR
  snapshot.

`CANCELLED` issues are excluded by default in `list_issues`; pass `include_cancelled=true` in
the AI's tool input (or filter by `status=CANCELLED`) to see them.

For cross-project trends / time-series shapes, the AI can fall back to
`query_clickhouse` against `bipros_analytics.fact_dpr_issues_daily` (the SqlGuard allowlist
covers it). Single-project counting always stays on JPA.

---

## 7. Direct ClickHouse access (cross-project analytics)

If you're building a dashboard outside the AI chat path, the warehouse fact table is:

```
bipros_analytics.fact_dpr_issues_daily
```

| Column | Type | Notes |
|---|---|---|
| `project_id` | UUID | Required predicate for SqlGuard. |
| `dpr_id` | UUID | |
| `issue_id` | UUID | Stable across upserts. |
| `activity_id` / `activity_name` | UUID / String | Snapshotted from parent DPR. |
| `supervisor_resource_id` / `supervisor_name` | UUID / String | Who logged it. |
| `assigned_to_resource_id` / `assigned_to_name` | UUID / String | Current assignee. |
| `report_date` | Date | |
| `opened_at` / `resolved_at` | DateTime64(3) / Nullable | |
| `resolution_age_hours` | Float32 | Precomputed; null while open. |
| `category` / `severity` / `status` | LowCardinality(String) | |
| `title` / `description` | String | |
| `chainage_from_m` / `chainage_to_m` | Nullable(Float64) | |
| `event_ts` / `_version` | DateTime64 / UInt64 | ReplacingMergeTree dedup keys. |

**Sort key**: `(project_id, dpr_id, issue_id)` — `FINAL` queries dedupe by `_version`.
**Partition**: `toYYYYMM(report_date)`. **TTL**: `report_date + INTERVAL 7 YEAR`.

Example aggregate — open issue count per supervisor for a project:

```sql
SELECT supervisor_name, count() AS open_issues
FROM bipros_analytics.fact_dpr_issues_daily FINAL
WHERE project_id = '<uuid>'
  AND status IN ('OPEN','IN_PROGRESS','BLOCKED')
GROUP BY supervisor_name
ORDER BY open_issues DESC
LIMIT 50
```

The SqlGuard requires a `project_id` predicate scoped to the caller's projects; cross-project
queries against a project the JWT doesn't own are rejected with `SQL_PROJECT_OUT_OF_SCOPE`.

---

## 8. Quick reference — endpoint map

| Method | Path | Purpose |
|---|---|---|
| `GET` | `/v1/projects/{p}/dpr-issues` | List with filters. |
| `GET` | `/v1/projects/{p}/dpr-issues/{id}` | Single issue. |
| `PATCH` | `/v1/projects/{p}/dpr-issues/{id}` | Edit fields, flip status, reassign. |
| `DELETE` | `/v1/projects/{p}/dpr-issues/{id}` | Hard delete (admin / PM). |
| `POST` | `/v1/projects/{p}/dpr` | Create DPR with `issues: []`. |
| `PUT` | `/v1/projects/{p}/dpr/{dprId}` | Update DPR; merges issues by id. |
| `GET` | `/v1/projects/{p}/dpr/{dprId}` | Returns the DPR with `issues: []`. |
| `GET` | `/v1/projects/{p}/dpr` | Returns DPR list with `issues: []` per row. |
| `POST` | `/v1/ai/chat` | NL questions → `list_issues` / `get_issue_details`. |

Swagger lists all of these at `http://localhost:8080/swagger-ui.html` when the backend is up.
