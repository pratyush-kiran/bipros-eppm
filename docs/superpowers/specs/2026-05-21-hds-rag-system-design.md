# HDS Knowledge Base — RAG + Agentic Architecture

**Status**: Design  
**Date**: 2026-05-21  
**Owner**: hemendra  
**Target release**: v1.0

---

## 1. Problem & goals

The platform needs an AI-powered knowledge system that answers questions strictly from uploaded Highway Design Standard (HDS) documents. Users select one or more HDS versions, ask natural-language questions, and receive grounded answers with verifiable citations. The AI must never use general/world knowledge — only the selected document content.

### In-scope (v1.0)
- Admin upload + version management for HDS publications (PDFs up to ~1 GB each).
- Background ingestion pipeline: parse → chunk → embed → index.
- Hybrid retrieval (BM25 + vector) + cross-encoder reranking.
- Agentic ReAct retrieval loop with self-verification.
- Strict grounding (no out-of-scope context, mandatory citations, verifier pass).
- Chat integration with version selector and citation rendering.
- Citation cards showing section path, page number, and excerpt.

### Out of scope (v1.0)
- In-browser PDF viewer with page deep-linking (deferred to v1.1).
- Per-project HDS subscription (deferred to v2.0).
- Multi-language HDS support (English only for v1).
- OCR for purely scanned PDFs without text layer (Docling does layout-OCR for tables/figures; full-page OCR fallback is v2.0).
- Cross-version diff view (v2.0).

### Success criteria
- An admin can upload an HDS PDF and within ~2 hours it is queryable.
- A user with `HDS_LIBRARY.READ` can select 1..N indexed versions, ask a question, and receive:
  - an answer that cites at least one chunk per factual claim, OR
  - the exact response `"I don't see that in the selected HDS documents."`
- Verifier rejects ungrounded claims; either retry succeeds or the system returns the safe-fail response.
- Citations link to the correct section path and page number on the source PDF.

---

## 2. Locked-in design decisions

| Question | Decision | Rationale |
|---|---|---|
| Library scope | Platform-wide shared catalog | Standards are typically authority-issued and shared across projects. Avoids storage duplication. |
| Vector store | pgvector on existing Postgres 17 | No new infra service. Scale (<20 volumes) sits well inside pgvector's HNSW sweet spot. |
| PDF parser | Docling sidecar (self-hosted) | Open-source, structured output (headings, tables, page numbers), no per-page cloud cost. |
| Retrieval depth | Full agentic ReAct + grounded-claim verifier | Stated requirement: minimal hallucination on engineering specs. |
| Embedding model | `text-embedding-3-large` truncated to 1536 dims | Matryoshka-trained; ~98% of full-dim quality; HNSW supported out-of-the-box. |
| Reranker | BGE-reranker-v2-m3 (open source) | Strong cross-encoder, CPU-runnable, no third-party API dependency. |
| Sparse retrieval (BM25) | Postgres `tsvector` + GIN index | Built-in, no new infra. ParadeDB `pg_search` is a v1.2 swap candidate. |
| File storage | MinIO (S3-compatible, already in compose) | Multipart streaming for 1GB uploads; presigned URLs to Docling and PDF viewer. |
| LLM provider | Existing OpenAI-compatible provider (`bipros-ai/provider/`) | Reuses the encrypted-key infra and chat orchestrator. |

---

## 3. Architecture overview

### 3.1 Module layout

```
backend/
├── bipros-hds/                     ← NEW module (depends on bipros-common)
│   ├── api/                        REST controllers (admin + query-side)
│   ├── application/                HdsLibraryService, IngestionOrchestrator,
│   │                               RetrievalService
│   ├── domain/                     HdsDocument, HdsVersion, HdsChunk,
│   │                               HdsIngestionJob, HdsQueryLog
│   └── infrastructure/             DoclingClient (HTTP),
│                                   EmbeddingClient (delegates to bipros-ai provider),
│                                   PgVectorRepository,
│                                   MinioStorageAdapter
│
├── bipros-ai/                      ← EXTENDED
│   ├── tool/hds/                   SearchHdsStandardsTool,
│   │                               CompareHdsRevisionsTool (optional)
│   └── chat/                       Conversation entity gains hds_version_ids JSONB
│
└── bipros-api/                     ← Aggregator: component-scan picks up bipros-hds

docker/
└── init-schemas.sql                ← ADD `hds` schema

docker-compose.yml                  ← ADD docling-serve sidecar
```

### 3.2 Why `bipros-hds` is a new module rather than extending `bipros-document`

- Different lifecycle: admin-only upload, async ingestion, indexing state machine.
- Different scope: platform-wide, not project-scoped (`bipros-document` is project-scoped).
- Specialized metadata: Volume / Discipline / Revision / Year / Authority / Country.
- Different storage backend: MinIO (S3) vs `bipros-document`'s filesystem.
- Keeping `bipros-document` focused preserves its bounded-context discipline.

### 3.3 Why retrieval tools live in `bipros-ai/tool/hds/`

- The existing orchestrator scans `bipros-ai/tool/**` for `@Component Tool` beans.
- Tools are the public surface from the LLM's perspective; the HDS module is the implementation they call into.
- This matches the existing pattern (e.g., analytics tools that call ClickHouse without owning the warehouse).

---

## 4. Database schema (`hds` schema)

Five tables. Added to `docker/init-schemas.sql` alongside the existing 12 schemas. `ddl-auto: update` in dev evolves the schema; Liquibase changelog under `bipros-api/src/main/resources/db/changelog/` is authoritative for `prod`.

### 4.1 `hds_document` — the logical publication

```sql
id                  uuid PRIMARY KEY
title               varchar(255) NOT NULL          -- "Highway Design Standard, Volume 3 — Geometric Design"
short_code          varchar(32)  UNIQUE NOT NULL   -- "HDS-V3" (used in citation strings)
discipline          varchar(32)  NOT NULL          -- enum: HIGHWAY|BRIDGE|GEOTECH|PAVEMENT|TRAFFIC|DRAINAGE|OTHER
issuing_authority   varchar(255)                   -- "Sultanate of Oman, MoT"
country             char(2)                        -- ISO-3166 alpha-2
description         text
created_at          timestamptz DEFAULT now()
updated_at          timestamptz DEFAULT now()
created_by          uuid                           -- FK to user (no DB-level constraint, follows existing pattern)
```

### 4.2 `hds_version` — a specific revision

```sql
id                       uuid PRIMARY KEY
hds_document_id          uuid NOT NULL REFERENCES hds.hds_document(id) ON DELETE CASCADE
version_label            varchar(64)  NOT NULL    -- "Rev 2.1"
revision_year            int
effective_date           date
file_name                varchar(512)
file_size_bytes          bigint
file_sha256              char(64) UNIQUE          -- dedupe identical bytes
storage_key              varchar(512)             -- MinIO object key
page_count               int
status                   varchar(16) NOT NULL DEFAULT 'PENDING'
                                                  -- enum: PENDING|PARSING|CHUNKING|EMBEDDING|INDEXED|FAILED
indexing_progress_pct    int NOT NULL DEFAULT 0
indexing_error           text
chunk_count              int
uploaded_by              uuid
uploaded_at              timestamptz DEFAULT now()
indexed_at               timestamptz
UNIQUE (hds_document_id, version_label)
```

### 4.3 `hds_chunk` — the searchable unit (one row per chunk)

```sql
id                  uuid PRIMARY KEY
hds_version_id      uuid NOT NULL REFERENCES hds.hds_version(id) ON DELETE CASCADE
chunk_index         int  NOT NULL                  -- order within version
page_start          int  NOT NULL
page_end            int  NOT NULL
section_path        text NOT NULL                  -- "Vol 3 > 4 Cross Section > 4.3 Shoulder Width"
section_number      varchar(32)                    -- "4.3"
chunk_type          varchar(16) NOT NULL           -- enum: TEXT|TABLE|FIGURE_CAPTION|FORMULA|LIST_ITEM
content             text NOT NULL                  -- markdown content
content_tokens      int                            -- estimated token count
embedding           vector(1536) NOT NULL          -- pgvector; HNSW indexed
tsv                 tsvector GENERATED ALWAYS AS
                    (to_tsvector('english', content)) STORED

INDEX hds_chunk_embedding_hnsw_idx
      USING hnsw (embedding vector_cosine_ops)
INDEX hds_chunk_tsv_gin_idx       USING gin   (tsv)
INDEX hds_chunk_version_chunk_idx           (hds_version_id, chunk_index)
INDEX hds_chunk_section_path_idx            (section_path text_pattern_ops)
```

**HNSW parameters**: `m=16, ef_construction=64` (pgvector defaults — sufficient for ≤ 2M chunks). Tune `ef_search` at query time (default 40, raise to 80 for higher recall).

### 4.4 `hds_ingestion_job` — async pipeline state (resumable)

```sql
id                  uuid PRIMARY KEY
hds_version_id      uuid NOT NULL REFERENCES hds.hds_version(id) ON DELETE CASCADE
stage               varchar(16) NOT NULL           -- PARSING|CHUNKING|EMBEDDING|INDEXING|COMPLETE|FAILED
progress_pct        int  NOT NULL DEFAULT 0
error_message       text
attempt_count       int  NOT NULL DEFAULT 0
started_at          timestamptz DEFAULT now()
completed_at        timestamptz
last_heartbeat_at   timestamptz                    -- worker updates every 10s; staleness detection
worker_id           varchar(64)                    -- node hostname for distributed setups
```

### 4.5 `hds_query_log` — audit + observability (drives later tuning)

```sql
id                       uuid PRIMARY KEY
user_id                  uuid
conversation_id          uuid
query_text               text NOT NULL
selected_version_ids     uuid[]
retrieved_chunk_ids      uuid[]
answer_text              text
citations                jsonb              -- array of citation objects (as returned to client)
duration_ms              int
token_usage              jsonb              -- { plan, examine, draft, verify } breakdowns
verifier_passed          boolean
rounds                   int                -- ReAct iterations consumed
created_at               timestamptz DEFAULT now()

INDEX hds_query_log_user_created_idx (user_id, created_at DESC)
```

---

## 5. Ingestion pipeline

### 5.1 Flow

```
Admin uploads PDF (multipart, streamed) ──▶ MinIO key /hds/{versionId}/raw.pdf
                                       └──▶ INSERT hds_version (status=PENDING)
                                       └──▶ INSERT hds_ingestion_job (stage=PARSING)

  IngestionWorker (@Scheduled, single instance per node, advisory lock)
   ┌──────────────────────────────────────────────────────────────────┐
   │ Stage 1 PARSING  (~15–30 min for 1GB)                            │
   │   POST docling-serve:5001/v1/convert  with presigned MinIO URL   │
   │   Receives JSON: pages, headings, tables, figures, page numbers  │
   │   Streams page-level progress 0–60%                              │
   │                                                                  │
   │ Stage 2 CHUNKING  (~1–2 min)                                     │
   │   Walk Docling AST; emit hds_chunk rows (no embeddings yet)      │
   │   Rules:                                                         │
   │     • split at heading boundaries (never cross sections)         │
   │     • token cap 800, overlap 10%                                 │
   │     • tables intact (1 chunk per table, chunk_type=TABLE)        │
   │     • figures: caption + reference text → FIGURE_CAPTION chunk   │
   │     • section_path = full heading ancestry as breadcrumb         │
   │   progress 60–70%                                                │
   │                                                                  │
   │ Stage 3 EMBEDDING  (~60–90 min for 200k chunks)                  │
   │   Batch 100 chunks → OpenAI embeddings API (dimensions=1536)     │
   │   Concurrency 4, exp-backoff on 429                              │
   │   Write embeddings back to hds_chunk in batches of 500           │
   │   progress 70–99%                                                │
   │                                                                  │
   │ Stage 4 INDEX FINALIZE                                           │
   │   ANALYZE hds_chunk;                                             │
   │   hds_version.status = INDEXED, chunk_count, indexed_at = now()  │
   │   hds_ingestion_job.stage = COMPLETE                             │
   │   progress 100%                                                  │
   └──────────────────────────────────────────────────────────────────┘
```

### 5.2 Resumability

- Each stage commits its progress to `hds_ingestion_job` before moving on.
- `last_heartbeat_at` is updated every 10s by the running worker.
- On application startup, the worker sweeps for jobs with `last_heartbeat_at < now() - 60s`, resets them to the start of their current stage, and re-queues.
- An advisory Postgres lock (`pg_try_advisory_lock`) ensures at most one worker per node consumes a job (safe under multi-instance deployment).

### 5.3 Idempotency

- The SHA-256 of the uploaded PDF is checked at upload time against `hds_version.file_sha256` (UNIQUE).
- Identical re-upload returns the existing version row; no re-ingestion. Admin gets a `409 Conflict` with the existing version ID.

### 5.4 Failure handling

- Stage-level failures set `hds_ingestion_job.stage = FAILED` and `error_message`.
- Admin UI exposes "Retry from last stage" → resets `stage` to last incomplete and re-enqueues.
- Hard failure (PDF unparseable, embedding API down >1h): `hds_version.status = FAILED` exposed in admin UI; version remains hidden from selector until retried.

### 5.5 Docker addition

```yaml
docling:
  image: quay.io/docling-project/docling-serve:latest
  ports: ["5001:5001"]
  environment:
    DOCLING_SERVE_ENABLE_UI: "false"
  deploy:
    resources:
      limits:
        memory: 4G
  restart: unless-stopped
```

### 5.6 Resource & cost estimates (per 1GB PDF, ~5k–10k pages, ~150k chunks)

| Resource | Estimate |
|---|---|
| Wall time (parsing) | 15–30 min on a single CPU container with 4 GB RAM |
| Wall time (embedding) | 60–90 min (OpenAI tier-1 rate limits dominate) |
| Wall time (total) | ~1.5–2 hours |
| OpenAI embedding cost | ~$1.50–$3.00 per 1GB doc, one time |
| Postgres storage for chunks | ~1.2 GB (1536 × float4 × 150k ≈ 920 MB embeddings + text) |

---

## 6. Retrieval tool (`SearchHdsStandardsTool`)

Registered as a `@Component` `Tool` in `bipros-ai/tool/hds/`. Invoked by the existing chat orchestrator when a question is routed to HDS (orchestrator routing logic discussed in §8.1).

### 6.1 Tool I/O contract

```jsonc
// input
{
  "question": "minimum shoulder width on primary rural roads",
  "selected_version_ids": ["uuid-1", "uuid-2"],   // 1..N versions; required
  "max_rounds": 2                                  // optional, default 2
}

// output
{
  "answer": "Per HDS-V3 Rev 2.1 §4.3.2, the minimum shoulder width on a primary rural road is 3.0 m [c1]. In Rev 1.0 the same parameter was 2.5 m [c2].",
  "citations": [
    {
      "marker": "c1",
      "chunk_id": "uuid",
      "version_id": "uuid",
      "version_label": "HDS-V3 Rev 2.1",
      "section_path": "Vol 3 > 4 Cross Section > 4.3.2 Shoulder Width",
      "page_start": 87,
      "page_end": 88,
      "excerpt": "Primary rural shoulder width shall be a minimum of 3.0 m…"
    }
  ],
  "verifier": { "passed": true, "issues": [] },
  "metadata": {
    "duration_ms": 18432,
    "rounds": 1,
    "input_tokens": 12000,
    "output_tokens": 800
  }
}
```

### 6.2 Five-phase pipeline

```
Phase 1 — PLAN                 1 LLM call (structured JSON output)
  Input:  question + version titles (short_code + label only — no content)
  Output: { is_compound, sub_questions[], search_queries[] }
  Compound detection example:
    "compare Rev 1 vs Rev 2 on shoulder width"
      → sub_questions = ["shoulder width in Rev 1", "shoulder width in Rev 2"]

Phase 2 — RETRIEVE             parallel SQL, NO LLM
  For each search_query in parallel:
    A. Dense: SELECT … FROM hds_chunk
              WHERE hds_version_id = ANY(:selected) AND embedding <=> :q < 0.7
              ORDER BY embedding <=> :q LIMIT 50
    B. Sparse: SELECT … WHERE hds_version_id = ANY(:selected)
                AND tsv @@ plainto_tsquery('english', :q)
                ORDER BY ts_rank(tsv, plainto_tsquery(…)) DESC LIMIT 50
    C. Fuse with Reciprocal Rank Fusion (k=60) → top 50 combined
    D. Rerank top 50 with BGE-reranker-v2-m3 → top 10
  Dedupe across sub-queries → final candidate set ≤ 20 chunks

Phase 3 — EXAMINE              1 LLM call (structured JSON)
  Input:  question + candidate chunks (section_path + content)
  Output: { sufficient: bool, follow_up_queries: string[] }
  If !sufficient AND round < max_rounds: loop back to Phase 2 with follow-ups

Phase 4 — DRAFT                1 LLM call, STREAMED via SSE
  System: STRICT GROUNDING PROMPT (see §6.4)
  User:   question + numbered chunks [c1]…[cN]
  Output: markdown answer with inline [cN] citations, streamed

Phase 5 — VERIFY               1 LLM call (structured JSON)
  Input:  draft answer + only the chunks actually cited in the draft
  Output: { passed: bool, issues: [{claim, citation, explanation}] }
  If !passed: 1 retry of Phase 4 with verifier feedback, then SAFE_FAIL if still fails
```

### 6.3 Strict-grounding mechanics (four layers)

1. **Hard scope filter at retrieval** — every SQL retrieval includes `WHERE hds_version_id = ANY(:selected_version_ids)`. No out-of-scope chunk can ever enter the LLM context. This is the most important layer.

2. **Empty-retrieval short-circuit** — if Phase 2 returns zero chunks above the similarity floor (0.3 cosine), the tool skips Phases 3–5 and returns the canonical safe-fail string. No LLM call that could hallucinate.

3. **System prompt** (pinned text, no per-call variation):
   > You are a Highway Design Standards lookup assistant. Answer ONLY using the numbered chunks provided. Every factual claim MUST end with a citation `[cN]` matching one of the provided chunks. If the answer is not in the provided chunks, reply exactly: "I don't see that in the selected HDS documents." Do NOT use any general engineering knowledge.

4. **Verifier pass (Phase 5)** — independent LLM call that re-reads each cited chunk and grades whether the claim is supported. Catches residual leakage that the prompt missed.

### 6.4 SSE progress events

The tool emits progress events compatible with the existing `TOOL_PROGRESS_LABELS` map in `AiChatPanel.tsx`. New labels to add:

| Event payload | Frontend label |
|---|---|
| `search_hds_standards: planning` | "Planning HDS retrieval…" |
| `search_hds_standards: retrieving (round N of M)` | "Searching HDS standards…" |
| `search_hds_standards: drafting answer` | "Drafting answer…" |
| `search_hds_standards: verifying grounding` | "Verifying citations…" |
| `tool_complete` | citations attached to message |

### 6.5 Caching layer

- Redis key: `hds:qa:{sha256(question)}:{sha256(sorted_selected_version_ids)}`
- Value: `{ answer, citations, verifier }` serialized JSON
- TTL: 1 hour
- Invalidation: any version included in the key, on re-indexing, fires a Redis `KEYS` sweep (use Redis Sets keyed by `version_id` → cache keys for O(1) invalidation).

### 6.6 Latency budget

| Phase | Typical | Notes |
|---|---|---|
| Plan | 1.5 s | structured output, small prompt |
| Retrieve + rerank | 0.5 s | pgvector HNSW + BGE-reranker (CPU) |
| Examine | 2 s | sees full chunk content |
| Draft (first token) | 1 s | streamed; user sees motion quickly |
| Draft (full) | 5–8 s | depends on answer length |
| Verify | 2 s | structured output |
| **Total (1 round)** | **12–18 s** | |
| **Total (2 rounds)** | **20–30 s** | compound / comparative questions |

### 6.7 Per-query cost (typical)

| LLM call | Tokens (in/out) | Cost @ gpt-4o-mini-ish pricing |
|---|---|---|
| Plan | 500 / 200 | ~$0.002 |
| Examine | 4,000 / 200 | ~$0.012 |
| Draft | 6,000 / 600 | ~$0.020 |
| Verify | 2,000 / 300 | ~$0.008 |
| **Per query** | | **~$0.04–$0.10** |

---

## 7. REST API

All paths under `/v1/hds/`. Standard `ApiResponse<T>` envelope.

### 7.1 Admin endpoints

| Method | Path | Permission | Purpose |
|---|---|---|---|
| POST | `/v1/hds/admin/documents` | `HDS_LIBRARY.CREATE` | Create a publication (metadata only) |
| GET | `/v1/hds/admin/documents` | `HDS_LIBRARY.READ` | List publications (paginated) |
| PATCH | `/v1/hds/admin/documents/{id}` | `HDS_LIBRARY.UPDATE` | Edit metadata |
| DELETE | `/v1/hds/admin/documents/{id}` | `HDS_LIBRARY.DELETE` | Delete (cascades to versions) |
| POST | `/v1/hds/admin/documents/{id}/versions` | `HDS_LIBRARY.CREATE` | Multipart upload (up to 1 GB) — creates `hds_version` and enqueues `hds_ingestion_job` |
| GET | `/v1/hds/admin/versions/{id}` | `HDS_LIBRARY.READ` | Version detail + indexing status |
| GET | `/v1/hds/admin/versions/{id}/progress` | `HDS_LIBRARY.READ` | SSE stream of ingestion progress |
| POST | `/v1/hds/admin/versions/{id}/retry` | `HDS_LIBRARY.UPDATE` | Retry failed ingestion |
| DELETE | `/v1/hds/admin/versions/{id}` | `HDS_LIBRARY.DELETE` | Delete version + cascade chunks |

### 7.2 Query-side endpoints (used by chat UI)

| Method | Path | Permission | Purpose |
|---|---|---|---|
| GET | `/v1/hds/versions` | `HDS_LIBRARY.READ` | List `INDEXED` versions for the selector — returns `{id, short_code, label, year, discipline}` |
| GET | `/v1/hds/chunks/{id}` | `HDS_LIBRARY.READ` | Single chunk + version metadata (citation modal) |
| GET | `/v1/hds/versions/{id}/pdf` | `HDS_LIBRARY.READ` | Presigned MinIO URL scoped to that version (v1.1 PDF viewer) |

### 7.3 Chat-side change (existing `POST /v1/ai/chat[/stream]`)

The chat request gains one optional field:

```diff
 ChatRequest {
   projectId?: string,
   conversationId?: string,
   message: string,
+  hdsVersionIds?: string[]   // when present, orchestrator preferentially routes
+                              // to search_hds_standards tool
 }
```

When `hdsVersionIds` is non-empty, routing is **deterministic, not LLM-decided**: the orchestrator bypasses tool selection and calls `SearchHdsStandardsTool` directly. The tool returns either a cited answer or the canonical safe-fail string — and that response is what the user sees. This is required for strict grounding: if the LLM could route to a non-HDS tool when HDS scope is selected, the user's intent to constrain answers to HDS is violated. When `hdsVersionIds` is absent or empty, the existing routing applies (LLM chooses among existing tools).

### 7.4 Multipart upload mechanics

- Frontend uses `XMLHttpRequest` (or `fetch` with `ReadableStream`) and `Content-Type: multipart/form-data` to stream up to 1 GB.
- Backend uses Spring `MultipartFile` with `spring.servlet.multipart.max-file-size=1100MB` and `max-request-size=1100MB` configured in `application.yml`. (Override existing defaults; current value is 50 MB.)
- The file is streamed directly to MinIO via the S3 multipart API (5 MB part size). The API does NOT buffer the entire file in memory or on local disk.
- After successful upload, the SHA-256 (computed during streaming) is checked for duplicates; on duplicate, MinIO part is aborted/abandoned and the existing version returned.

---

## 8. Frontend (Next.js 16, App Router)

### 8.1 New routes

```
src/app/(app)/admin/hds-library/
├── page.tsx                          publication list + "New publication" CTA
├── new/page.tsx                      create publication form
├── [docId]/
│   ├── page.tsx                      versions list + status badges
│   ├── upload/page.tsx               drag-drop multipart upload + progress
│   └── versions/[verId]/page.tsx     version detail, live SSE indexing
```

Visual reference (admin list view):

```
HDS Library
─────────────────────────────────────────────────────────────────────
 Publication                Discipline   Versions   Last indexed
─────────────────────────────────────────────────────────────────────
 HDS-V3 Geometric Design    HIGHWAY      3          2026-04-12
 HDS-V4 Pavement Design     PAVEMENT     2          2026-03-21
 HDS-V6 Drainage            DRAINAGE     1          ⚠ Failed (retry)
                                                                [+ Add]
```

### 8.2 Chat panel extensions (`AiChatPanel.tsx`)

```
┌──────────────────────────────────────────────────────────────────────┐
│ Chat                                                                 │
│                                                                      │
│ ┌──────────────────────────────────────────────────────────────────┐ │
│ │ 📚 HDS scope: HDS-V3 Rev 2.1, HDS-V4 Rev 1.0   [edit] [clear]   │ │
│ └──────────────────────────────────────────────────────────────────┘ │
│                                                                      │
│ You: minimum shoulder width on primary rural roads?                  │
│                                                                      │
│ Assistant:                                                           │
│   Per HDS-V3 Rev 2.1 §4.3.2, minimum shoulder width on primary       │
│   rural roads is 3.0 m [c1]. Rev 1.0 specified 2.5 m [c2].           │
│                                                                      │
│   ▼ Sources                                                          │
│   [c1] HDS-V3 Rev 2.1 — §4.3.2 Shoulder Width — p. 87                │
│        "Primary rural shoulder width shall be a minimum…"            │
│   [c2] HDS-V3 Rev 1.0 — §4.3.2 — p. 82                               │
└──────────────────────────────────────────────────────────────────────┘
```

- **Version-scope chip**: clicking opens a modal with checkbox multi-select of all `INDEXED` versions. Selection is conversation-scoped (persisted in `Conversation.hds_version_ids`; carries across messages within the conversation). New conversations start with no scope (chip shows "Select HDS sources").
- **Citations**: rendered as numbered badges in the answer; clicking expands the source card inline. In v1.1, "Open" launches a PDF viewer modal at `page_start`.
- **No-scope behavior**: if the user asks a question without any HDS versions selected, the orchestrator falls back to existing tools (no `search_hds_standards` invocation).

### 8.3 New TypeScript API client

`frontend/src/lib/api/hdsApi.ts` — mirrors the existing pattern of `documentApi.ts`. Exports types `HdsDocument`, `HdsVersion`, `HdsChunk`, `IndexingStatus`, and functions for each REST endpoint plus SSE subscriber for ingestion progress.

### 8.4 Conversation model change

The existing `Conversation` entity in `bipros-ai/chat/` gains:

```diff
 conversation {
   id, user_id, project_id, title, created_at, ...
+  hds_version_ids jsonb       -- string[] of selected hds_version.id
 }
```

The chat orchestrator reads this on each request; if non-empty AND the incoming `ChatRequest.hdsVersionIds` is null, it uses the conversation default. The request field always takes precedence when present.

---

## 9. Permissions (RBAC)

Four new permission codes added to the existing matrix (memory: `[[dev_rbac_layout]]` — 22 roles, 76 codes today).

| Code | Default-assigned roles |
|---|---|
| `HDS_LIBRARY.READ` | All authenticated users (added to the base role) |
| `HDS_LIBRARY.CREATE` | `ADMIN`, `PORTFOLIO_MANAGER` |
| `HDS_LIBRARY.UPDATE` | `ADMIN`, `PORTFOLIO_MANAGER` |
| `HDS_LIBRARY.DELETE` | `ADMIN` |

Enforced via the existing `@PreAuthorize("hasPermission(null, '<code>')")` pattern. The tool-level guard `SearchHdsStandardsTool` re-checks `HDS_LIBRARY.READ` before executing (defense-in-depth at the AI tool boundary).

---

## 10. Configuration

New `application.yml` keys (under `bipros.hds.*`):

```yaml
bipros:
  hds:
    storage:
      bucket: hds                          # MinIO bucket
      multipart-part-size-mb: 5
    docling:
      url: ${DOCLING_URL:http://docling:5001}
      timeout-minutes: 60
    embedding:
      model: text-embedding-3-large
      dimensions: 1536
      batch-size: 100
      concurrency: 4
    retrieval:
      similarity-floor: 0.30
      bm25-top-k: 50
      vector-top-k: 50
      rerank-top-k: 10
      max-chunks-per-query: 20
      max-rounds: 2
      cache-ttl-seconds: 3600
    verifier:
      max-retries: 1
```

Environment-overridable. The retrieval thresholds are the primary tuning knobs.

---

## 11. Testing strategy

### 11.1 Unit tests
- **Chunker**: feed synthetic Docling AST, assert chunk boundaries respect headings, table integrity, token caps, overlap correctness, section_path construction.
- **Retrieval SQL**: assert hard scope filter rejects out-of-scope chunks; floor threshold short-circuits empty results.
- **RRF fusion**: assert reciprocal rank fusion math against a fixture.
- **Verifier prompt parser**: feed canned verifier JSON outputs, assert correct pass/fail handling.

### 11.2 Integration tests (using existing `@SpringBootTest` pattern in `bipros-api`)
- **Ingestion happy path**: upload a small synthetic PDF, drive `IngestionWorker` synchronously, assert `hds_chunk` rows + final `INDEXED` status.
- **Ingestion crash recovery**: kill mid-stage, restart worker, assert it resumes from last incomplete stage.
- **Idempotency**: re-upload identical SHA, assert `409 Conflict` and zero new chunks.
- **Strict grounding**: configure a fixture with a known chunk; query for something NOT in that chunk; assert the canonical safe-fail string and zero LLM calls past Phase 2.
- **Permission gates**: assert each endpoint rejects users without the required code.

### 11.3 End-to-end (Playwright, frontend repo)
- Admin uploads a small PDF (~10 pages of sample HDS text), polls ingestion via SSE, asserts INDEXED status.
- User opens chat, selects the indexed version, asks a question whose answer is in the doc, asserts citation badges appear and source card expands to the correct section/page.
- User asks an out-of-scope question, asserts the safe-fail string.

### 11.4 Eval harness (offline, runs ad hoc)
- Hand-curated golden set: 50 question/expected-answer/expected-chunk triples covering direct lookups, table queries, cross-section references, multi-version comparisons, and out-of-scope questions.
- Reports recall@10 of the gold chunks, verifier pass rate, and hallucination rate (claims not present in cited chunks, judged by a separate model).
- Run before each retrieval-prompt or chunking change to detect regression.

---

## 12. Risks & mitigations

| Risk | Severity | Mitigation |
|---|---|---|
| Docling fails on pathological PDFs (encrypted, scanned, broken structure) | Medium | `FAILED` status + admin retry UI; fall back to PDFBox text extraction as a v1.1 escape hatch; document supported PDF profiles |
| OpenAI embedding rate-limits stall ingestion for hours | Medium | Worker uses exp-backoff + persists progress, so it survives extended pauses; document tier-1 expected time of 1.5–2 hours |
| pgvector HNSW build time becomes painful at 2M+ chunks | Low (small scale) | At v1 scale (<2M chunks) HNSW build is minutes, not hours; if v1.2 grows, partition `hds_chunk` per `hds_document_id` |
| Verifier loops or false-rejects valid answers | Medium | Verifier capped at 1 retry → SAFE_FAIL; eval harness measures false-reject rate; tune the verifier prompt over the first month |
| 1GB multipart upload times out at proxy/CDN layer | Medium | Document required `client_max_body_size` for the deployment's reverse proxy; provide chunked-upload guidance (S3 multipart from browser is a v1.1 option) |
| LLM still leaks general knowledge despite all four layers | Medium | Verifier is the last line of defense; eval harness specifically tests this with adversarial out-of-corpus questions and tracks hallucination rate over time |
| react-pdf chokes on 1GB PDFs in v1.1 PDF viewer | Low (deferred) | v1.1 only; can use server-rendered page images if needed |
| Maven stale M2 jar gotcha when adding new module (memory: `[[dev_maven_stale_m2_gotcha]]`) | Low | Always build with `mvn -pl bipros-api -am` or run `mvn install` after adding `bipros-hds` |

---

## 13. Open questions (resolve before plan)

- **Embedding provider account/tier**: confirm the OpenAI account configured in `LlmProviderConfig` has the embeddings endpoint enabled and is at least tier-1 (otherwise ingestion latency expands).
- **Default `HDS_LIBRARY.READ` assignment**: should it go to *all* roles by default, or gated behind one of the existing role permissions (e.g., users with any project access)?
- **Conversation default scope**: when user creates a new conversation, should we pre-select all indexed versions, or start empty? (Current spec assumes empty; debatable.)
- **Discipline taxonomy**: confirm the discipline enum values (`HIGHWAY|BRIDGE|GEOTECH|PAVEMENT|TRAFFIC|DRAINAGE|OTHER`) match the engineering team's standard taxonomy or need extension.

---

## 14. Rollout phasing

| Phase | Scope | Notes |
|---|---|---|
| **v1.0** | Upload, ingest, retrieve, chat integration with text citations (page number + excerpt). Permission codes + admin UI + version selector + verifier. | Meets the stated requirements. |
| **v1.1** | In-browser PDF viewer modal with page deep-link; PDFBox text-extraction fallback for problem PDFs. | Polish. |
| **v1.2** | ParadeDB `pg_search` swap for BM25 if recall is weak; per-publication HNSW partitions if chunk count grows. | Optional optimization based on real query logs. |
| **v2.0** | Per-project HDS subscription; cross-version diff view; OCR fallback for scanned-only PDFs; multi-language support. | Future scope. |

Each phase is independently shippable; v1.0 alone meets the requirements stated in the brief.

---

## 15. Memory references

- `[[dev_ai_tool_layout]]` — existing `bipros-ai` tool surface (~70 tools); HDS adds 1–2 tools to that catalog.
- `[[dev_rbac_layout]]` — permission matrix; HDS adds 4 codes.
- `[[dev_maven_stale_m2_gotcha]]` — when adding `bipros-hds`, always use `-am` on first build.
- `[[dev_ai_kek]]` — `BIPROS_AI_KEK` env var is required for the embedding API key decrypt to work; same key as chat.
