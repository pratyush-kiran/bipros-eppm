# RBAC Implementation Guide

**Status:** Live on `feat/ai-scope-and-rate-hardening` (commits `c3aedba` … `d9697c1`,
plus the Supervisor hardening sweep — Phase A/B/C — described in §12).
**Audience:** Part I — developers working on or extending the access-control layer.
Part II — administrators and end users learning how access is granted.
**Companion suite:** `frontend/e2e/tests/33-rbac-comprehensive.spec.ts` (37 active
Playwright scenarios + 2 documented skips, all green).

---

## Part I — Developer Guide

### 1. The big picture

BIPROS EPPM access is granted through three layered concepts. Every authorization
decision the backend makes is the union of these three.

```
┌──────────────────────────────────────────────────────────────────┐
│ 1. Role         ─ coarse-grained identity (PROJECT_MANAGER,      │
│                   VIEWER, ADMIN, …). 22 canonical roles, one     │
│                   per organisational archetype. Issued at JWT    │
│                   creation, surfaced as authorities to Spring    │
│                   Security.                                      │
│                                                                  │
│ 2. Profile      ─ a named permission bundle the admin can attach │
│                   to a user. Matches one of the 22 canonical role│
│                   shapes by default ("system-default profiles"), │
│                   but can be cloned and customised. Carries the  │
│                   explicit list of permission codes the user can │
│                   exercise globally.                             │
│                                                                  │
│ 3. ProjectMember─ row in `project_members` that binds a user to  │
│                   a project with a member-role (PROJECT_MANAGER, │
│                   TEAM_MEMBER, CONTRACTOR, …). The evaluator     │
│                   checks this for any `@projectAccess.has…`-     │
│                   guarded endpoint.                              │
└──────────────────────────────────────────────────────────────────┘
```

Every controller method ends up in one of three guard styles:

| Annotation pattern                                                | When                                                |
| ----------------------------------------------------------------- | --------------------------------------------------- |
| `@PreAuthorize("isAuthenticated()")`                              | Any logged-in user (rare, used at class level)      |
| `@PreAuthorize("hasPermission(null, 'XYZ.ACTION')")`              | Global permission — checked against the user's permset |
| `@PreAuthorize("@projectAccess.hasProjectPermission(#projectId, 'XYZ.ACTION')")` | Project-scoped — also requires `ProjectMember` for the project |

There is **no** `hasRole(...)` left in any `@PreAuthorize` annotation after Phase 3
(`d96b663`). The sweep migrated 151 controllers to permission-based gates.

### 2. The 22 canonical roles

Defined in `backend/bipros-security/src/main/java/com/bipros/security/domain/model/RolePermissionMatrix.java`.
Each name maps to an immutable `Set<String>` of permission codes.

```
ADMIN, EXECUTIVE, PMO, FINANCE,
PROJECT_MANAGER, SCHEDULER, PLANNING_ENGINEER,
RESOURCE_MANAGER, STORE_MANAGER, PROCUREMENT_OFFICER,
SITE_MANAGER, SITE_ENGINEER, PROJECT_ENGINEER,
SUPERVISOR, FOREMAN,
QA_QC_ENGINEER, SAFETY_OFFICER, BIM_DATA_COORDINATOR,
TEAM_MEMBER, CONTRACTOR, CLIENT,
VIEWER
```

A user's effective permission set is:

```
RolePermissionMatrix.permissionsForAll(user.roles)   // union over all roles
  ∪ user.profile?.permissions                        // explicit profile bundle
```

Legacy authority aliases (`QC_MANAGER` ↔ `QA_QC_ENGINEER`, `HSE_OFFICER` ↔
`SAFETY_OFFICER`, `SITE_SUPERVISOR` → `SUPERVISOR`) are added as Spring authorities
in `CustomUserDetailsService` so old JWTs and `hasRole(...)`-style code keep working,
but the canonical name is what's in the DB and the JWT `roles` claim.

### 3. The 108 permission codes

Defined in `backend/bipros-security/src/main/java/com/bipros/security/domain/model/PermissionCatalog.java`.
Format: `MODULE.ACTION`. Module prefixes:

```
PROJECT, ACTIVITY, SCHEDULE, BASELINE, RESOURCE, COST, EVM,
RISK, DOCUMENT, CONTRACT, PORTFOLIO, REPORT, AI,
DPR, NCR, PERMIT, SAFETY, YIELD_VARIANCE, DATA_QUALITY,
PROJECT_MEMBER, ADMIN_USER, ADMIN_PROFILE, ADMIN_ORG,
ADMIN_MASTER, ADMIN_SETTINGS,
WORKFRONT, SNAG, SHIFT_HANDOVER, ATTENDANCE, CHECKLIST,
PROCUREMENT_REQUEST
```

Action verbs: `CREATE`, `READ`, `UPDATE`, `DELETE`, `EXPORT`, `APPROVE`, `ANNOTATE`,
`AUDIT`, `WRITE`, `MANAGE`, `SUBMIT`, `REJECT`, `CLOSE`, `RELEASE`. Not every action
exists for every module — the catalog enumerates the 108 valid combinations.
`CLOSE` and `RELEASE` were added with Phase C (see §12) for `SNAG.CLOSE` and
`WORKFRONT.RELEASE` respectively.

### 4. JWT shape

`bipros-security/.../infrastructure/jwt/JwtTokenProvider.java` produces this access
token payload:

```json
{
  "sub":   "admin",
  "roles": ["ADMIN"],
  "perms": "ACTIVITY.CREATE,ACTIVITY.DELETE,ACTIVITY.READ,…",
  "iat":   1736890200,
  "exp":   1736893800
}
```

**`perms` is a sorted comma-joined string, not an array** (`String.join(",", new
TreeSet<>(perms))` at line 75). Frontend code that reads it must split on `,`.

The `roles` array contains canonical names without the `ROLE_` prefix; Spring
Security's middleware re-applies the prefix when it builds the `Authentication`.

### 5. Adding a new permission

1. Add a row to `PermissionCatalog.ALL` with the right module + action verb. Use
   the action constants at the top of the file.
2. If a default role should carry it, add the code to the relevant entry in
   `RolePermissionMatrix.DEFAULTS`.
3. Add the `@PreAuthorize("hasPermission(null, 'YOUR.CODE')")` annotation to the
   controller method.
4. If the endpoint is project-scoped, prefer
   `@PreAuthorize("@projectAccess.hasProjectPermission(#projectId, 'YOUR.CODE')")`.
5. Update or extend any test in `bipros-security/src/test/java` that asserts on
   the catalog size or the role→perm map.

The end-of-build coverage test `RbacCoverageIntegrationTest` will flag any
controller method that lacks a `@PreAuthorize` (added in Phase 0).

### 6. Adding a new canonical role

1. Append the role to `RolePermissionMatrix.DEFAULTS` with its permission set.
2. Add a row to the boot-time role seeder (`bipros-api/.../DataSeeder.java`).
3. If the role corresponds to a Profile UI option, add a system-default Profile
   row keyed by the same name. `ProfileSeeder.java` enumerates these.
4. If the role gets a legacy authority alias, add it to
   `CustomUserDetailsService.aliasFor(...)`.

### 7. Project-scoped access

The evaluator lives at
`bipros-security/.../infrastructure/security/CustomPermissionEvaluator.java`
(global perms) and `bipros-security/.../application/service/ProjectAccessService.java`
(project-scoped).

`hasProjectPermission(projectId, code)` returns true when:

- the user has `ROLE_ADMIN`, **or**
- the user has the global permission `code` (from role/profile), **or**
- the user is a `ProjectMember` of `projectId` and their member-role's permission
  set in `RolePermissionMatrix` contains `code`.

That permissive third path is intentional — it lets a viewer who's been added as
a project member do project-scoped reads they can't do globally. It does mean
some endpoints currently lack a "must-be-member" check on top of the global perm;
see *Known gaps* below.

### 8. Frontend gating

`frontend/src/lib/auth/permissions.ts` mirrors the backend matrix for client-side
UX gating only — the server is always the source of truth.

```ts
isAdmin(user)                       // user.roles.includes("ADMIN")
hasRole(user, role)
hasAnyRole(user, roles)
hasPermission(user, code)           // checks user.permissions[]
hasProjectPermission(user, projId, code)  // membership + perm
```

`useAuth()` in `frontend/src/lib/auth/useAuth.ts` is the React-side accessor.
The `Sidebar` component (`frontend/src/components/common/Sidebar.tsx`) filters
nav items by `adminOnly`, `permission`, and `module`.

`frontend/src/proxy.ts` (Next.js middleware) decodes the JWT cookie and:

- redirects unauthenticated users to `/auth/login`,
- redirects non-admins away from `/admin/*` to `/forbidden`,
- treats undecodable tokens as denied for `/admin/*` paths.

API failures (401/403) **do not** auto-redirect; the axios client surfaces them
to React Query and the calling component renders the error state. This is
deliberate — silent redirects on 403 mask bugs.

### 9. Booting the system

Local dev (the e2e suite assumes this exact shape):

```bash
# Backend — needs the AI KEK + dev profile so DataSeeder runs.
cd backend
BIPROS_AI_KEK="…"                  \
DDL_AUTO=update                    \
SPRING_PROFILES_ACTIVE=dev         \
mvn spring-boot:run -pl bipros-api

# Frontend
cd frontend && pnpm dev
```

Add `legacy-demo` to `SPRING_PROFILES_ACTIVE` to also seed the 20 ICPMS users
(`dmicdc.ceo`, `aecom.pmc.lead`, …) on password `ChangeMe@2026`. The seeder is
in `bipros-api/.../config/seeder/IcpmsPhaseASeeder.java`; downstream phases
(B–E) may fail in environments without ClickHouse — that's tolerated.

### 10. The 37-scenario e2e suite

`frontend/e2e/tests/33-rbac-comprehensive.spec.ts` — single file, eight describe
blocks, ~19s wall-clock against a warm backend.

| Block | Coverage |
| ----- | -------- |
| A — Auth & `/v1/auth/me` claim | JWT shape, `perms` CSV round-trip, refresh, VIEWER baseline |
| B — Sidebar gating | `/admin/*` link presence by role |
| C — Admin route guarding | 200 for admin, redirect for non-admin |
| D — Project scope | Membership + perm enforcement on members / activities |
| E — Action gating | Delete/Deactivate buttons, "New Activity" by role |
| F — Legacy aliases + supervisor cutover | `QC_MANAGER` alias, `/v1/users?roles=…` picker, Phase 4 field rename |
| G — Negative paths | Tampered cookie, VIEWER-tier user can't reach admin |
| H — Supervisor hardening (Phase A/B/C) | JWT carries new Phase C perms; workfront create+list; SNAG.CLOSE / PROCUREMENT_REQUEST.APPROVE / CHECKLIST.APPROVE gates fire 403 for SUPERVISOR; checklist templates load; project-tab strip reflects per-tab `permission`; VIEWER cannot create workfront |

To run:

```bash
cd frontend
pnpm playwright test 33-rbac-comprehensive
```

`globalSetup` provisions four `e2e_*` profile users (`SITE_MANAGER`,
`PROJECT_ENGINEER`, `QA_QC_ENGINEER`, `BIM_DATA_COORDINATOR`) and enrols them on
a fresh project plus the seeded `pmanager` (manager123). The fixtures are in
`frontend/e2e/fixtures/test-users.ts` and `auth.fixture.ts`.

#### Adding a new RBAC test

- Use the helpers `login(page)`, `loginAs(page, profileCode)`, or
  `loginAsSeeded(page, username, password?)`. Don't re-implement login.
- For JWT-claim assertions, `decodeJwt(token).perms` is a CSV string — split it
  with `parsePermsClaim()` (already in the spec).
- For project-scoped assertions, prefer `pmanager` + `e2e_*` users over ICPMS
  PD/PMC users; the ICPMS users have corridor scope but no `ProjectMember` row.
- For action-level write gates, post an empty body to an endpoint guarded by
  `@PreAuthorize` and read the status code — `403` means the gate fired, `400`
  means it passed and Bean Validation rejected the body. (One caveat: when
  `@Valid` runs before method security — as on `POST /v1/projects/{id}/dpr` —
  the gate is masked behind the 400. Use a different endpoint or test the gate
  via the corresponding `GET`/`DELETE`.)

### 11. Known gaps (live tickets)

1. **DPR list/create not project-scope-guarded.** `GET /v1/projects/{id}/dpr` and
   the corresponding write endpoints currently rely on the global `DPR.READ` /
   `DPR.CREATE` permission only. A non-member with the perm gets in. Suite test
   D18 originally exercised this; pivoted to `/activities` (correctly guarded)
   while the controller catches up.
2. **`@Valid` runs before `@PreAuthorize` on the DPR controller.** Invalid bodies
   short-circuit at 400 before method security runs, masking 403 outcomes.
   Reordering `@PreAuthorize` to a class-level annotation or moving validation
   into a custom converter would fix this.
3. **ICPMS PD/PMC users have corridor scope, no `ProjectMember` row.** The
   evaluator doesn't bridge corridor scope to project membership, so seeded PD
   users fail project-scoped checks. Either the seeder should enroll them or
   the evaluator should consult corridor scope.
4. **`bipros-ai` tool roster.** A couple of AI tools still call legacy supervisor
   getters via a native-query bridge (returns null silently). Not security-
   sensitive but worth cleaning up.
5. **Analytics column name.** `fact_dpr_issues_daily.supervisor_resource_id` in
   ClickHouse still uses the pre-cutover name; a separate analytics PR will
   rename it.
6. **`SecurityContextHelper.getCurrentUserId()` parses username as UUID.** Surfaced
   while authoring Block H of the e2e suite — the helper does
   `UUID.fromString(userDetails.getUsername())` but Spring Security passes the
   username, so every Phase C service that uses it for an audit column
   (`SnagService.create`, `ChecklistService.start`, `MaterialIndentService.create`)
   500s with "Invalid UUID string: <username>". `WorkfrontService` is unaffected
   because `BaseEntity.createdBy` is a String column populated by JPA auditing.
   Block H.34b / H.35b are `test.skip`'d until this is fixed.

**Closed by Phase C** (kept here as historical context):

- ~~NCR and Safety lack standalone controllers — only embedded in DPR.~~ Phase C
  added `NcrController` and `SafetyController` under `bipros-site-ops`.
- ~~Frontend write-affordance leaks on equipment-logs / labour-returns /
  weather-log.~~ Phase B added `hasPermission()` gates around the Add/Delete
  buttons on those three pages.
- ~~No permission gate on the "New Permit" page.~~ Phase B early-returns with a
  forbidden notice when the user lacks `PERMIT.CREATE`.

### 12. Site Ops modules (Phase C)

Phase C of the Supervisor hardening sweep added a new Maven module —
`bipros-site-ops` — with seven controllers, six new permission modules, and the
`CLOSE` / `RELEASE` action verbs. The SUPERVISOR row in
`RolePermissionMatrix.DEFAULTS` grew from 13 → 22 codes; `ProfileSeeder` is
self-healing and back-fills the new codes onto the 10 pre-existing system-default
profiles at boot (logged on startup).

| Module               | Endpoints                                                                            | Perms (SUPERVISOR gets ✓ / ✗)                                                                    | System-default profile holders                                                                              | Controller                                                                                                                              |
| -------------------- | ------------------------------------------------------------------------------------ | ------------------------------------------------------------------------------------------------ | ----------------------------------------------------------------------------------------------------------- | --------------------------------------------------------------------------------------------------------------------------------------- |
| Workfront            | `POST/GET/PUT /v1/projects/{id}/workfronts`, `POST /{id}/release`                    | `WORKFRONT.CREATE`✓ `.READ`✓ `.UPDATE`✓ `.RELEASE`✗                                              | Site Manager, Project Manager (incl. `.RELEASE`); Supervisor and Site Engineer (create/read/update)         | `backend/bipros-site-ops/src/main/java/com/bipros/siteops/api/WorkfrontController.java`                                                |
| Snag                 | `POST/GET/PUT /v1/projects/{id}/snags`, `POST /{id}/close`                           | `SNAG.CREATE`✓ `.READ`✓ `.UPDATE`✓ `.CLOSE`✗                                                     | QA QC Engineer (incl. `.CLOSE`); Supervisor / Site Engineer / Foreman raise & update                        | `backend/bipros-site-ops/src/main/java/com/bipros/siteops/api/SnagController.java`                                                     |
| Shift Handover       | `POST/GET /v1/projects/{id}/shift-handovers`, `POST /{id}/acknowledge`               | `SHIFT_HANDOVER.CREATE`✓ `.READ`✓                                                                | Supervisor, Foreman, Site Engineer, Site Manager                                                            | `backend/bipros-site-ops/src/main/java/com/bipros/siteops/api/ShiftHandoverController.java`                                            |
| Attendance           | `POST/GET/PUT /v1/projects/{id}/attendance`, `POST /{id}/approve`, `GET /summary`    | `ATTENDANCE.CREATE`✓ `.READ`✓ `.UPDATE`✓ `.APPROVE`✓                                             | Supervisor, Foreman, Site Manager (Site Engineer reads only)                                                | `backend/bipros-site-ops/src/main/java/com/bipros/siteops/api/AttendanceController.java`                                               |
| Material Indent      | `POST/GET/PUT /v1/projects/{id}/material-indents`, `POST /{id}/{submit,approve,reject}` | `PROCUREMENT_REQUEST.CREATE`✓ `.READ`✓ `.APPROVE`✗                                            | Store Manager, Procurement Officer, Project Manager (incl. `.APPROVE`); Supervisor / Foreman raise & submit | `backend/bipros-site-ops/src/main/java/com/bipros/siteops/api/MaterialIndentController.java`                                           |
| Checklist            | `GET /v1/checklist-templates`, `POST/GET /v1/projects/{id}/checklists`, `POST /{id}/{submit,approve,reject}` | `CHECKLIST.CREATE`✓ `.READ`✓ `.UPDATE`✓ `.APPROVE`✗                                  | QA QC Engineer (incl. `.APPROVE`); Supervisor / Foreman / Site Engineer fill out                            | `backend/bipros-site-ops/src/main/java/com/bipros/siteops/api/ChecklistController.java` + `ChecklistTemplateController.java`           |
| NCR (standalone)     | `POST/GET/PUT /v1/projects/{id}/ncrs`, `POST /{id}/approve-closure`, `POST /{id}/reject` | Existing `NCR.*` perms — SUPERVISOR gets `.CREATE`✓ `.READ`✓ `.UPDATE`✓                       | QA QC Engineer (incl. closure approval); Supervisor raises and updates                                      | `backend/bipros-site-ops/src/main/java/com/bipros/siteops/api/NcrController.java`                                                      |
| Safety (standalone)  | `POST/GET/PUT /v1/projects/{id}/safety`                                              | Existing `SAFETY.*` perms — SUPERVISOR gets `.CREATE`✓ `.READ`✓                                  | Safety Officer (full); Supervisor raises and reads                                                          | `backend/bipros-site-ops/src/main/java/com/bipros/siteops/api/SafetyController.java`                                                   |

Checklist templates are seeded on first boot (`ChecklistTemplateSeeder`):
`PRE_CONCRETE`, `EXCAVATION`, `SHUTTERING`. Any checklist instance starts as
`DRAFT`, moves through `IN_PROGRESS` (supervisor fills it), `SUBMITTED`, and
ultimately `APPROVED` / `REJECTED` (QA QC Engineer).

All Phase C controllers use the project-scoped guard
`@projectAccess.hasProjectPermission(#projectId, '<MODULE>.<ACTION>')` except
`GET /v1/checklist-templates`, which is a global read.

The Phase A matrix test
`backend/bipros-security/src/test/java/.../RolePermissionMatrixTest.java#supervisorHasSiteOpsPermissions`
pins the 22 SUPERVISOR perms and explicitly asserts the four closure perms
(`WORKFRONT.RELEASE`, `SNAG.CLOSE`, `CHECKLIST.APPROVE`,
`PROCUREMENT_REQUEST.APPROVE`) are *absent*. Block H of the e2e suite re-asserts
the same shape end-to-end (JWT → API → UI).

---

## Part II — Administrator & End-User Guide

### 1. Who you are in the system

When you sign in, the system computes who you are along two axes:

- **Role** — a fixed identity like *Project Manager*, *Site Engineer*, *Auditor*,
  *Admin*. You may carry more than one role. Roles are the coarse description of
  what kind of work you do. There are 22 of them; an Admin assigns them on the
  Users page.
- **Profile** — the explicit permission bundle attached to your account. Profiles
  start as a copy of one of the 22 role templates (a *system-default profile*)
  and an Admin can edit your profile to add or revoke individual permissions
  without changing your role. Think of the role as the title and the profile as
  the badge that lists exactly what doors it opens.

In addition to the global role and profile, every project you can work on has
its own list of **project members**. Being a member of a project — with a
member-role like *Project Manager*, *Team Member*, or *Client* — is what lets
you actually read or change that project's data.

### 2. Why you might not see a button or page

There are three failure modes you'll meet:

| What you see                                                | What it means                                              | What to do                                                       |
| ----------------------------------------------------------- | ---------------------------------------------------------- | ---------------------------------------------------------------- |
| You're bounced to `/auth/login`                             | Your session expired, or your access token is invalid.     | Sign in again.                                                   |
| You land on `/forbidden` after clicking a link              | You're logged in, but you lack the role for that area.     | Ask an Admin to assign the role or use a different account.      |
| The page loads, but a button or section is missing          | You're logged in and on the right page, but your profile or project-member role doesn't include the permission for that action. | Ask an Admin to extend your profile or your project membership. |

### 3. Where each thing is set

Everything below requires Admin (`ROLE_ADMIN`). Other roles can read their own
profile and the project members of projects they belong to.

#### Users (`/admin/users`)
- Create, deactivate, or edit a user.
- Reset a user's password.
- Assign a Profile to a user (drop-down). Changing the Profile changes what the
  user can do next time they log in or refresh their token.
- The Delete button is actually a **Deactivate** — users are never destroyed,
  only disabled, so audit history stays intact.

#### Roles (`/admin/roles`)
- Read-only catalogue of the 22 canonical roles.
- Each role row links to its default Profile so you can see exactly which
  permissions the role implies.

#### Profiles (`/admin/profiles`)
- Edit or clone a permission bundle.
- System-default profiles (one per role) are read-only by name and role mapping,
  but you can edit the permission list. The system shows a warning when you do.
- Custom profiles can be renamed and freely edited. They cannot be deleted while
  any user is assigned to them.

#### Project members (`/projects/{id}/members`)
- Add an existing user to a project with a member-role.
- Remove a user from a project — their token keeps working until they re-login
  or it expires, after which they lose access to the project's data.
- Change a member's role within the project (e.g. promote *Team Member* to
  *Project Manager*).

#### Site-ops project tabs (`/projects/{id}` tab strip)

The seven Phase C tabs surface on a project page only if the user's profile (or
project-member role) carries the matching READ permission. For a Supervisor on
their enrolled project, they appear in this order to the right of *GIS*:

| Tab          | Permission to see           | What the supervisor does here                                                                |
| ------------ | --------------------------- | -------------------------------------------------------------------------------------------- |
| Workfronts   | `WORKFRONT.READ`            | Marks a workfront *Planned*/*Ready*/*Handed-Over*; release is reserved for Site/Project Mgr. |
| Snags        | `SNAG.READ`                 | Raises and updates snags; closure goes to QA QC Engineer.                                    |
| Handovers    | `SHIFT_HANDOVER.READ`       | Writes the end-of-shift handover; outgoing shift acknowledges incoming.                      |
| Attendance   | `ATTENDANCE.READ`           | Logs daily attendance and approves the day's roster.                                         |
| Checklists   | `CHECKLIST.READ`            | Picks a template (PRE_CONCRETE / EXCAVATION / SHUTTERING) and fills it out; approval is QA.  |
| Indents      | `PROCUREMENT_REQUEST.READ`  | Raises material indents (DRAFT → submit); approval is Store Manager / Procurement Officer.   |
| NCRs         | `NCR.READ`                  | Raises and updates NCRs; closure approval is QA QC Engineer.                                 |

Tabs the supervisor does *not* see (because the corresponding READ perm is
absent on their profile): *Costs*, *EVM*, *Baselines*, *Risks*, *Contracts*.

### 4. A worked example

Pradeep is a new site engineer joining DMIC-N03 P01. Steps:

1. **Admin → Users → New**. Username `pradeep.s`, role *SITE_ENGINEER*, profile
   *Site Engineer* (the system-default).
2. Send Pradeep his temporary password.
3. **Open the project DMIC-N03 P01 → Members → Add**. Pick *Pradeep S*, role
   *Team Member*.
4. Pradeep signs in. He sees the *Plan*, *Execute*, and *Master Data* sidebar
   groups (his role lets him), and the DMIC-N03 P01 project under *Projects*.
   He does not see `/admin/*` items.
5. Pradeep opens DMIC-N03 P01 → DPR. He sees an *Add DPR* button because his
   profile carries `DPR.CREATE` and he's a project member. He doesn't see
   *Add Activity* because his profile doesn't carry `ACTIVITY.CREATE`.

If you only need Pradeep to view DPRs (not log them), change his profile to
something like *Site Manager — Read Only* (cloned from *Site Manager* with
`DPR.CREATE` removed) before assigning.

### 5. The legacy role names

Old documents and templates may say *QC Manager*, *HSE Officer*, or *Site
Supervisor*. The canonical names are *QA QC Engineer*, *Safety Officer*, and
*Supervisor*. The system accepts the old names everywhere they used to work —
old JWTs, old profile names, role-based redirects — but the Admin UI shows the
new names. If you see a mismatch in a screenshot from before May 2026, it's the
same role under a different label.

### 6. The Supervisor picker

DPR and Activity supervisor pickers no longer pull from the resource pool. They
query users by role (*Supervisor*, *Foreman*, *Site Engineer*, *Site Manager*).
A picked supervisor must therefore exist as a User in the system. If a supervisor
name appears in an old DPR but their User account has been deactivated, the row
will keep the historical name but the picker won't suggest them for new rows.

### 7. Common questions

**Q: I'm a Project Manager but I can't see the project I'm supposed to manage.**
You may not have a *ProjectMember* row on that project yet. Ask the project
owner to add you under `/projects/{id}/members`. Being the PM by role isn't the
same as being a member of a particular project.

**Q: I added a user as a member but they still get 403s.**
Tokens are cached for ~15 minutes. Ask the user to sign out and back in (or
wait). The cached JWT still claims their old permissions.

**Q: I clicked Delete on a user and they came back the next day.**
Delete is actually Deactivate. The user is hidden from selection but their
record persists. Re-activating them is one click on the same screen.

**Q: A button shows up, I click it, and I get an "access denied" toast.**
The frontend tries to hide actions you can't take but it's not always perfect.
The server is the source of truth — when the button slips through and the API
rejects, the toast is the safety net. If you think you should be able to do
this action, the fix is on your profile, not the page.

**Q: How do I see exactly what I'm allowed to do?**
`/admin/users` → click your name → the *Effective permissions* section lists
every permission code you currently carry. Self-readable; you don't need Admin
for your own row.

---

## Appendix — Where each piece lives

| Concern                          | Path                                                                                                   |
| -------------------------------- | ------------------------------------------------------------------------------------------------------ |
| Permission catalogue (86 codes)  | `backend/bipros-security/src/main/java/com/bipros/security/domain/model/PermissionCatalog.java`        |
| Role → permission matrix (22)    | `backend/bipros-security/src/main/java/com/bipros/security/domain/model/RolePermissionMatrix.java`     |
| Global perm evaluator            | `backend/bipros-security/src/main/java/com/bipros/security/infrastructure/security/CustomPermissionEvaluator.java` |
| Project-scope evaluator          | `backend/bipros-security/src/main/java/com/bipros/security/application/service/ProjectAccessService.java` |
| Legacy authority aliases         | `backend/bipros-security/src/main/java/com/bipros/security/infrastructure/security/CustomUserDetailsService.java` |
| JWT generation                   | `backend/bipros-security/src/main/java/com/bipros/security/infrastructure/jwt/JwtTokenProvider.java`   |
| Default role seeding             | `backend/bipros-api/src/main/java/com/bipros/api/config/DataSeeder.java`                               |
| Default profile seeding          | `backend/bipros-api/src/main/java/com/bipros/api/config/ProfileSeeder.java`                            |
| ICPMS demo users                 | `backend/bipros-api/src/main/java/com/bipros/api/config/seeder/IcpmsPhaseASeeder.java`                 |
| FE auth store + hooks            | `frontend/src/lib/auth/` + `frontend/src/lib/state/store.ts`                                           |
| Next.js middleware               | `frontend/src/proxy.ts`                                                                                |
| Sidebar gating                   | `frontend/src/components/common/Sidebar.tsx`                                                           |
| Admin pages                      | `frontend/src/app/(app)/admin/{users,roles,profiles}/`                                                 |
| Project members page             | `frontend/src/app/(app)/projects/[id]/members/`                                                        |
| 37-scenario e2e suite            | `frontend/e2e/tests/33-rbac-comprehensive.spec.ts`                                                     |
| E2E fixture helpers              | `frontend/e2e/fixtures/auth.fixture.ts`, `frontend/e2e/fixtures/test-users.ts`                         |
| Design spec for the suite        | `docs/superpowers/specs/2026-05-14-rbac-e2e-tests-design.md`                                           |
| Phase C — Workfront controller   | `backend/bipros-site-ops/src/main/java/com/bipros/siteops/api/WorkfrontController.java`                |
| Phase C — Snag controller        | `backend/bipros-site-ops/src/main/java/com/bipros/siteops/api/SnagController.java`                     |
| Phase C — Shift Handover         | `backend/bipros-site-ops/src/main/java/com/bipros/siteops/api/ShiftHandoverController.java`            |
| Phase C — Attendance             | `backend/bipros-site-ops/src/main/java/com/bipros/siteops/api/AttendanceController.java`               |
| Phase C — Material Indent        | `backend/bipros-site-ops/src/main/java/com/bipros/siteops/api/MaterialIndentController.java`           |
| Phase C — Checklist (+ templates)| `backend/bipros-site-ops/src/main/java/com/bipros/siteops/api/ChecklistController.java`, `ChecklistTemplateController.java` |
| Phase C — NCR controller         | `backend/bipros-site-ops/src/main/java/com/bipros/siteops/api/NcrController.java`                      |
| Phase C — Safety controller      | `backend/bipros-site-ops/src/main/java/com/bipros/siteops/api/SafetyController.java`                   |
| Phase C — Project tab strip      | `frontend/src/app/(app)/projects/[projectId]/layout.tsx` (lines 64–71 — per-tab `permission` field)    |
