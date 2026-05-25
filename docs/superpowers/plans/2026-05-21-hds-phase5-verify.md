# HDS Phase 5 — End-to-End Verification

> **Single agent, sequential.** Phases 0–4 must be complete. This is the moment we prove the system works against real services.

**Goal:** A real HDS PDF (small test doc) is uploaded, ingested, queried, and the answer carries verifiable citations. Any gap discovered here is recorded as a follow-up task.

---

## Task 5.1 — Bring the full stack up

- [ ] **Step 1: Start backend services**
```bash
docker compose up -d postgres redis minio docling
docker compose ps  # all must report healthy or running
```

- [ ] **Step 2: Start backend**
```bash
(cd backend && mvn install -pl bipros-api -am -DskipTests -q)
(cd backend && mvn spring-boot:run -pl bipros-api)  # leave in foreground
```
In a second shell, wait until you see `Started BiprosApplication` in the log.

- [ ] **Step 3: Start frontend**
```bash
(cd frontend && pnpm dev)  # leave in foreground
```
Browse to http://localhost:3000.

- [ ] **Step 4: Ensure embedding API key is configured**
The OpenAI key for embeddings is the same one the chat orchestrator uses (encrypted in `ai.llm_provider_config`). Confirm:
```bash
psql -h localhost -U bipros -d bipros -c \
  "SELECT name, model FROM ai.llm_provider_config WHERE active = true;"
```
And that `BIPROS_AI_KEK` is set in the backend env (memory: `[[dev_ai_kek]]`):
```bash
env | grep BIPROS_AI_KEK
```
If missing: export it before the backend command in Step 2.

---

## Task 5.2 — Smoke: upload a small HDS PDF

- [ ] **Step 1: Pick a small test PDF**
Use a small (≤ 5 MB) public-domain engineering PDF. Save under `docs/ActualData/hds-smoke-test.pdf` or grab a sample. AASHTO public extracts and FHWA documents are typical fixtures.

If nothing's handy, generate a synthetic one:
```bash
mkdir -p /tmp/hds-test
cat > /tmp/hds-test/sample.md <<'EOF'
# Highway Design Standard - Sample

## 1. Introduction
This document provides minimum geometric design standards for primary rural roads.

## 4. Cross Section
### 4.3 Shoulder Width
The minimum shoulder width on a primary rural road shall be 3.0 m. On secondary roads the minimum is 2.0 m.

### 4.4 Lane Width
The minimum lane width shall be 3.65 m for design speeds above 80 km/h.
EOF
# If pandoc is available:
which pandoc && pandoc /tmp/hds-test/sample.md -o /tmp/hds-test/sample.pdf
ls -la /tmp/hds-test/sample.pdf
```

- [ ] **Step 2: Log in as admin and create a publication**

In the browser, go to http://localhost:3000/admin/hds-library, click **+ New publication**, fill in:
- Title: `HDS Smoke Test`
- Short code: `HDS-TEST`
- Discipline: `HIGHWAY`
- Country: `OM`

Submit. Note the URL `/admin/hds-library/<docId>`.

- [ ] **Step 3: Upload the test PDF**

Click **+ Upload version**, fill in label `Rev 1.0`, year 2026, choose the PDF. Submit and watch the progress bar.

- [ ] **Step 4: Watch ingestion progress**

After multipart upload completes you're redirected to the version detail page. The SSE stream should drive the progress bar through PARSING → CHUNKING → EMBEDDING → INDEXING → COMPLETE.

For a ~10-page test PDF, ingestion should complete in **under 2 minutes**. If it takes longer than 10 min, investigate:
- Docling logs: `docker compose logs --tail=50 docling`
- Backend logs (look for `IngestionOrchestrator` lines).
- Job state: `SELECT id, stage, progress_pct, error_message FROM hds.hds_ingestion_job;`

- [ ] **Step 5: Confirm INDEXED state**

```bash
psql -h localhost -U bipros -d bipros -c \
  "SELECT version_label, status, page_count, chunk_count, indexed_at FROM hds.hds_version;"
```
Expected: one row with status=INDEXED, chunk_count > 0.

---

## Task 5.3 — Query the indexed corpus

- [ ] **Step 1: In the chat UI, pick the new HDS version as scope**

Open the chat panel. Click the scope chip (it should say "Select HDS sources"), check the "HDS Smoke Test — Rev 1.0" box, confirm.

- [ ] **Step 2: Ask a question that is in the corpus**

Type: `What is the minimum shoulder width on a primary rural road?`

Submit and observe:
- The progress label sequence should appear: "Planning HDS retrieval…", "Searching HDS standards…", "Drafting answer…", "Verifying citations…".
- The answer should mention `3.0 m` and include a `[c1]` marker.
- A "Sources" section should appear below the answer with one citation card pointing to section 4.3.

- [ ] **Step 3: Ask a question that is NOT in the corpus**

Type: `What is the design speed for a high-speed rail line?`

Expected: response is exactly `"I don't see that in the selected HDS documents."` (the canonical safe-fail string from spec §6.3).

- [ ] **Step 4: Ask a question with no scope selected**

Click the chip's `clear` action. Ask the same question. The orchestrator should fall back to existing tools (no `search_hds_standards` invocation).

---

## Task 5.4 — Record findings as a smoke note

**Files:**
- Create: `docs/superpowers/notes/2026-05-21-hds-phase5-smoke-results.md`

- [ ] **Step 1: Capture observations**

```markdown
# HDS Phase 5 — Smoke Test Results (2026-05-21)

## Setup
- Backend: bipros-api running on :8080
- Frontend: pnpm dev on :3000
- Docker services: postgres + redis + minio + docling all healthy
- Test PDF: <path / size / pages>

## Happy path
- [x] / [ ] Publication created
- [x] / [ ] Multipart upload completed (sec, MB)
- [x] / [ ] Ingestion completed (total min)
- [x] / [ ] Status reached INDEXED, N chunks
- [x] / [ ] In-corpus question answered with [cN] citation
- [x] / [ ] Out-of-corpus question returned safe-fail string
- [x] / [ ] No-scope fallback used existing tools

## Gaps / follow-ups
- (any backend issues hit)
- (any frontend issues hit)
- (any prompts that returned bad output)
- (latency / cost observations vs spec budget)
```

- [ ] **Step 2: Commit**
```bash
git add docs/superpowers/notes/2026-05-21-hds-phase5-smoke-results.md
git commit -m "docs(hds): phase 5 smoke test results"
```

---

## Task 5.5 — Optional: add a Playwright e2e for the happy path

**Files:**
- Create: `frontend/e2e/hds-rag.spec.ts`

- [ ] **Step 1: Add a minimal e2e**

```ts
import { test, expect } from "@playwright/test";

test.describe("HDS RAG smoke", () => {
  test("admin uploads + user queries with citations", async ({ page, request }) => {
    test.setTimeout(180_000);
    // Pre-condition: backend has admin/admin123. Adjust to project's seeded credentials.

    // Login
    await page.goto("/auth/login");
    await page.fill('input[name="username"]', "admin");
    await page.fill('input[name="password"]', "admin123");
    await page.click('button[type="submit"]');

    // Create a publication (idempotent attempt)
    await page.goto("/admin/hds-library/new");
    await page.fill('input[placeholder*="Highway Design"]', "HDS E2E");
    await page.fill('input[placeholder="HDS-V3"]', "HDS-E2E-" + Date.now());
    await page.click('button:has-text("Create publication")');

    // Upload
    await expect(page).toHaveURL(/\/admin\/hds-library\/[a-f0-9-]+$/);
    await page.click('a:has-text("Upload version")');
    await page.fill('input[placeholder="Rev 2.1"]', "Rev 1.0");
    const fileChooser = page.waitForEvent("filechooser");
    await page.click('input[type="file"]');
    (await fileChooser).setFiles("frontend/e2e/fixtures/hds-smoke.pdf");
    await page.click('button:has-text("Upload")');

    // Wait for INDEXED (poll the page text)
    await expect(page.locator("text=/Indexed \\d+ chunks/")).toBeVisible({ timeout: 120_000 });
  });
});
```

Note: this skips the chat-side assertions because the chat UI mount path varies; once the assistant route is stable, extend.

- [ ] **Step 2: Run + commit**
```bash
(cd frontend && pnpm test:e2e --grep "HDS RAG smoke" || echo "Failed — log to gaps note")
git add frontend/e2e/hds-rag.spec.ts
git commit -m "test(hds): playwright e2e — admin upload happy path"
```

---

## Phase 5 verify gate

After Tasks 5.1–5.4 are all green:
- Task 5.4's note has zero un-checked happy-path items, OR each unchecked item has a concrete follow-up captured in `docs/superpowers/notes/2026-05-21-hds-phase5-smoke-results.md`.
- `git log --oneline --since=phase-start | grep -c "^[0-9a-f]\+ feat(hds)"` shows the expected commit count (rough sanity).

If something is broken, fix it in this phase (single agent, sequential debugging) rather than spawning new parallel tracks — file-ownership matrices were designed for additive work, not bug-stomping.
