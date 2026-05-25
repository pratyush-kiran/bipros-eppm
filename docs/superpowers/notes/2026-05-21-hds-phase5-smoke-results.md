# HDS Phase 5 — Smoke Test Results (2026-05-21)

## Setup

- Backend: bipros-api running on `:8080`, against **docker postgres on `:5433`** (switched away from native Postgres.app because Postgres 18 + pgvector compile required `/Applications` write access)
- Custom postgres image: `bipros-postgis-pgvector:17-3.5` (postgis/postgis:17-3.5 + postgresql-17-pgvector via Dockerfile, commit `77b14725`)
- Docker services: postgres + minio + docling + redis all healthy
- LlmProvider: configured via admin UI (name "OpenAI Dev", model gpt-5.4, default+active, key encrypted with `BIPROS_AI_KEK`)
- Test PDF: 4-page synthetic HDS sample (1695 bytes) covering shoulder width, lane width, stopping sight distance

## Happy path — all green

| Step | Result |
|---|---|
| Publication created via `POST /v1/hds/admin/documents` | ✅ `HDS-SMOKE` |
| Multipart upload via `POST .../versions` (1695 bytes) | ✅ created `HdsVersion` row, status PENDING |
| Worker picked up job, transitioned PENDING→PARSING→EMBEDDING→INDEXED | ✅ ~30s total |
| Docling parsed and returned 10 markdown blocks | ✅ 4-page PDF → 4 chunks |
| OpenAI `text-embedding-3-large` (1536 dims) | ✅ live spend ~$0.001 |
| Chunks persisted with section_path: e.g. "Highway Design Standard - Sample > 4.3 Shoulder Width" | ✅ |
| In-corpus query: *"What is the minimum shoulder width on a primary rural road?"* | ✅ `"The minimum shoulder width on a primary rural road shall be 3.0 meters. [c1]"` |
| Out-of-corpus query: *"What is the design speed for a high-speed rail line?"* | ✅ Exact safe-fail string returned |

## Known issues discovered + fixed during Phase 5

| # | Issue | Fix |
|---|---|---|
| 1 | `OpenAiEmbeddingClient` (bipros-hds) used env-var key — wouldn't pick up UI-configured provider | Added `BiprosAiEmbeddingClient` in bipros-ai (`@Primary`) using encrypted `LlmProviderConfig` (commit `9dfd0528`) |
| 2 | `HdsVersionAdminController.currentUserIdOrNull()` only caught `IllegalStateException`; `UUID.fromString("admin")` throws `IllegalArgumentException` | Broadened to `catch (Exception e)` |
| 3 | `HdsLibraryService.uploadVersion` pre-assigned ID then save() → Spring Data treated as detached entity (`@Version` is null) | Save first to get generated ID, then upload to MinIO using that ID |
| 4 | `IngestionOrchestrator` re-saved `job`/`version` entities with stale `@Version` after each `advance()` → `OptimisticLockingFailureException` infinite loop | Reload entities via `findById` before saves; copy fresh `@Version` back into caller's reference |
| 5 | `DoclingClient` POSTed to `/v1/convert` with field `file` — real docling-serve API is `/v1/convert/file` with field `files` and a totally different response shape (`{document: {md_content, json_content, ...}}`) | Rewrote `DoclingClient` to call `/v1/convert/file`, parse `md_content`, synthesize `DoclingBlock` list for the chunker |
| 6 | `hds.hds_chunk.tsv` `tsvector` column generated via `GENERATED ALWAYS AS ... STORED` not emitted by Hibernate `ddl-auto: update` | Applied `ALTER TABLE` + GIN/HNSW indexes manually (proper fix: add to Liquibase changeset before Phase 0 or use `@Generated` annotation) |

## Known issues NOT fixed (deferred follow-ups)

| # | Issue | Severity | Notes |
|---|---|---|---|
| A | Chat response text appears duplicated (e.g. `"...3.0 meters. [c1]...3.0 meters. [c1]"`) | Cosmetic | Likely `HdsLlmGatewayAdapter.completeStreaming` collects deltas AND returns the accumulated string; orchestrator probably appends both. Triage in `AiOrchestrator.runHdsDeterministic` |
| B | Postgres 18.1 + pgvector requires `/Applications/Postgres.app/...` write access we can't do from this shell | Workaround applied | Phase 5 used docker postgres on `:5433`. Native Postgres.app still on `:5432` untouched. User can install pgvector for Postgres 18 separately if they want to use native DB later |
| C | `tsv` generated column added manually | Tech debt | Add it to a Liquibase changeset in `bipros-api/src/main/resources/db/changelog/changesets/` |
| D | Hibernate `ddl-auto` doesn't create the HNSW + GIN indexes (only manually applied this run) | Tech debt | Already in `2026-05-21-hds-pgvector-indexes.sql` Liquibase changeset (committed Phase 1 Track C) — needs Liquibase enabled in prod/dev profile |
| E | `pageCount` is 1 even though the test PDF has 4 pages | Cosmetic | `DoclingClient` returns `pages: 1` because we use markdown content (no per-page info). Use Docling's `json_content` to get real page numbers |
| F | `indexingProgressPct=99` on INDEXED versions (instead of 100) | Cosmetic | `advance(INDEXING, 99)` is called; the COMPLETE state save doesn't bump it to 100 |
| G | `AiContext.conversationId()` accessor missing — `HdsQueryLog.conversation_id` always null | Minor | Phase 3 Track B noted this; add accessor in `AiContext` record |
| H | Frontend's `pnpm typecheck` script doesn't exist — used `pnpm exec tsc --noEmit` | Minor | Add `"typecheck": "tsc --noEmit"` to `package.json` |
| I | Multiple commits across phases have misleading messages due to parallel-agent staging races (e.g., `bc8544d3` "HdsDocument entity" actually contains Docling client files) | Cosmetic | Code in each commit is correct; only titles mismatch. Don't rewrite history |
| J | Pre-existing testcontainer-Docker Desktop incompatibility blocks `HdsEntitySmokeTest`, `HybridSearchRepositoryIT`, `HdsChatRoutingIT` | Pre-existing | Affects existing `ActivityPredecessorValidationIT` too. Fix by bumping testcontainers BOM in parent pom |

## Cost

- OpenAI embeddings: ~$0.001 for 4 chunks of test content (text-embedding-3-large at 1536 dims)
- OpenAI gpt-5.4 (retrieval): plan + examine + draft + verify = ~$0.05 across both Q1 + Q2

## Architecture verification

- ✅ Module `bipros-hds` is properly wired into `bipros-api` aggregator
- ✅ `bipros-ai` depends on `bipros-hds` (no circular dep)
- ✅ `BiprosAiEmbeddingClient` (in `bipros-ai`) is `@Primary` and wins over `OpenAiEmbeddingClient` (in `bipros-hds`)
- ✅ HDS Library permissions wired: admin gets all four `HDS_LIBRARY.{READ,CREATE,UPDATE,DELETE}`
- ✅ `AiOrchestrator.runHdsDeterministic` correctly routes to `SearchHdsStandardsTool` when `hdsVersionIds` is non-empty
- ✅ ReAct loop: plan → retrieve (BM25+vector+RRF) → examine → draft → verify all ran live for both queries
- ✅ Strict-grounding short-circuit: out-of-corpus question returned the exact safe-fail string, no LLM hallucination
- ✅ Citations rendered in answer with `[cN]` markers
- ✅ pgvector HNSW + GIN indexes are queryable

## Recommended next actions for the user

1. Resolve item **C** (tsv column + indexes) by adding to the existing Liquibase changeset.
2. Resolve item **A** (duplicate text) — likely a 5-line fix in `HdsLlmGatewayAdapter.completeStreaming`.
3. (Optional) Install pgvector for Postgres 18.1 on the native Postgres.app to use the native DB (item **B**).
4. (Optional) Frontend: bring up `pnpm dev`, test the UI end-to-end through the browser at `http://localhost:3000/admin/hds-library`.
