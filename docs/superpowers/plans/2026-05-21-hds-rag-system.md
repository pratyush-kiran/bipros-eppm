# HDS Knowledge Base Implementation Plan — Master

> **For agentic workers:** REQUIRED SUB-SKILL: Use `superpowers:subagent-driven-development` (recommended) or `superpowers:executing-plans`. Phase files use checkbox (`- [ ]`) syntax. Phases serialize; tracks within a phase parallelize.

**Spec:** [`docs/superpowers/specs/2026-05-21-hds-rag-system-design.md`](../specs/2026-05-21-hds-rag-system-design.md)

**Goal:** Ship the HDS RAG knowledge base v1.0 — platform-wide HDS PDF catalog, async ingestion pipeline, agentic ReAct retrieval with grounded-claim verifier, chat integration with conversation-scoped version selector and citations.

**Architecture:** New Maven module `bipros-hds` (entities, ingestion, retrieval), extensions to `bipros-ai` (tool + orchestrator routing + conversation field) and `bipros-api` (aggregator wiring), plus new admin + chat UI in `frontend/`. pgvector on existing Postgres 17. Docling sidecar for parsing. MinIO for raw-PDF storage via AWS S3 SDK v2. OpenAI-compatible embeddings (`text-embedding-3-large` truncated to 1536 dims) via existing provider. BGE-reranker-v2-m3 cross-encoder.

**Tech stack:** Spring Boot 3.5 / Java 23 (Maven multi-module), Spring Data JPA, pgvector, PostgreSQL 17, Reactor (Flux for SSE), MinIO (S3 SDK v2), AWS SDK v2 multipart, Docling-serve (Python sidecar in Docker), Next.js 16 / React 19 / TypeScript / Tailwind, pnpm, Playwright e2e.

---

## Phase order and dispatch matrix

| Phase | Sub-plan | Tracks | Parallelism | Verify gate before next |
|---|---|---|---|---|
| 0 — Foundation | [`2026-05-21-hds-phase0-foundation.md`](2026-05-21-hds-phase0-foundation.md) | 1 (serial) | none | `mvn install -pl bipros-hds -am -DskipTests`; `docker compose up -d` reaches healthy |
| 1 — Domain + infra clients | [`2026-05-21-hds-phase1-domain-infra.md`](2026-05-21-hds-phase1-domain-infra.md) | 3 | full (separate dirs) | `mvn test -pl bipros-hds` green |
| 2 — Application services | [`2026-05-21-hds-phase2-services.md`](2026-05-21-hds-phase2-services.md) | 3 | full (separate packages) | `mvn test -pl bipros-hds` green |
| 3 — REST + AI tool | [`2026-05-21-hds-phase3-rest-ai.md`](2026-05-21-hds-phase3-rest-ai.md) | 3 | full | `mvn test -pl bipros-api -am`; live `/v1/ai/chat` curl with HDS scope returns answer |
| 4 — Frontend | [`2026-05-21-hds-phase4-frontend.md`](2026-05-21-hds-phase4-frontend.md) | 3 | full | `pnpm typecheck && pnpm lint && pnpm build` green; visual smoke in dev server |
| 5 — End-to-end + handoff | [`2026-05-21-hds-phase5-verify.md`](2026-05-21-hds-phase5-verify.md) | 1 | none | Manual smoke + Playwright happy path |

**Verify rule (memory: `[[feedback_parallel_agent_phases]]`)**: between every two phases I (the dispatching session) run the gate command myself and only fan out the next phase after it passes.

---

## Agent-dispatch contract (passed to each track agent)

When dispatching, every track agent receives:

1. **Project root**: `/Volumes/Java/Projects/bipros-eppm`
2. **Their phase file**: e.g., `docs/superpowers/plans/2026-05-21-hds-phase1-domain-infra.md`
3. **Their track letter**: A / B / C
4. **File-ownership rule**: an agent only edits files listed under its own track. Conflicts mean re-plan, not improvise.
5. **Commit cadence**: one commit per top-level task within the track. Commit messages prefixed `feat(hds): <track> <task>`.
6. **No cross-track merges**: when the agent finishes, it leaves its branch state alone; I merge into the integration branch.

---

## Branch + worktree strategy

All work happens on the current branch `hemendra-pilot-e2e-and-rbac-fixes` unless the user requests a feature branch (memory: user has explicitly partitioned work this way before). If parallel agents share files, dispatch them serially within that phase. The file-ownership matrix in each phase file is engineered so no two tracks touch the same file.

---

## Phase 0 summary — Foundation

Single agent. Bootstraps the new module, schema, docker services, and config. ~20 small tasks. ~30–40 min wall time.

## Phase 1 summary — Domain + infra clients

Three parallel agents, file-ownership-partitioned by directory:
- **Track A** — `bipros-hds/src/main/java/com/bipros/hds/domain/**` + repo tests
- **Track B** — `bipros-hds/src/main/java/com/bipros/hds/infrastructure/{docling,storage,embedding,reranker}/**` + tests
- **Track C** — `bipros-hds/src/main/java/com/bipros/hds/infrastructure/retrieval/**` + RRF utility + tests

## Phase 2 summary — Application services

Three parallel agents:
- **Track A** — `bipros-hds/src/main/java/com/bipros/hds/application/ingestion/**` (ChunkingService, EmbeddingService, IngestionOrchestrator, IngestionWorker, ProgressStreamRegistry)
- **Track B** — `bipros-hds/src/main/java/com/bipros/hds/application/retrieval/**` (RetrievalService = 5-phase ReAct loop, cache layer)
- **Track C** — `bipros-hds/src/main/java/com/bipros/hds/application/library/**` + `query/**` (HdsLibraryService, QueryLogService) + permission codes

## Phase 3 summary — REST + AI tool

Three parallel agents:
- **Track A** — `bipros-hds/src/main/java/com/bipros/hds/api/**` (controllers + DTOs)
- **Track B** — `bipros-ai/src/main/java/com/bipros/ai/tool/hds/**` (SearchHdsStandardsTool + prompt templates)
- **Track C** — `bipros-ai/src/main/java/com/bipros/ai/chat/AiConversation.java` (add field) + `bipros-ai/src/main/java/com/bipros/ai/orchestrator/AiOrchestrator.java` (deterministic-routing branch) + ChatRequest DTO

## Phase 4 summary — Frontend

Three parallel agents:
- **Track A** — `frontend/src/lib/api/hdsApi.ts` + `frontend/src/app/(app)/admin/hds-library/**`
- **Track B** — `frontend/src/components/ai/HdsScopeChip.tsx`, `HdsScopeSelectorModal.tsx`, modifications to `AiChatPanel.tsx` chip slot + request wiring
- **Track C** — `frontend/src/components/ai/HdsCitationCard.tsx`, citation renderer in `AiChatPanel.tsx` answer body, progress-label map extension

## Phase 5 summary — End-to-end + handoff

Single agent. Spins up backend + docling + frontend, uploads a small test PDF, waits for INDEXED, runs a query, asserts citations resolve. Records any gaps as follow-up tasks; commits a summary note.

---

## Open questions (from spec §13 — resolve before or during execution)

1. OpenAI embeddings tier on the configured `LlmProviderConfig` — confirm.
2. Default `HDS_LIBRARY.READ` assignment — all roles or gated.
3. New-conversation default scope — empty or pre-select all indexed.
4. Discipline taxonomy values — confirm match with engineering taxonomy.

Phase 0 includes a question-stub block; if user hasn't resolved, agent proceeds with the spec defaults.

---

## How to start (for the dispatching session)

1. Read the phase 0 file end-to-end.
2. Run phase 0 yourself (single agent, sequential) OR dispatch one agent with phase 0 as its sole job. Verify gate passes.
3. For phase 1+: launch 3 parallel agents in one Agent-tool message, each scoped to one track file path within the phase file. Wait for all three.
4. Run the phase's verify gate yourself.
5. Repeat for phases 2, 3, 4.
6. Run phase 5 as a single agent or interactively.

The file-ownership matrices are designed so the three agents never collide on a single file inside the same phase.
