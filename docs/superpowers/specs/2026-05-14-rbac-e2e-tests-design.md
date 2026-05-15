# RBAC Comprehensive E2E Test Suite — Design

**Date:** 2026-05-14
**Branch:** `feat/ai-scope-and-rate-hardening`
**Scope:** 30 Playwright e2e scenarios that validate the 7-phase RBAC overhaul (commits `c3aedba` … `5831b27`).

## Goal

Lock down end-to-end behaviour for:

- The 22-role canonical catalog + permission code matrix (Phase 1).
- `CustomPermissionEvaluator` + project-scoped `hasProjectPermission` (Phase 2).
- The 151-controller `@PreAuthorize` migration to `hasPermission(...)` (Phase 3).
- The supervisor identity cutover from `Resource` to `User` (Phase 4).
- Admin UI surfaces and Sidebar permission gating (Phase 5).
- Legacy authority aliases (`QC_MANAGER` ↔ `QA_QC_ENGINEER`, etc.) (Phase 0).

The suite is intentionally broad-but-shallow: each test asserts a *visible* RBAC boundary (status code, redirect, button visibility, network call shape). Deep behavioural tests stay in the existing per-feature specs.

## File layout

Single file:

```
frontend/e2e/tests/33-rbac-comprehensive.spec.ts
```

Helpers extended in `frontend/e2e/fixtures/auth.fixture.ts`:

- `loginAsSeeded(page, username, password?)` — authenticates as a backend-seeded ICPMS user (`ChangeMe@2026` is the default). Returns `{ accessToken, user }` so tests can assert claims and use the token for direct API calls.
- `decodeJwt(token)` — pure helper returning the decoded JWT payload (no signature check) for `perms` / `roles` claim assertions.

## User fixtures

Reuse what already exists; **no new seeders required.**

| Source | Examples | Role / Profile |
|---|---|---|
| `BiprosApplication` boot seed | `admin` / `admin123` | `ADMIN` |
| `IcpmsPhaseASeeder` (`ChangeMe@2026`) | `nicdc.secretary`, `cag.auditor`, `cvc.officer`, `aadhaar.citizen` | `VIEWER` + module matrices |
| `IcpmsPhaseASeeder` | `dmicdc.ceo`, `dmicdc.pd.n03`, `dmicdc.pd.n04`, `aecom.pmc.lead`, `egis.pmc.lead`, `lnt.pm.n03`, `tata.pm.n04` | `PROJECT_MANAGER` |
| `IcpmsPhaseASeeder` | `aecom.sched` | `SCHEDULER` |
| `IcpmsPhaseASeeder` | `lnt.sitein` | `VIEWER` (EPC site) |
| `globalSetup` provisions | `e2e_smanager`, `e2e_pengineer`, `e2e_qcmanager`, `e2e_bimcoord` | profile-coded |
| `/v1/auth/register` (ad-hoc) | `pw_zero_perm_user` | `VIEWER` (default) — for "minimal perms" cases |

## 30 scenarios

### Block A — `/v1/auth/me` permissions claim (5 tests)

| # | Scenario | Acting user | Assertion |
|---|---|---|---|
| 1 | Admin permissions are a full superset | `admin` | `permissions[]` contains `USER.CREATE`, `ROLE.MANAGE`, `PROFILE.MANAGE` |
| 2 | Citizen viewer permissions are read-only | `aadhaar.citizen` | `permissions[]` contains no `*.CREATE` / `*.DELETE` / `*.MANAGE` codes |
| 3 | JWT `perms` claim mirrors `/users/me.permissions[]` | `dmicdc.pd.n03` | Decoded JWT `perms` set equals API `permissions[]` set |
| 4 | Refresh token preserves `perms` claim | `aecom.pmc.lead` | After `POST /v1/auth/refresh`, the new access token still carries `perms` |
| 5 | Newly registered user has VIEWER-tier permissions | freshly registered | `permissions[]` ⊂ VIEWER role's row in `RolePermissionMatrix` |

### Block B — Sidebar permission gating (5 tests)

| # | Scenario | Acting user | Assertion |
|---|---|---|---|
| 6 | Admin sees all admin nav items | `admin` | Sidebar contains links matching `/admin/users`, `/admin/roles`, `/admin/profiles` |
| 7 | Citizen sees zero admin nav items | `aadhaar.citizen` | No sidebar `<a>` has `href` starting with `/admin/` |
| 8 | PMC Lead sees PMC, not Admin Users | `aecom.pmc.lead` | Sidebar has DPR / Activity links, no `/admin/users` link |
| 9 | Auditor sees read-only nav | `cag.auditor` | Sidebar has no "New …" buttons (page-level toolbars assert separately) |
| 10 | SITE_MANAGER profile sees site-relevant nav, no `/admin/*` | `loginAs('SITE_MANAGER')` | No `/admin/` href anywhere in sidebar |

### Block C — Admin route guarding (6 tests)

| # | Scenario | Acting user | Assertion |
|---|---|---|---|
| 11 | Admin can open `/admin/users` | `admin` | Heading `Users` visible, table rendered |
| 12 | PROJECT_MANAGER blocked from `/admin/users` | `lnt.pm.n03` | Redirected to `/forbidden` OR `/v1/users` API returns 403 |
| 13 | Admin opens `/admin/roles` and sees ≥ 22 roles | `admin` | Page renders, role list has 22+ entries |
| 14 | Non-admin blocked from `/admin/roles` | `dmicdc.pd.n03` | Forbidden |
| 15 | Admin opens `/admin/profiles` and sees system-defaults | `admin` | At least 22 Profile rows visible |
| 16 | Auditor blocked from `/admin/profiles` | `cag.auditor` | Forbidden |

### Block D — Project-scoped permissions (5 tests)

| # | Scenario | Acting user | Assertion |
|---|---|---|---|
| 17 | PD can open own project members but not stranger project | `dmicdc.pd.n03` | `/projects/{N03}/members` 200; `/projects/{N04}/members` 403/forbidden |
| 18 | Site Manager reads DPRs only in enrolled project | `e2e_smanager` | `GET /v1/projects/{enrolled}/dprs` 200; same against a stranger project 403 |
| 19 | Non-member project members page is forbidden | `aadhaar.citizen` | Visiting `/projects/{anyId}/members` → `/forbidden` |
| 20 | "New DPR" button visible only to writers | PM vs Viewer in same project | PM sees button; `lnt.sitein` does not |
| 21 | Member removal cuts off project access | admin removes `e2e_pengineer` mid-test, then re-login | After re-login, member redirected away from project |

### Block E — Action-level button gating (4 tests)

| # | Scenario | Acting user | Assertion |
|---|---|---|---|
| 22 | Admin row in `/admin/users` exposes Delete | `admin` | Each row has a Delete button |
| 23 | PROJECT_MANAGER cannot see Delete user button | `dmicdc.ceo` | On `/admin/users` (if reachable) Delete control is absent OR page is forbidden |
| 24 | PROJECT_MANAGER sees Add Activity in own project | `lnt.pm.n03` | "Add Activity" / "New Activity" button visible on activity list |
| 25 | VIEWER does not see Add Activity in same project | `lnt.sitein` | Button absent |

### Block F — Legacy aliases & supervisor cutover (3 tests)

| # | Scenario | Acting user | Assertion |
|---|---|---|---|
| 26 | Legacy alias resolves to canonical | `e2e_qcmanager` (`QC_MANAGER` profile) | Can call a `QA_QC_ENGINEER`-gated endpoint (e.g. quality module) without 403 |
| 27 | DPR supervisor picker hits Users, not Resources | `aecom.pmc.lead` opens DPR new page | Network log shows `/v1/users?roles=...` request; no `/v1/resources?type=SUPERVISOR` request fires |
| 28 | DPR detail resolves supervisor via User identity | `aecom.pmc.lead` opens a seeded DPR | Supervisor name renders (non-empty, not "Unknown" / "—") |

### Block G — Negative & edge cases (2 tests)

| # | Scenario | Acting user | Assertion |
|---|---|---|---|
| 29 | Tampered cookie redirects to login | n/a | After mutating `access_token` cookie, navigating `/` ends at `/auth/login` |
| 30 | Zero-permission user — protected nav hidden, admin route forbidden | freshly registered | Dashboard renders; no `/admin/` sidebar links; `/admin/users` → `/forbidden` |

## What the tests do NOT cover

- Field-level masking (deferred to backend `SecurityIT`).
- ABAC row filtering (covered by backend integration tests).
- ClickHouse fact-table column rename (analytics PR, out of scope).
- `bipros-ai` tool authority — the Phase 4.5 bridge returns null silently; covered when the AI follow-up PR lands.

## Implementation strategy

Three parallel implementer agents and one Devil's Advocate. Implementers each own a distinct scratch file to avoid merge conflicts; results are consolidated into the single `33-rbac-comprehensive.spec.ts`.

| Agent | Scope | Output |
|---|---|---|
| Implementer A | Blocks A + B (10 tests) | `e2e/tests/_rbac/33a-auth-and-sidebar.spec.ts` |
| Implementer B | Blocks C + D (11 tests) | `e2e/tests/_rbac/33b-admin-and-project.spec.ts` |
| Implementer C | Blocks E + F + G (9 tests) | `e2e/tests/_rbac/33c-actions-legacy-negative.spec.ts` |
| Devil's Advocate | Read this spec + the existing fixtures, critique | Report identifying weakest tests, false-positive risks, missing coverage |

Consolidation step then merges the three scratch files into a single `33-rbac-comprehensive.spec.ts`, drops the `_rbac/` scratch directory, and fixes the issues the Devil's Advocate flagged.

## Verification gate

After consolidation:

- `pnpm exec tsc --noEmit` must pass for the new file.
- `pnpm exec playwright test 33-rbac-comprehensive --list` must enumerate exactly 30 tests.
- A representative subset (3-5 tests) is run against a live backend to confirm fixtures work; remaining run in CI.

## Risks

- **Seed dependency.** The suite assumes the ICPMS Phase A seeder has run. Tests that hit `lnt.pm.n03` etc. will fail on a `DDL_AUTO=create-drop` reboot without seed. Documented in the file's top-level comment.
- **Fragile selectors.** Sidebar / admin-page selectors will need updating if the UI is restyled. We use role-based locators (`getByRole('heading', { name: /…/i })`) over CSS to limit blast radius.
- **JWT decode without verification.** `decodeJwt` is a base64-only split; safe because tests trust the token they just received from the backend in the same request.
