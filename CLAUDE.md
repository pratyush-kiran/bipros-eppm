# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Start here

`AGENTS.md` at the repo root is the canonical reference for build commands, test commands, Docker, and the per-language coding conventions (Java entity/DTO/service/controller patterns, TypeScript/Next.js patterns, ESLint). **Read it first.** This file only adds the cross-cutting architecture and project-specific gotchas that aren't in AGENTS.md.

The frontend has its own `frontend/AGENTS.md` and `frontend/CLAUDE.md` — both important; see the Next.js 16 note below.

## Working discipline

Behavioral guidelines to reduce common LLM coding mistakes.

**Tradeoff:** these guidelines bias toward caution over speed. For trivial tasks, use judgment.

### 1. Think Before Coding

**Don't assume. Don't hide confusion. Surface tradeoffs.**

Before implementing:
- State your assumptions explicitly. If uncertain, ask.
- If multiple interpretations exist, present them — don't pick silently.
- If a simpler approach exists, say so. Push back when warranted.
- If something is unclear, stop. Name what's confusing. Ask.

### 2. Simplicity First

**Minimum code that solves the problem. Nothing speculative.**

- No features beyond what was asked.
- No abstractions for single-use code.
- No "flexibility" or "configurability" that wasn't requested.
- No error handling for impossible scenarios.
- If you write 200 lines and it could be 50, rewrite it.

Ask yourself: "Would a senior engineer say this is overcomplicated?" If yes, simplify.

### 3. Surgical Changes

**Touch only what you must. Clean up only your own mess.**

When editing existing code:
- Don't "improve" adjacent code, comments, or formatting.
- Don't refactor things that aren't broken.
- Match existing style, even if you'd do it differently.
- If you notice unrelated dead code, mention it — don't delete it.

When your changes create orphans:
- Remove imports/variables/functions that YOUR changes made unused.
- Don't remove pre-existing dead code unless asked.

The test: Every changed line should trace directly to the user's request.

### 4. Goal-Driven Execution

**Define success criteria. Loop until verified.**

Transform tasks into verifiable goals:
- "Add validation" → "Write tests for invalid inputs, then make them pass"
- "Fix the bug" → "Write a test that reproduces it, then make it pass"
- "Refactor X" → "Ensure tests pass before and after"

For multi-step tasks, state a brief plan:
```
1. [Step] → verify: [check]
2. [Step] → verify: [check]
3. [Step] → verify: [check]
```

Strong success criteria let you loop independently. Weak criteria ("make it work") require constant clarification.

**These guidelines are working if:** fewer unnecessary changes in diffs, fewer rewrites due to overcomplication, and clarifying questions come before implementation rather than after mistakes.

## Big-picture architecture

Full-stack monorepo. Two deployables, one database:

- **`backend/`** — Maven multi-module Spring Boot 3.5 / Java 23 app. 21 modules, one module per DDD bounded context plus `bipros-common` (shared utilities, `BaseEntity`, `ApiResponse`, exceptions, `GlobalExceptionHandler`) and `bipros-api` (the aggregator — depends on every domain module, owns `BiprosApplication.java`, `application.yml`, seeders). All domain modules produce Spring beans via component scan; they only run when wired in through `bipros-api`.
- **`frontend/`** — Next.js 16 / React 19 app using App Router, pnpm. Authenticated pages live under `src/app/(app)/...`; unauthenticated auth flows under `src/app/auth/`. API calls go through `src/lib/api/*Api.ts` (one file per backend domain), all sharing the axios client in `src/lib/api/client.ts` which attaches JWT + auto-refresh.
- **`docker/init-schemas.sql`** — creates a `bipros` DB user and one PostgreSQL **schema per bounded context** (`project`, `activity`, `scheduling`, `resource`, `cost`, `evm`, `baseline`, `udf`, `risk`, `portfolio`, `contract`, `document`). Each backend module maps its entities to its own schema via `@Table(schema = "...")`. When adding a new domain module, add its schema here too.

### Key consequences of this structure

- **Dev schema evolves additively.** `application.yml` sets `ddl-auto: update` (override with `DDL_AUTO=create-drop` for a clean slate) and Liquibase is disabled. **`update` only adds — it never drops columns, never removes NOT NULL, never narrows types.** When you delete a field from an entity or switch from a feature branch back to main, the old column lingers in the DB with its old constraints. Symptom: insert fails with `null value in column "X" violates not-null constraint` for a column that no longer exists in your code. Fix: `ALTER TABLE … DROP COLUMN X` manually, or restart with `DDL_AUTO=create-drop` to wipe the schema. Production profile (`prod`) flips to `ddl-auto: validate` and enables Liquibase; migrations under `backend/bipros-api/src/main/resources/db/changelog/` are the source of truth there.
- **Seeders live in `bipros-api/src/main/java/com/bipros/api/config/seeder/`** (ICPMS demo data) and run at boot via Spring. The `scripts/seed-*.sh` scripts are HTTP-based seeders that hit the running API — use them when you need to reset demo data without a backend restart.
- **Cross-module dependencies flow inward through `bipros-common`.** Domain modules should not depend on each other; if they need to, extend `bipros-common` or coordinate via `bipros-api`. Keep each module's `api/` → `application/` → `domain/` → `infrastructure/` layering intact.
- **Every response is wrapped in `ApiResponse<T>`** (from `bipros-common`). The frontend `ApiResponse<T>` / `PagedResponse<T>` types in `src/lib/types/index.ts` mirror this — keep them in sync when changing the envelope shape.

## Currency (per-project, relabel-only)

Each project stores **one** currency in `Project.budgetCurrency` (ISO code string, default `INR`). Every monetary value inside that project must render in that one currency — same symbol, terminology, and decimals on every tab. Historically the same project showed `₹` on some screens, `OMR` on others, even `$` on one; that inconsistency is what this system fixes.

**Core principle — relabel, never convert.** All money in the system is currency-neutral raw numbers (`units × rate`, variance, CV/CPI/SPI, contribution, efficiency). Changing a project's currency only changes how numbers are *labelled and abbreviated* — it NEVER multiplies by an exchange rate and NEVER touches any business-value calculation. There is a `CurrencyService.convert()` in the backend but **it is called from zero production paths — do not wire it into any money path.** No FX rates are maintained anywhere.

### The pieces

- **`frontend/src/lib/currency/format.ts`** (canonical formatter — pure, no React). `resolveCurrencyMeta(code, master?)` + `formatMoney(amount, meta, opts?)`. The symbol and numbering are derived from the ISO code via `Intl` first, so they are correct **even when the currencies master 403s** (see permission gotcha below); the master only enriches a custom glyph / `decimalPlaces`. Compact ladder is currency-driven:
  - `INR` → Indian: `≥1e7` "Cr" (÷1e7) · `≥1e5` "L" (÷1e5) · `≥1e3` "k", grouped `en-IN`.
  - everything else → international: `≥1e9` "B" · `≥1e6` "M" · `≥1e3` "K", grouped `en-US`.
  - So `30000000` renders `3 Cr` for an INR project but `30 M` for an OMR/USD project — same stored number, different label. Glyph currencies render `{symbol}{number}{suffix}`; code-only currencies (OMR/AED/SAR) render `{number} {suffix} {CODE}`.
- **`frontend/src/lib/currency/ProjectCurrencyProvider.tsx`** (the single source of "current project currency"). Mounted once in `projects/[projectId]/layout.tsx` (the layout already owns the `["project", projectId]` query, so no extra fetch). Expose money formatting to any tab via `useProjectCurrency()` → `{ code, symbol, decimalPlaces, isIndian, money(amount, opts?), moneyCompact(amount, opts?) }`. Use `useProjectCurrencyOptional()` (returns `null` outside a provider) in components also reused on portfolio/global screens. **Any page under `[projectId]/` formats money through this hook — never hardcode `₹`/`$`/`en-IN`/a crore divisor.**

### What happens when someone changes a project's currency

The selector lives on Project Overview (`projects/[projectId]/page.tsx`, `CurrencyCard`). It calls `projectApi.updateProject(projectId, { budgetCurrency })` with only that field, shows a confirm dialog ("relabels amounts only — values are not converted"), and on success invalidates `["project", projectId]` + `["project-budget", projectId]` so every child tab and `useProjectCurrency` re-render.

The one subtlety is the **BAC (Budget At Completion)**, which is stored in *major-unit* scale (crores = 1e7 for INR, millions = 1e6 for others) and multiplied back up by `EvmService`/`CostService`. To keep the *raw money* constant across that unit gap, `ProjectService.updateProject` detects a currency change and **rescales** `originalBudget`/`currentBudget` by `oldFactor / newFactor`. Example: a stored `2` under INR (= ₹2 Cr = 20,000,000) becomes `20` under OMR (= 20 M = 20,000,000) — the displayed money is identical, only the unit label flips. No data migration, no EVM/Cost formula change, no re-entry prompt. Known limitation: historical approved budget-change-request rows are not individually rescaled (only the `currentBudget` aggregate is) — rare, and documented here so it isn't mistaken for a bug.

### Adding a NEW currency (the supported flow)

1. **Create it under Settings first** — the Currency master (`bipros-admin`, `Currency`: code, name, symbol, `decimalPlaces`, `isBaseCurrency`, `exchangeRate`) via `/v1/admin/currencies`. `exchangeRate` is stored but unused by money rendering; `decimalPlaces` is honoured (OMR = 3).
2. **Then set it on a project** — the New Project dropdown and the Overview selector are driven by `settingsApi.listCurrencies()` (no hardcoded list). Once selected, every tab picks it up through `useProjectCurrency()` automatically.

If a currency renders only as its bare code with no symbol, either add a glyph in the Currency master or extend the `KNOWN_GLYPHS` map in `format.ts` — `Intl` already covers most ISO codes.

### Permission gotcha (root cause of the old inconsistency)

`GET /v1/admin/currencies` requires `ADMIN_MASTER.READ`, which Finance/Supervisor/Engineer roles lack even though they see money (`COST.READ`/`DPR.READ`). When the master 403s, the provider treats it as "no enrichment" and **falls back to `Intl`-derived symbol/numbering from the ISO code** — so the currency is still correct, just without a custom glyph. The old bug was a silent fallback to INR `₹`; that path no longer exists. The `["currencies"]` query in the provider uses `retry: false` and never surfaces the error.

### Scope boundary

Portfolio/executive/financial dashboards and hub banners (`app/(app)/dashboard/`, `dashboards/*`, `hub/mission-control/*`, `HubGreeting`/`HubBanner`, `PortfolioKpiRow`) aggregate **many** projects with different currencies — they must **not** call `useProjectCurrency()` (it throws outside the provider, and a single symbol would be wrong for a mixed set). Leave those on their crore-basis fields. Global rate masters (`components/role/*`, `admin/unit-rate-master`, `admin/productivity-norms`) are currency-neutral — show **no symbol** ("interpreted in each project's own currency").

After any change here, a grep for `₹`, `$`, `en-IN`, `INR_PER_CRORE`, `ONE_CRORE`, `formatCrore`, `formatINR` should return only the documented portfolio/global files above.

## Commands not in AGENTS.md

### Running the full stack locally
```bash
docker compose up -d                        # Postgres + pgAdmin + Redis
(cd backend && mvn spring-boot:run -pl bipros-api)   # starts on :8080
(cd frontend && pnpm dev)                   # starts on :3000
```
Backend seeds an admin user on first boot: `admin` / `admin123`.

### Seeding demo data (backend must be running)
```bash
./scripts/seed-demo-data.sh        # Generic construction/engineering demo
./scripts/seed-icpms-data.sh       # ICPMS-specific dataset
./scripts/seed-post-data.sh        # Post-boot seed extensions
./scripts/restore-seed-data.sh     # Reset to a known state
./scripts/e2e-test.sh              # Curl-based end-to-end API walkthrough
```

### Frontend e2e (Playwright)
```bash
cd frontend
pnpm test:e2e             # headless
pnpm test:e2e:ui          # Playwright UI
pnpm test:e2e:headed      # headed browser
pnpm test:e2e:report      # view last report
```
`playwright.config.ts` auto-starts `pnpm dev` if it's not already running; backend must be started separately.

## Next.js 16 warning

`frontend/AGENTS.md` is blunt about this and it's worth repeating: **this is Next.js 16, not the Next.js in your training data.** APIs, conventions, and file layout may differ. Before writing frontend code that touches Next.js specifics (routing, server components, caching, `next/*` imports), read the relevant guide in `frontend/node_modules/next/dist/docs/`. Heed deprecation notices.

## Swagger / API discovery

Backend running → `http://localhost:8080/swagger-ui.html` for the live OpenAPI spec. Faster than grepping controllers when you need to find an endpoint.

## graphify

This project has a graphify knowledge graph at graphify-out/.

Rules:
- Before answering architecture or codebase questions, read graphify-out/GRAPH_REPORT.md for god nodes and community structure
- If graphify-out/wiki/index.md exists, navigate it instead of reading raw files
- For cross-module "how does X relate to Y" questions, prefer `graphify query "<question>"`, `graphify path "<A>" "<B>"`, or `graphify explain "<concept>"` over grep — these traverse the graph's EXTRACTED + INFERRED edges instead of scanning files
- After modifying code files in this session, run `graphify update .` to keep the graph current (AST-only, no API cost)
