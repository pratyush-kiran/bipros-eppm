# DPR Tab — Server-Side Pagination, Slim Payload, Lazy Detail

**Date:** 2026-05-25
**Status:** Approved (design)
**Area:** `frontend/` (DPR tab) + `backend/bipros-project` (DPR list endpoint)

## Problem

The DPR (Daily Progress Report) tab takes a long time to load. Root causes, traced end-to-end:

1. **It loads the entire project, not a window.** `dpr/page.tsx` seeds the date filter to the
   fallback `today ± 180 days`, then re-seeds it once to the project's
   `plannedStartDate → plannedFinishDate` (`page.tsx:162-172`). So the list query fetches *every*
   DPR in the project.
2. **No pagination.** `DailyProgressReportService.list(...)` returns all matching rows; the
   frontend `dprApi.list` returns `DailyProgressReportResponse[]` with no paging.
3. **Every row is fully hydrated just to render count chips.** The collapsed `DprWorkFrontRow`
   only needs aggregates — manpower sum, equipment sum, material count, photo count, issue
   flags — but the payload carries the full `manpower[]`, `equipment[]`, `materials[]`,
   `subContractors[]`, `issues[]`, `attachments[]` arrays for every report. The backend even
   runs an expensive **cross-schema native query** to enrich sub-contractor assignment snapshots
   (`DailyProgressReportService.attachComputedCumulativeAndChildren`, the
   `resource.activity_sub_contractor_assignments` lookup) on every list call.
4. **Client grouping is recomputed every render.** `groupByDayThenActivity` in `DprDayList.tsx`
   is called inline (`DprDayList.tsx:133`), not memoized — O(n log n) on every re-render.
5. **No virtualization.** Every day → activity → work-front row mounts into the DOM at once.
   (Detail *tables* are already lazy — `DprWorkFrontRow` starts collapsed, `open=false` —
   so they are not the bottleneck.)

Net effect for the Khasab project (Jan–Mar 2026 daily data across many activities and work
fronts): a large DB read with full child hydration + the sub-contractor native query, a multi-MB
JSON payload, a heavy client grouping pass, and a large initial DOM. That is the spinner.

## Goals

- Cut DPR initial load to a small, bounded payload regardless of project size.
- Keep the "site ledger" UX: Day → Activity → Work-front, most-recent day first, full range
  reachable by scrolling.
- Keep per-day and per-activity totals correct.

## Non-goals (YAGNI)

- Numbered pages — rejected; breaks the continuous-scroll feel and can split a day across pages.
- A caching layer (`@Cacheable`) — the slim paged query is cheap; not worth invalidation cost.
- A whole-range totals endpoint — the page computes no whole-range aggregate (`rows` feeds only
  `DprDayList`, confirmed `page.tsx:381-386`), so none is needed.

## Design

### Interaction model

**Infinite scroll by day.** Load the most recent ~14 days; as the user scrolls near the bottom,
auto-fetch the next older batch of days. We paginate by **whole day**, not by raw row, so each
loaded day is complete and its activity/work-front grouping and totals are intact.

### Backend (`bipros-project`)

**1. Day-cursor pagination on `GET /v1/projects/{projectId}/dpr`.**

New query params (all additive; existing `from` / `to` / `activity` retained):

- `before` — exclusive date cursor; return only days strictly older than this. Omitted on the
  first page.
- `days` — batch size in distinct days. Default `14`.

Algorithm:

1. Select the most-recent `days + 1` **distinct** `report_date` values within `[from, to]`,
   and `< before` when the cursor is present, ordered `report_date DESC`.
2. If `days + 1` rows came back, there are older days → `hasMore = true`; drop the extra date.
3. Fetch all DPR rows whose `report_date` is in the selected dates (existing date-range repo
   method can be reused by passing the min/max of the selected dates, then filtering, or a new
   `report_date IN (:dates)` query — implementer's choice; prefer the `IN` query for precision).
4. Build the slim response (below) for those rows.
5. Return `{ items, nextCursor, hasMore }` where `nextCursor` = the oldest date in the batch
   (or `null` when `hasMore` is false). The next request passes `before = nextCursor`.

The `activity` filter, when present, narrows rows the same way it does today; pagination still
applies over the matching days.

**2. Slim list payload `DprSummaryResponse`.**

A new response DTO returned by the list endpoint (the per-row detail GET keeps returning the full
`DailyProgressReportResponse`). Fields:

- Parent fields the grouping and collapsed row use: `id`, `reportDate`, `activityId`,
  `activityName`, `boqItemNo`, `unit`, `qtyExecuted`, `chainageFromM`, `chainageToM`, `side`,
  `supervisorUserId`, `supervisorName`, `approvalStatus`, `weatherCondition`.
- **Deliberately excluded** (expanded-panel-only, sourced from the per-id detail GET):
  `cumulativeQty`, `landmark`, `remarks`, and all child arrays. `cumulativeQty` in particular
  **must not** be in the slim payload: it is a project-to-date running figure and the current
  list computes it as a running sum over the full result set
  (`attachComputedCumulativeAndChildren`). Under day-pagination the loaded subset omits earlier
  days, so that running sum would be wrong. The detail GET computes it correctly via
  `computeCumulative` → `sumQtyExecutedThroughDate(projectId, activityName, reportDate)` (a SQL
  `SUM` through date, independent of what is loaded), so the expanded panel stays accurate.
- Precomputed aggregates (replace the client-side `.reduce()` over child arrays):
  `manpowerNos` (Σ nos), `equipmentNos` (Σ nos), `materialCount`, `photoCount`, `issueCount`
  (live issues, excluding `CANCELLED`), `openIssueCount` (not `RESOLVED`/`CLOSED`),
  `hasCriticalOpen` (any `CRITICAL` and open).

Aggregates are computed with cheap `GROUP BY dpr_id` count/sum batch queries keyed on
`dpr_id IN (:ids)` — **no child-row hydration**. The sub-contractor cross-schema native
enrichment is **dropped from the list path** entirely (it is only needed in expanded detail).
The list path also **stops computing the cumulative running sum** (no longer in the slim payload;
see exclusions below) — the existing `attachComputedCumulativeAndChildren` is replaced by the
slim-aggregate builder for the list case.

Issue aggregates respect the same status rules the frontend uses today
(`DprWorkFrontRow.tsx:78-85`): exclude `CANCELLED` from `issueCount`; `openIssueCount` excludes
`RESOLVED`/`CLOSED`; `hasCriticalOpen` requires `severity = CRITICAL` and open.

**3. `GET /dpr/{id}` unchanged.** Returns the full `DailyProgressReportResponse` with all
children. This is the lazy-detail source on expand.

**Caller check:** before changing the list response shape, grep for other consumers of
`GET /v1/projects/{projectId}/dpr` (dashboards, exports, AI tools). If any rely on full children
from the list, either point them at the per-id GET or give them a dedicated query. Resolve during
planning.

### Frontend

**4. `useInfiniteQuery`** in `dpr/page.tsx`, keyed `["dpr", projectId, from, to]`:

- `queryFn: ({ pageParam }) => dprApi.list(projectId, { from, to, before: pageParam, days: 14 })`
- `initialPageParam: undefined`
- `getNextPageParam: (lastPage) => lastPage.hasMore ? lastPage.nextCursor : undefined`
- `rows = data.pages.flatMap(p => p.items)`

`dprApi.list` returns the new paged envelope (`{ items, nextCursor, hasMore }`) typed to
`DprSummaryResponse`. Save/delete invalidation keeps the same `["dpr", projectId, from, to]` key
(refetches loaded pages).

**5. Slim types + aggregate reads.** Add `DprSummaryResponse` to `lib/types/dpr`. `DprDayList` /
`DprActivityGroup` / `DprWorkFrontRow` collapsed view consume `DprSummaryResponse`.
`DprWorkFrontRow` reads `row.manpowerNos`, `row.equipmentNos`, `row.materialCount`,
`row.photoCount`, `row.issueCount`, `row.openIssueCount`, `row.hasCriticalOpen` instead of
reducing/filtering child arrays.

**6. Lazy detail on expand.** When a work-front row opens, fetch the full report via
`useQuery(["dpr-detail", projectId, id], () => dprApi.get(projectId, id), { enabled: open })`.
Render the manpower/equipment/material/sub-contractor/issue detail tables from that result; show a
small skeleton while it loads. Edit still opens the drawer (the form fetches/uses the full record
as it does today).

**7. Memoize grouping.** Wrap `groupByDayThenActivity(rows)` in `useMemo` keyed on the accumulated
`rows` (move the call out of inline render, or memoize inside `DprDayList`).

**8. Virtualize the day list** with `@tanstack/react-virtual` (already a dependency). Virtualize at
the **day-section** granularity with dynamic measurement (`measureElement`) since day heights vary.
Preserve the sticky day headers. Trigger `fetchNextPage()` when the last virtual item nears the
viewport (and `hasNextPage && !isFetchingNextPage`). Reference prior art:
`docs/superpowers/specs/2026-05-05-virtualized-tables-design.md`.

**9. `React.memo`** on `DprActivityGroup` and `DprWorkFrontRow` so an unrelated page re-render does
not re-render every row.

## Data flow

```
page.tsx (useInfiniteQuery)
  → dprApi.list(projectId, {from,to,before,days})
      → GET /v1/projects/{id}/dpr?from&to&before&days
          → DailyProgressReportService: distinct-days query → rows for days → GROUP BY aggregates
          → { items: DprSummaryResponse[], nextCursor, hasMore }
  → rows = pages.flatMap(items)
  → useMemo: groupByDayThenActivity(rows)  → DayGroup[]
  → virtualized DprDayList (day sections)
      → DprActivityGroup → DprWorkFrontRow (collapsed, reads aggregates)
          → on expand: useQuery dpr-detail → GET /dpr/{id} (full children) → DetailTable[]
  → scroll near bottom → fetchNextPage(before = nextCursor)
```

## Error handling

- List page fetch error: existing `pageError` / empty-state handling; infinite-query error surfaces
  a retry on the bottom sentinel.
- Detail fetch error on expand: inline error inside the expanded panel with a retry; does not break
  the row.
- Empty range: existing `DprDayList` empty state (`DprDayList.tsx:135-145`).

## Testing / verification

On the Khasab project with the full planned window:

1. **Payload + load time** — record DPR list payload size and time-to-interactive before vs after.
   Expect a large drop (no child arrays, no sub-contractor native query, only ~14 days).
2. **Correctness of chips** — manpower/equipment/material/photo/issue chips on collapsed rows match
   the pre-change values for a sample of rows.
3. **Lazy detail** — expanding a row shows the correct manpower/equipment/material/sub-contractor/
   issue detail.
4. **Infinite scroll** — scrolling past the 14-day boundary auto-loads older days; `hasMore` goes
   false at the oldest day; no duplicate days.
5. **Per-day/per-activity totals** — day activity counts and activity total-qty unchanged.
6. **Save/delete** — creating/editing/deleting a DPR refreshes the list.
7. **Backend tests** — service test for the distinct-days + cursor logic (first page, mid cursor,
   last page `hasMore=false`) and aggregate correctness incl. issue status rules.

## Open items to resolve in planning

- Confirm no other backend caller depends on full children from the list endpoint (caller check
  above).
- Decide the exact repo query for "rows for selected dates" (`IN (:dates)` vs min/max range).
- Confirm `@tanstack/react-virtual` dynamic-height + sticky-header pattern against the existing
  virtualized-tables implementation.
