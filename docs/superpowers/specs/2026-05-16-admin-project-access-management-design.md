# Admin Project-Access Management — Design

**Status:** Draft
**Date:** 2026-05-16
**Owner:** hemendra
**Scope:** MVP single slice (Approach A). Phase 2 items called out but not in scope.

## Problem

There is no central place for an Admin to see or manage project memberships across the system. The existing per-project Members page (`/projects/{projectId}/members`, backed by `ProjectMemberController`) requires the admin to drill into each project one at a time. With a growing project list this is impractical, and there is no way to answer "what projects can this user reach?" without writing a SQL query.

`/admin/user-access` already exists, but it manages ICPMS module-level access (M1–M9 with VIEW/EDIT/CERTIFY/APPROVE/FULL) — not the `project_members` table that drives `ProjectAccessEvaluator.hasProjectPermission`.

## Goals

- Single admin page that answers three questions: "who is on this project?", "what projects is this user on?", and "show me the whole user×project grid for audit".
- Pre-revoke preview that shows the admin what the target user will lose access to before confirming, addressing the blast-radius concern that motivated this work (stranded DPR drafts, orphaned supervisor assignments).
- No DB schema change; reuse the existing `project_members` table and `ProjectMemberController` mutations.

## Non-goals (Phase 2 or later)

- Soft-revoke / grace-period column on `project_members`.
- Automatic reassignment of stranded DPR drafts and `activity_supervisors` rows.
- Real-time push to the target user's frontend (SSE/websocket) to drop the revoked project from their React Query cache instantly.
- Bulk operations (assign N users to N projects in one action).
- Export to CSV.

## Audit findings (informs design)

Before settling on Approach A, an audit of downstream consumers confirmed:

- **Backend has no caches of `project_members`.** Every request to `ProjectAccessEvaluator.hasProjectPermission` reads the table live. Revoke is effective immediately for any new request.
- **JWT carries only global permissions** (`perms` claim in `JwtTokenProvider`), not project IDs. No token re-issue is needed when memberships change.
- **AI tools** scope via `ProjectScopedTool.execute()` which calls `ctx.scopedProjectIds()` per invocation — no caching; revoked users hit `AccessDeniedException` on the next tool call.
- **ClickHouse** rows are immutable; `QueryClickHouseTool` filters via `SqlGuard` upstream of the query. Revoked users lose query access via the AI tool guard, not via a ClickHouse-level filter.
- **DPR submission** (`DailyProgressReportController`) uses `@PreAuthorize("@projectAccess.hasProjectPermission(#projectId, 'DPR.CREATE/UPDATE/DELETE/APPROVE')")` on every mutation. Drafts (`approval_status = 'DRAFT'`) remain in the DB after revoke but become uneditable by the original author.
- **`activity_supervisors`** rows are **not** cascade-cleared when `project_members` is deleted. Activities can end up with a supervisor who is no longer a project member.
- **Frontend project list** (`GET /v1/projects` + React Query key `["projects"]`) caches for ~5 min by default; the revoked project lingers in the target user's sidebar briefly. Backend filtering is correct on the next refetch.

## Architecture

### Backend (`bipros-security`)

**No DB schema changes.** All work uses the existing `project_members` table.

New controller: `AdminProjectAccessController` under `bipros-security/src/main/java/com/bipros/security/api/`. All endpoints gated by `@PreAuthorize("hasAuthority('ROLE_ADMIN')")`.

| Verb | Path | Returns |
|---|---|---|
| GET | `/v1/admin/project-access/by-user?page=&size=&search=` | `Page<UserMembershipSummary>` — `userId, username, displayName, role, projectCount, projects:[{projectId, projectName, projectRole, grantedAt}]` |
| GET | `/v1/admin/project-access/by-project?page=&size=&search=` | `Page<ProjectMembershipSummary>` — `projectId, projectName, status, memberCount, members:[{userId, username, displayName, projectRole, grantedAt}]` |
| GET | `/v1/admin/project-access/matrix?userPage=&userSize=&projectPage=&projectSize=` | `MatrixResponse` — `users:[{id,name}], projects:[{id,name}], cells:[{userId, projectId, roles:[ProjectMemberRole]}]`. Default cap: 200 users × 100 projects. |
| GET | `/v1/admin/project-access/revoke-impact?userId=&projectId=` | `RevokeImpactDto` — `draftDprCount, supervisorActivityCount, lastDprAt` |

**Mutations are not new endpoints.** Add/remove/change-role go through the existing `POST` and `DELETE` on `/v1/projects/{projectId}/members` (already gated by `PROJECT_MEMBER.MANAGE`, which ADMIN holds). This keeps the build-gate `RbacCoverageIntegrationTest` happy without registering new mutating endpoints.

**Repository support.** `ProjectMemberRepository` gets two new query methods (paged, with optional username/project-name LIKE filter):

- `findUserSummaries(Pageable, String search)` — group by user.
- `findProjectSummaries(Pageable, String search)` — group by project.

`RevokeImpactDto` is built from these queries (all on real columns — verified against `DailyProgressReport` and `ActivitySupervisor` entities):

- `dailyProgressReportRepository.countDrafts(userId, projectId)` — `supervisor_user_id = userId AND project_id = projectId AND approval_status = 'DRAFT'`. These are the DPRs that become stranded (uneditable) when access is revoked.
- `activitySupervisorRepository.countByUserAndProject(userId, projectId)` — `activity_supervisors.user_id = userId` joined to `activities.project_id = projectId`. These are the assignments that become orphaned (the activity loses this supervisor; the row is not auto-cleared).
- `lastDprAt` = `MAX(updated_at)` from the user's DPRs in the project (any status). Tells admin whether the user is currently active or stale.

**Note on what's not counted:** `DailyProgressReport` has no `approver_user_id` field. Approval is permission-gated (`DPR.APPROVE`), not user-assigned. So we cannot show "N DPRs awaiting this user's approval" — there is no such relationship to count.

The DPR and activity repositories live in `bipros-project` and `bipros-activity`; `bipros-security` already depends on `bipros-common` and reads cross-module via Spring Data, so adding read-only repository calls is in-pattern.

### Frontend (`frontend/`)

New route: `frontend/src/app/(app)/admin/project-access/page.tsx`.

Three tabs, URL-synced via `?tab=by-user|by-project|matrix` for deep-linking and shareable URLs.

```
/admin/project-access
├── ?tab=by-user (default)
│   ├── Left: paged user list (VirtualDataTable)
│   └── Right: selected user's memberships table + Add/Edit/Revoke
├── ?tab=by-project
│   ├── Left: paged project list (VirtualDataTable)
│   └── Right: selected project's members table + Add/Edit/Revoke
└── ?tab=matrix (read-only audit grid)
```

**Reused components**, extracted from `frontend/src/app/(app)/projects/[projectId]/members/page.tsx`:

- `frontend/src/components/admin/project-access/AddMembershipDialog.tsx` — was `AddMemberDialog` inline.
- `frontend/src/components/admin/project-access/EditRoleDialog.tsx` — was `EditRoleDialog` inline.
- `frontend/src/components/admin/project-access/RevokeImpactDialog.tsx` — **new**, replaces the existing inline confirm dialog. Calls `getRevokeImpact()` on open and renders the three fields (`draftDprCount`, `supervisorActivityCount`, `lastDprAt`) plus a "Revoke anyway" button.

The existing `projects/[projectId]/members/page.tsx` is updated to import from these shared components so both screens stay in sync.

**API client:** new `frontend/src/lib/api/adminProjectAccessApi.ts`:

- `listByUser(page, size, search)`
- `listByProject(page, size, search)`
- `getMatrix(userPage, userSize, projectPage, projectSize)`
- `getRevokeImpact(userId, projectId)`

Mutations reuse `projectMemberApi.assign`, `revoke`, `updateRole`.

**Sidebar:** add a "Project access" item in the admin section, immediately under "User access".

## Data flow: revoke with preview

```
Admin clicks Revoke on a row
        │
        ▼
Frontend opens RevokeImpactDialog
        │
        ▼
GET /v1/admin/project-access/revoke-impact?userId=…&projectId=…
        │
        ▼
Dialog renders three counts (drafts, supervisor activities, last activity) + warning text
        │
   ┌────┴────┐
   ▼         ▼
Cancel   Revoke anyway
              │
              ▼
DELETE /v1/projects/{projectId}/members/{memberId}
              │
              ▼
Toast: "Removed. N drafts and M supervisor assignments are now stranded.
        See the project's DPR/Activities pages to reassign."
              │
              ▼
React Query invalidates ["admin-project-access", ...] keys → table refetches
```

## Error handling and edge cases

- **403 on non-admin:** standard `Forbidden` empty state via existing route layout guard.
- **409 on duplicate assign:** unique `(user_id, project_id, project_role)` constraint already enforces this; backend translates to HTTP 409, frontend surfaces via `getErrorMessage()` toast. No optimistic UI in MVP.
- **Race on revoke:** if another admin already revoked the row, the DELETE returns 404; frontend toasts and refetches.
- **Self-revoke (admin removes a logged-in non-admin user):** target user's next backend call returns 403 immediately; their frontend project sidebar lags by up to 5 min until React Query refetches `["projects"]`. This lag is explicitly accepted in MVP — Phase 2 adds an SSE signal.
- **Admin revokes themselves from a project:** allowed; ROLE_ADMIN bypasses `ProjectAccessEvaluator` regardless, so no actual loss of access. No special-casing.
- **Missing userId or projectId on impact endpoint:** 400. Unknown IDs: 404. No membership exists: 200 with all zeros (page may pre-flight before showing the dialog).

## Authorization summary

| Action | Backend gate |
|---|---|
| List by-user / by-project / matrix / impact | `hasAuthority('ROLE_ADMIN')` |
| Assign (existing endpoint) | `@projectAccess.hasProjectPermission(#projectId, 'PROJECT_MEMBER.MANAGE')` — ADMIN bypasses |
| Revoke (existing endpoint) | same |

Frontend `hasPermission()` checks hide affordances defensively but the server is the source of truth.

## Testing

**Backend (`bipros-security`)**

- `AdminProjectAccessControllerTest` (`@WebMvcTest`): for each of the four endpoints — ADMIN allowed, non-ADMIN 403, unauthenticated 401. Pagination/search smoke tests on `by-user` and `by-project`.
- `RevokeImpactQueryTest` (`@DataJpaTest`): seed a project with DRAFT DPRs (matching `supervisor_user_id`) and `activity_supervisors` rows; assert all three fields (`draftDprCount`, `supervisorActivityCount`, `lastDprAt`) match. Include a zero-state test that returns zeros and `null` for `lastDprAt`.
- `RbacCoverageIntegrationTest` already enforces `@PreAuthorize` coverage at build time — no new test needed; it must keep passing.

**Frontend (Playwright e2e under `frontend/tests/e2e/`)**

- `admin-project-access.spec.ts`:
  - Log in as admin, open `/admin/project-access`.
  - Tab switch (by-user → by-project → matrix), search filters work.
  - Assign a TEAM_MEMBER from "By User"; the row appears in both "By User" and "By Project" tabs.
  - Revoke via the impact-preview dialog; assert the three counts render and the toast warns about stranded drafts.
- One non-admin smoke test (PROJECT_MANAGER): expect Forbidden on `/admin/project-access`.

**Manual verification before merge**

- Run `./scripts/seed-icpms-data.sh`.
- Log in as admin, click through all three tabs.
- Revoke a supervisor with active drafts → confirm the toast warns about stranded drafts.
- Re-add the supervisor → confirm drafts are editable again.

No component-level unit tests for the React pieces — the project doesn't have a component-test convention; behaviour is covered by the e2e flow.

## Out of scope (Phase 2 candidates)

These were considered and explicitly deferred to keep MVP shippable in a single PR:

1. **Soft-revoke** — `status` column on `project_members` (ACTIVE/READ_ONLY/REVOKED) with grace-period auto-revoke. Lets revoked supervisors finish submitting in-flight DPRs before losing access.
2. **Reassignment workflow** — when revoking a user, offer to reassign their drafts and supervisor rows to another project member in the same dialog.
3. **SSE / websocket signal** — push "your access changed" to the target user so their frontend invalidates `["projects"]` and any open project page immediately, not after the ~5 min React Query stale window.
4. **Bulk ops** — multi-select users and projects, bulk assign/revoke.
5. **Audit log** — separate table tracking every membership change with actor/before/after/timestamp. The `granted_by` + `created_at` on the row are the MVP audit record; full history requires a separate table.
6. **CSV export** of the matrix for offline audits.

## File-level change summary

| File | Change |
|---|---|
| `backend/bipros-security/src/main/java/com/bipros/security/api/AdminProjectAccessController.java` | NEW |
| `backend/bipros-security/src/main/java/com/bipros/security/application/dto/UserMembershipSummary.java` | NEW |
| `backend/bipros-security/src/main/java/com/bipros/security/application/dto/ProjectMembershipSummary.java` | NEW |
| `backend/bipros-security/src/main/java/com/bipros/security/application/dto/MatrixResponse.java` | NEW |
| `backend/bipros-security/src/main/java/com/bipros/security/application/dto/RevokeImpactDto.java` | NEW |
| `backend/bipros-security/src/main/java/com/bipros/security/application/service/AdminProjectAccessService.java` | NEW |
| `backend/bipros-security/src/main/java/com/bipros/security/domain/repository/ProjectMemberRepository.java` | EDIT — add summary queries |
| `backend/bipros-project/src/main/java/com/bipros/project/domain/repository/DailyProgressReportRepository.java` | EDIT — add `countDraftsByUserAndProject`, `findLastDprAtByUserAndProject` |
| `backend/bipros-activity/src/main/java/com/bipros/activity/domain/repository/ActivitySupervisorRepository.java` | EDIT — add `countByUserAndProject` |
| `backend/bipros-security/src/test/java/com/bipros/security/api/AdminProjectAccessControllerTest.java` | NEW |
| `backend/bipros-security/src/test/java/com/bipros/security/application/service/RevokeImpactQueryTest.java` | NEW |
| `frontend/src/app/(app)/admin/project-access/page.tsx` | NEW |
| `frontend/src/components/admin/project-access/AddMembershipDialog.tsx` | NEW (extracted) |
| `frontend/src/components/admin/project-access/EditRoleDialog.tsx` | NEW (extracted) |
| `frontend/src/components/admin/project-access/RevokeImpactDialog.tsx` | NEW |
| `frontend/src/app/(app)/projects/[projectId]/members/page.tsx` | EDIT — switch to shared dialog components |
| `frontend/src/lib/api/adminProjectAccessApi.ts` | NEW |
| `frontend/src/components/common/Sidebar.tsx` | EDIT — add "Project access" admin nav item under "User access" |
| `frontend/tests/e2e/admin-project-access.spec.ts` | NEW |
