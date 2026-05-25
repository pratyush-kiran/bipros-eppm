# HDS PDF Parser — Developer Test Guide

This document walks a fresh developer through testing the HDS ingestion pipeline after the **`feat(hds): native PDFBox parser for text-extractable PDFs`** change (commit `dc0834e9`).

The change adds a **router** that sends text-extractable PDFs through Apache PDFBox (~200 MB peak memory, bounded by streaming) and falls back to Docling only for image-heavy / scanned PDFs.

## What changed in one sentence

`IngestionOrchestrator` no longer calls `DoclingClient.parse(...)` directly — it calls `RoutingPdfParser.parse(...)`, which decides between **PDFBox** (cheap, text-only) and **Docling** (rich layout, expensive) based on a quick extractability probe.

---

## 1. Prerequisites

You need the full BIPROS dev stack plus two non-default setup steps for HDS:

| Component | Required | Notes |
|---|---|---|
| Docker Desktop | ✅ | Allocate **≥ 8 GiB RAM** to the VM (Settings → Resources → Memory). The Docling fallback path still needs headroom. |
| PostgreSQL 18 | ✅ | The dev stack uses **Postgres.app v18** on `localhost:5432`. |
| pgvector extension for PG 18 | ✅ | Must be installed manually — see §2. |
| `bipros` database with `hds` schema | ✅ | Auto-created by Hibernate `ddl-auto: update` at first boot. |
| `BIPROS_AI_KEK` env var | ✅ | Base64 KEK that decrypts the LLM provider key. Without it `/v1/ai/chat` returns empty text. |
| Docker dependencies | ✅ | docling, MinIO, Redis, ClickHouse from `docker-compose.yml`. |

### 1.1 Verify your Docker Desktop memory

```bash
docker info | grep "Total Memory"
# Total Memory: 10.69GiB   ← anything ≥ 8 GiB is fine for text-mode
```

If you ever fall back to Docling for a large image-heavy PDF, you'll want **24+ GiB**. For text-based PDFs (the common case) 8 GiB is plenty.

---

## 2. Install pgvector for Postgres 18 (one-time setup)

Postgres.app v18 ships the pgvector control file but **not** the `.dylib`. Homebrew's `pgvector` formula only provides binaries for PG 14 and PG 17, so you have to build it yourself for PG 18.

The build directory `/Applications/Postgres.app/Contents/Versions/18/lib/postgresql/` is SIP-protected. The trick is to use Postgres 18's new `extension_control_path` GUC to point at a writable directory in your home folder.

```bash
# Build pgvector
cd /tmp
git clone --depth 1 --branch v0.8.1 https://github.com/pgvector/pgvector.git
cd pgvector
PG_CONFIG=/Applications/Postgres.app/Contents/Versions/18/bin/pg_config make

# Install into a writable directory (since /Applications is SIP-locked)
EXTDIR=$HOME/pg18-extensions
mkdir -p $EXTDIR/lib $EXTDIR/share/extension
cp vector.dylib $EXTDIR/lib/
cp sql/vector--*.sql $EXTDIR/share/extension/
cp vector.control $EXTDIR/share/extension/
# Also copy the upgrade migration scripts that ship with Postgres.app
cp /Applications/Postgres.app/Contents/Versions/18/share/postgresql/extension/vector--*.sql \
   $EXTDIR/share/extension/ 2>/dev/null

# Tell Postgres where to find them — at the database level, so the backend connection
# picks them up automatically.
PSQL=/Applications/Postgres.app/Contents/Versions/18/bin/psql
$PSQL -h localhost -U hemendra -d bipros <<SQL
ALTER DATABASE bipros SET dynamic_library_path = '$EXTDIR/lib:\$libdir';
ALTER DATABASE bipros SET extension_control_path = '$EXTDIR/share/extension:\$system';
SQL

# Now create the extension
$PSQL -h localhost -U hemendra -d bipros -c "CREATE EXTENSION IF NOT EXISTS vector;"

# Verify
$PSQL -h localhost -U bipros -d bipros -c "SELECT '[1,2,3]'::vector;"
#  vector  
# ---------
#  [1,2,3]
```

Replace `hemendra` with your local Postgres superuser. The first two `ALTER DATABASE` statements need superuser privileges; everything else runs as the `bipros` app user.

---

## 3. Apply the missing `tsv` column (one-time setup)

Hibernate's `ddl-auto: update` can't create GENERATED columns, so the BM25 `tsv` column needs to be applied manually the first time you boot:

```bash
PSQL=/Applications/Postgres.app/Contents/Versions/18/bin/psql
$PSQL -h localhost -U bipros -d bipros <<SQL
ALTER TABLE hds.hds_chunk
  ADD COLUMN IF NOT EXISTS tsv tsvector
  GENERATED ALWAYS AS (to_tsvector('english', coalesce(content, ''))) STORED;
CREATE INDEX IF NOT EXISTS idx_hds_chunk_tsv ON hds.hds_chunk USING GIN (tsv);
CREATE INDEX IF NOT EXISTS idx_hds_chunk_embedding ON hds.hds_chunk USING hnsw (embedding vector_cosine_ops);
SQL
```

You only need to do this once per dev DB.

---

## 4. Start the stack

```bash
# Containers (docling, MinIO, Redis, ClickHouse, etc.)
docker compose up -d

# Backend — must export BIPROS_AI_KEK first
export BIPROS_AI_KEK='Vd/RdHKwlLA1vFuDVUr/ou0CMHAsha99Cfi8UXzXUlA='   # dev KEK
cd backend
mvn install -DskipTests        # first time only, or after pulling sibling-module changes
mvn -pl bipros-api spring-boot:run

# Frontend (separate terminal)
cd frontend
pnpm dev
```

**Gotcha:** if you pulled changes that modified sibling modules (e.g. `bipros-evm` or `bipros-common`), you **must** `mvn install` from `backend/` first — `spring-boot:run` reads sibling deps from `~/.m2`, and stale jars cause `ClassNotFoundException` at startup. See `dev_maven_stale_m2_gotcha.md` in your auto-memory for context.

Backend boot should take ~10 s. Log in at <http://localhost:3000> with `admin / admin123`.

---

## 5. Test scenarios

### 5.1 Small text PDF — should route to PDFBox

**Input:** a small (< 10 MB), text-extractable PDF.

```bash
# Use the test PDF the repo ships, or any small text PDF you have.
ls .playwright-mcp/hds-claude-test.pdf   # 953-byte single-page test PDF
```

**Steps:**
1. Navigate to **Admin → HDS Library**.
2. Click on the **Test HDS Smoke Document** publication (or create a new one).
3. Click **Upload version**, drop your PDF, give it a label like `test-1`, year `2026`.
4. Click **Upload & index**.

**Expected:**
- Status walks through `Queued → Parsing PDF → Chunking → Embedding → Indexed` within seconds.
- Backend log shows:
  ```
  Parser routing: avg_chars=… ≥ min=200 → PDFBox (file=…, pages=N)
  PDFBox extraction complete: pages=N, blocks=M
  ```
- **No** memory spike on the docling container (`docker stats bipros-docling --no-stream`).

### 5.2 Large text PDF — force-pdfbox ceiling kicks in

**Input:** a real-world standard, e.g. `docs/ActualData/HDS_Vol 3.pdf` (~350 MB, 546 pages).

**Steps:** same as 5.1, but with the large PDF.

**Expected:**
- Backend log shows:
  ```
  Parser routing: size=352 MB ≥ forcePdfBoxOverMb=100 MB → PDFBox (file=HDS_Vol 3.pdf)
  PDFBox loaded 546 pages from HDS_Vol 3.pdf (369000000 bytes on disk)
  PDFBox extraction complete: pages=546, blocks=5170
  ```
- Total time to **Indexed**: ~15-30 s. Docling memory stays at the idle baseline (~1.4 GiB).
- DB check:
  ```bash
  psql -h localhost -U bipros -d bipros -c \
    "SELECT version_label, status, chunk_count FROM hds.hds_version ORDER BY created_at DESC;"
  ```

### 5.3 Verify grounded retrieval

1. Click the **Ask AI** floating button.
2. Click **📚 Select HDS sources**, tick the version you just uploaded, click **Use selected**.
3. Ask an in-corpus question, e.g.:
   - For the smoke PDF: *"What is the minimum shoulder width on rural arterials?"*
   - For HDS Vol 3: *"What does the standard say about pavement design?"*

**Expected:**
- Answer cites specific sections, e.g. `[c1] VOLUME 3 > EARTHWORKS — p. 12`.
- Section paths use the `§N.N.N Title` hierarchy detected by `PdfTextExtractor`.

### 5.4 Verify off-topic refusal

Ask something unrelated: *"Hello, how are you?"*

**Expected:** `I'm the HDS document assistant. Ask me about content in the selected documents — for example, specific facts from a section, or what topics the documents cover.`

### 5.5 Verify out-of-corpus safe-fail

Ask something not in the document: *"What is the speed limit for high-speed rail in Japan?"*

**Expected:** `I don't see that in the selected HDS documents.`

### 5.6 (Optional) Force Docling fallback

Useful when you want to test the Docling path without an image-heavy PDF.

```bash
# Set the threshold high enough that even small files don't auto-route to PDFBox,
# and lower the size ceiling so the PDFBox forced path doesn't kick in.
export HDS_PARSER_TEXT_MODE=false        # disables text mode entirely
mvn -pl bipros-api spring-boot:run
```

Upload any PDF and watch the log:
```
Parser routing: textModeEnabled=false → Docling (file=…, size=… B)
```

Reset by unsetting `HDS_PARSER_TEXT_MODE` or setting it back to `true`.

---

## 6. Configuration knobs

All under `bipros.hds.parser` in `application.yml`:

```yaml
bipros:
  hds:
    parser:
      text-mode-enabled: true              # master switch
      min-chars-per-page: 200              # ≥ this avg → PDFBox
      probe-sample-size: 5                 # pages sampled for extractability test
      force-pdfbox-over-mb: 100            # any PDF this size+ → PDFBox unconditionally
```

Environment overrides:

| Env var | Maps to | Default |
|---|---|---|
| `HDS_PARSER_TEXT_MODE` | `text-mode-enabled` | `true` |

---

## 7. Inspecting routing decisions

The router logs one line per file at INFO level:

```bash
# Watch the routing decision live
tail -f /tmp/bipros-logs/backend.log | grep -E "Parser routing|extraction complete|HDS retrieval"
```

You'll see one of three forms:

- `Parser routing: avg_chars=N ≥ min=200 → PDFBox` (text-based PDF)
- `Parser routing: size=N MB ≥ forcePdfBoxOverMb=100 MB → PDFBox` (oversize file)
- `Parser routing: avg_chars=N < min=200 → Docling` (image-heavy PDF)
- `Parser routing: textModeEnabled=false → Docling` (manual override)

---

## 8. Common gotchas

| Symptom | Cause | Fix |
|---|---|---|
| `ERROR: type "vector" does not exist` at boot | pgvector not installed | §2 above |
| `column "tsv" does not exist` during chat | `tsv` column not created | §3 above |
| `Connection prematurely closed BEFORE response` on a large PDF | Docling OOM-killed (you ended up on the Docling path) | Confirm `text-mode-enabled: true` and `force-pdfbox-over-mb: 100`; verify in routing log |
| `NoClassDefFoundError: EvmCalculationRepository` at boot | Stale `~/.m2` sibling jar | `mvn install -DskipTests` from `backend/` |
| Status stays `FAILED` after retry | Stale state from previous run | Check `/tmp/bipros-logs/backend.log`; the version's `indexing_error` column has the message |
| Health endpoint returns `DOWN` but app works | A non-essential health check is failing (Redis or ClickHouse) | Cosmetic — login/chat still work |

---

## 9. Running the test suite

```bash
cd backend
mvn -pl bipros-hds test
```

The relevant test is `IngestionOrchestratorTest` which mocks `RoutingPdfParser` and verifies the full pipeline from PARSING → INDEXED. No real PDFs are read in the unit tests.

For integration / smoke tests, use the Playwright suite:

```bash
cd frontend
pnpm test:e2e
```

---

## 10. Where to look in the code

| File | Purpose |
|---|---|
| `backend/bipros-hds/src/main/java/com/bipros/hds/infrastructure/parser/PdfTextExtractor.java` | PDFBox streaming extractor + extractability probe |
| `backend/bipros-hds/src/main/java/com/bipros/hds/infrastructure/parser/RoutingPdfParser.java` | Decision logic between PDFBox and Docling |
| `backend/bipros-hds/src/main/java/com/bipros/hds/application/ingestion/IngestionOrchestrator.java` | Calls `parser.parse(...)` instead of `docling.parse(...)` |
| `backend/bipros-hds/src/main/java/com/bipros/hds/config/HdsProperties.java` | `Parser` config class |
| `backend/bipros-api/src/main/resources/application.yml` | `bipros.hds.parser.*` defaults |
| `docker-compose.yml` | Docling memory cap (12 G — kept as belt-and-suspenders for the rare Docling fallback) |

---

## 11. Reverting to Docling-everywhere (rollback)

If you need to disable the router and use Docling for every PDF (e.g. to debug a Docling-specific issue):

```bash
HDS_PARSER_TEXT_MODE=false mvn -pl bipros-api spring-boot:run
```

Or set `bipros.hds.parser.text-mode-enabled: false` in `application.yml`.

No data migration needed; existing indexed chunks remain queryable either way.
