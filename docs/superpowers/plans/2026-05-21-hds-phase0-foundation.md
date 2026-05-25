# HDS Phase 0 — Foundation

> **Single agent, sequential.** Bootstraps the new `bipros-hds` module, schema, docker services, and configuration. ~30–40 min wall time.

**Goal:** After this phase, `mvn install -pl bipros-hds -am -DskipTests` builds clean and `docker compose up -d` shows MinIO + Docling healthy.

**Verify gate (run after all tasks below):**
```bash
(cd backend && mvn install -pl bipros-hds -am -DskipTests -q)
docker compose up -d docling minio postgres
docker compose ps   # docling and minio must be healthy/running
```

---

## Task 0.1 — Create the Maven module skeleton

**Files:**
- Create: `backend/bipros-hds/pom.xml`
- Create directories: `backend/bipros-hds/src/main/java/com/bipros/hds/{api,application,domain,infrastructure,config}`
- Create: `backend/bipros-hds/src/test/java/com/bipros/hds/.gitkeep`

- [ ] **Step 1: Create module directories**

```bash
mkdir -p backend/bipros-hds/src/main/java/com/bipros/hds/{api/admin,api/dto,application,domain/enums,domain/repo,infrastructure/docling,infrastructure/storage,infrastructure/embedding,infrastructure/reranker,infrastructure/retrieval,config}
mkdir -p backend/bipros-hds/src/test/java/com/bipros/hds
touch backend/bipros-hds/src/test/java/com/bipros/hds/.gitkeep
```

- [ ] **Step 2: Write `backend/bipros-hds/pom.xml`**

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0
                             https://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <parent>
        <groupId>com.bipros</groupId>
        <artifactId>bipros-backend</artifactId>
        <version>0.1.0-SNAPSHOT</version>
    </parent>

    <artifactId>bipros-hds</artifactId>
    <name>bipros-hds</name>
    <description>HDS knowledge base (PDF ingestion, vector retrieval, agentic RAG)</description>

    <dependencies>
        <dependency>
            <groupId>com.bipros</groupId>
            <artifactId>bipros-common</artifactId>
        </dependency>

        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-data-jpa</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-web</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-webflux</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-validation</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-data-redis</artifactId>
        </dependency>
        <dependency>
            <groupId>org.postgresql</groupId>
            <artifactId>postgresql</artifactId>
        </dependency>

        <!-- pgvector JDBC type support -->
        <dependency>
            <groupId>com.pgvector</groupId>
            <artifactId>pgvector</artifactId>
            <version>0.1.6</version>
        </dependency>

        <!-- AWS S3 SDK v2 (matches existing pattern in bipros-integration) -->
        <dependency>
            <groupId>software.amazon.awssdk</groupId>
            <artifactId>s3</artifactId>
        </dependency>

        <dependency>
            <groupId>org.projectlombok</groupId>
            <artifactId>lombok</artifactId>
            <scope>provided</scope>
        </dependency>

        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-test</artifactId>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>org.testcontainers</groupId>
            <artifactId>postgresql</artifactId>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>org.testcontainers</groupId>
            <artifactId>junit-jupiter</artifactId>
            <scope>test</scope>
        </dependency>
    </dependencies>
</project>
```

- [ ] **Step 3: Verify pgvector + AWS SDK versions exist in parent BOM**

Run:
```bash
grep -E "(pgvector|software\.amazon\.awssdk)" backend/pom.xml
```
Expected: either both present (use BOM-managed versions and remove `<version>` from the child), or absent (add explicit versions: `pgvector` 0.1.6 stays as-is; AWS SDK BOM `software.amazon.awssdk:bom:2.27.21` should already be present via `bipros-integration`).

If `aws bom` is not in the parent, add to `backend/pom.xml` `<dependencyManagement>`:
```xml
<dependency>
  <groupId>software.amazon.awssdk</groupId>
  <artifactId>bom</artifactId>
  <version>2.27.21</version>
  <type>pom</type>
  <scope>import</scope>
</dependency>
```

- [ ] **Step 4: Add module to parent `backend/pom.xml`**

Edit `backend/pom.xml`: in the `<modules>` block, add `<module>bipros-hds</module>` alphabetically (after `bipros-evm` or wherever fits).

- [ ] **Step 5: Commit**

```bash
git add backend/bipros-hds/ backend/pom.xml
git commit -m "feat(hds): scaffold bipros-hds maven module"
```

---

## Task 0.2 — Wire `bipros-hds` into `bipros-api`

**Files:**
- Modify: `backend/bipros-api/pom.xml`

- [ ] **Step 1: Add dependency**

Open `backend/bipros-api/pom.xml`. Locate the existing `<dependency>` for `bipros-dbs` (or any sibling), and add immediately after:
```xml
<dependency>
    <groupId>com.bipros</groupId>
    <artifactId>bipros-hds</artifactId>
</dependency>
```

- [ ] **Step 2: Verify aggregator picks up the new module on build**

```bash
(cd backend && mvn install -pl bipros-api -am -DskipTests -q)
```
Expected: BUILD SUCCESS with both `bipros-hds` and `bipros-api` listed in reactor summary.

- [ ] **Step 3: Commit**

```bash
git add backend/bipros-api/pom.xml
git commit -m "feat(hds): wire bipros-hds into bipros-api aggregator"
```

---

## Task 0.3 — Add `hds` schema to init script

**Files:**
- Modify: `docker/init-schemas.sql`

- [ ] **Step 1: Append the schema and pgvector extension**

Open `docker/init-schemas.sql`. After the last `CREATE SCHEMA IF NOT EXISTS ...`, append:
```sql
CREATE SCHEMA IF NOT EXISTS hds AUTHORIZATION bipros;
GRANT ALL ON SCHEMA hds TO bipros;
GRANT USAGE ON SCHEMA hds TO bipros;

-- pgvector extension (idempotent; installs into 'public' but is usable from any schema)
CREATE EXTENSION IF NOT EXISTS vector;
```

- [ ] **Step 2: Apply to running Postgres**

If `bipros-postgres` is the native Homebrew one (memory: `[[dev_dual_postgres]]`):
```bash
psql -h localhost -U bipros -d bipros -f docker/init-schemas.sql
```
Otherwise, drop and recreate the docker postgres container so the init script re-runs:
```bash
docker compose down postgres && docker volume rm bipros-eppm_postgres_data && docker compose up -d postgres
```

- [ ] **Step 3: Verify schema and extension**

```bash
psql -h localhost -U bipros -d bipros -c "\dn hds" -c "\dx vector"
```
Expected: schema `hds` listed; extension `vector` listed.

- [ ] **Step 4: Commit**

```bash
git add docker/init-schemas.sql
git commit -m "feat(hds): add hds schema and pgvector extension"
```

---

## Task 0.4 — Add Docling sidecar to docker-compose

**Files:**
- Modify: `docker-compose.yml`

- [ ] **Step 1: Append the service**

Append the following service to the existing `services:` block in `docker-compose.yml` (alphabetical position is fine; keep it near `minio`):
```yaml
  docling:
    image: quay.io/docling-project/docling-serve-cpu:latest
    container_name: bipros-docling
    ports:
      - "5001:5001"
    environment:
      DOCLING_SERVE_ENABLE_UI: "false"
      DOCLING_SERVE_API_HOST: "0.0.0.0"
    deploy:
      resources:
        limits:
          memory: 4G
    healthcheck:
      test: ["CMD", "curl", "-fsS", "http://localhost:5001/health"]
      interval: 15s
      timeout: 5s
      retries: 10
      start_period: 60s
    restart: unless-stopped
```

- [ ] **Step 2: Pull and start**

```bash
docker compose pull docling
docker compose up -d docling
docker compose logs docling --tail=20
```
Expected: Docling boots, logs include "Uvicorn running on http://0.0.0.0:5001". May take 60–90s on cold pull.

- [ ] **Step 3: Smoke-test the API**

```bash
curl -sS http://localhost:5001/health || echo "no /health route — try root"
curl -sS http://localhost:5001/v1/convert -X POST -H "Content-Type: application/json" -d '{}' | head -c 300
```
Expected: either an HTTP 4xx with a JSON validation error (means the endpoint is reachable) or a 200 health response.

- [ ] **Step 4: Commit**

```bash
git add docker-compose.yml
git commit -m "feat(hds): add docling-serve sidecar to docker-compose"
```

---

## Task 0.5 — Bootstrap MinIO bucket `hds`

**Files:**
- Modify: `scripts/init-minio.sh` (create if absent)
- Modify: `docker-compose.yml` (optional: add `minio-init` one-shot service that creates buckets)

- [ ] **Step 1: Create a one-shot bucket-init script**

Create `scripts/init-minio.sh`:
```bash
#!/usr/bin/env bash
# Ensures MinIO has the required buckets for bipros-eppm.
# Idempotent — safe to re-run.

set -euo pipefail

MINIO_URL="${MINIO_URL:-http://localhost:9000}"
MINIO_ACCESS_KEY="${MINIO_ACCESS_KEY:-minio}"
MINIO_SECRET_KEY="${MINIO_SECRET_KEY:-minio123}"
ALIAS="bipros-local"

if ! command -v mc >/dev/null 2>&1; then
  echo "MinIO client 'mc' not found. Install with: brew install minio/stable/mc"
  exit 1
fi

mc alias set "$ALIAS" "$MINIO_URL" "$MINIO_ACCESS_KEY" "$MINIO_SECRET_KEY"

for bucket in hds; do
  if ! mc ls "$ALIAS/$bucket" >/dev/null 2>&1; then
    mc mb "$ALIAS/$bucket"
    echo "Created bucket: $bucket"
  else
    echo "Bucket already exists: $bucket"
  fi
done
```

- [ ] **Step 2: Make executable and run**

```bash
chmod +x scripts/init-minio.sh
docker compose up -d minio
sleep 5   # let MinIO settle
./scripts/init-minio.sh
```
Expected: "Created bucket: hds" (first run) or "Bucket already exists: hds" (re-run).

- [ ] **Step 3: Commit**

```bash
git add scripts/init-minio.sh
git commit -m "feat(hds): minio bucket bootstrap script"
```

---

## Task 0.6 — Add HDS configuration to `application.yml`

**Files:**
- Modify: `backend/bipros-api/src/main/resources/application.yml`

- [ ] **Step 1: Bump multipart upload limits**

Find the existing `spring:` block. Under `servlet:` or as a new `servlet:` block, ensure:
```yaml
spring:
  servlet:
    multipart:
      enabled: true
      max-file-size: 1100MB
      max-request-size: 1100MB
      file-size-threshold: 10MB    # spool to disk above this
```
If `spring.servlet.multipart` already exists, update existing keys rather than duplicating.

- [ ] **Step 2: Add HDS config block**

At the end of `application.yml` (top-level), add:
```yaml
bipros:
  hds:
    storage:
      bucket: hds
      endpoint: ${MINIO_ENDPOINT:http://localhost:9000}
      access-key: ${MINIO_ACCESS_KEY:minio}
      secret-key: ${MINIO_SECRET_KEY:minio123}
      region: ${MINIO_REGION:us-east-1}
      multipart-part-size-mb: 5
    docling:
      url: ${DOCLING_URL:http://localhost:5001}
      timeout-minutes: 60
    embedding:
      model: text-embedding-3-large
      dimensions: 1536
      batch-size: 100
      concurrency: 4
    reranker:
      enabled: ${HDS_RERANKER_ENABLED:false}
      url: ${HDS_RERANKER_URL:http://localhost:8088}
      top-k: 10
    retrieval:
      similarity-floor: 0.30
      bm25-top-k: 50
      vector-top-k: 50
      max-chunks-per-query: 20
      max-rounds: 2
      cache-ttl-seconds: 3600
    verifier:
      max-retries: 1
    ingestion:
      worker-poll-seconds: 5
      heartbeat-interval-seconds: 10
      stale-job-after-seconds: 60
```

> NB: `reranker.enabled` defaults to `false` so Phase 2 can ship without the BGE service. When false, retrieval skips the cross-encoder step and uses RRF results directly. We toggle on after Phase 5 verification.

- [ ] **Step 3: Commit**

```bash
git add backend/bipros-api/src/main/resources/application.yml
git commit -m "feat(hds): application.yml — multipart 1.1GB + hds config block"
```

---

## Task 0.7 — Define `HdsProperties` configuration class

**Files:**
- Create: `backend/bipros-hds/src/main/java/com/bipros/hds/config/HdsProperties.java`

- [ ] **Step 1: Write the properties class**

```java
package com.bipros.hds.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConfigurationProperties(prefix = "bipros.hds")
@Data
public class HdsProperties {

    private Storage storage = new Storage();
    private Docling docling = new Docling();
    private Embedding embedding = new Embedding();
    private Reranker reranker = new Reranker();
    private Retrieval retrieval = new Retrieval();
    private Verifier verifier = new Verifier();
    private Ingestion ingestion = new Ingestion();

    @Data
    public static class Storage {
        private String bucket;
        private String endpoint;
        private String accessKey;
        private String secretKey;
        private String region;
        private int multipartPartSizeMb;
    }

    @Data
    public static class Docling {
        private String url;
        private int timeoutMinutes;
    }

    @Data
    public static class Embedding {
        private String model;
        private int dimensions;
        private int batchSize;
        private int concurrency;
    }

    @Data
    public static class Reranker {
        private boolean enabled;
        private String url;
        private int topK;
    }

    @Data
    public static class Retrieval {
        private double similarityFloor;
        private int bm25TopK;
        private int vectorTopK;
        private int maxChunksPerQuery;
        private int maxRounds;
        private int cacheTtlSeconds;
    }

    @Data
    public static class Verifier {
        private int maxRetries;
    }

    @Data
    public static class Ingestion {
        private int workerPollSeconds;
        private int heartbeatIntervalSeconds;
        private int staleJobAfterSeconds;
    }
}
```

- [ ] **Step 2: Add a `BiprosHdsAutoConfiguration` marker (component scan trigger)**

Create `backend/bipros-hds/src/main/java/com/bipros/hds/config/BiprosHdsAutoConfiguration.java`:
```java
package com.bipros.hds.config;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.ComponentScan;

@AutoConfiguration
@ComponentScan(basePackages = "com.bipros.hds")
public class BiprosHdsAutoConfiguration {
}
```

Then create `backend/bipros-hds/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`:
```
com.bipros.hds.config.BiprosHdsAutoConfiguration
```

- [ ] **Step 3: Verify wiring**

```bash
(cd backend && mvn install -pl bipros-hds -am -DskipTests -q)
(cd backend && mvn -pl bipros-api spring-boot:run -am) &
# wait ~20s
curl -sS http://localhost:8080/actuator/health || true
```
Boot must succeed. If `actuator` isn't exposed, just check the logs say `Started BiprosApplication`. Kill the bg process.

- [ ] **Step 4: Commit**

```bash
git add backend/bipros-hds/src/main/java/com/bipros/hds/config backend/bipros-hds/src/main/resources/META-INF
git commit -m "feat(hds): HdsProperties + auto-configuration"
```

---

## Task 0.8 — Register new permission codes in RBAC

**Files:**
- Modify: the central permission registry (likely a `RolePermissionMatrix` or enum class in `bipros-common` or `bipros-api`; locate via memory `[[dev_rbac_layout]]`)

- [ ] **Step 1: Locate the permission enum / matrix**

```bash
grep -rl "PROJECT.CREATE" backend/ --include="*.java" | head -5
grep -rl "RolePermission" backend/ --include="*.java" | head -5
```
Pick the file that *defines* the canonical permission set (likely `backend/bipros-common/.../security/Permission.java` or similar). Read it.

- [ ] **Step 2: Add the four HDS codes**

Add to the enum (preserve existing ordering style):
```java
HDS_LIBRARY_READ("HDS_LIBRARY.READ"),
HDS_LIBRARY_CREATE("HDS_LIBRARY.CREATE"),
HDS_LIBRARY_UPDATE("HDS_LIBRARY.UPDATE"),
HDS_LIBRARY_DELETE("HDS_LIBRARY.DELETE"),
```
Match the constructor signature in the existing enum. If permissions are pure strings (not enums), add them as strings.

- [ ] **Step 3: Update the role → permission matrix**

Find `RolePermissionMatrix.java` (or equivalent) and grant:
- All roles: `HDS_LIBRARY.READ`
- `ADMIN`, `PORTFOLIO_MANAGER`: `HDS_LIBRARY.CREATE`, `HDS_LIBRARY.UPDATE`
- `ADMIN`: `HDS_LIBRARY.DELETE`

(If permissions are seeded into DB at startup via a seeder, also update the seeder.)

- [ ] **Step 4: Verify**

```bash
(cd backend && mvn install -pl bipros-api -am -DskipTests -q)
(cd backend && mvn -pl bipros-api spring-boot:run -am) &
sleep 25
# Login and check the user's permissions include HDS_LIBRARY.READ
ADMIN_TOKEN=$(curl -sS -X POST http://localhost:8080/v1/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"admin","password":"admin123"}' | jq -r '.data.accessToken')
curl -sS http://localhost:8080/v1/users/me -H "Authorization: Bearer $ADMIN_TOKEN" | jq '.data.permissions'
# Stop the server.
```
Expected: `HDS_LIBRARY.READ` and the three admin codes appear in the array.

- [ ] **Step 5: Commit**

```bash
git add -p   # stage just permission files
git commit -m "feat(hds): register HDS_LIBRARY.{READ,CREATE,UPDATE,DELETE} permissions"
```

---

## Task 0.9 — Document a question-stub for open questions

**Files:**
- Create: `docs/superpowers/notes/2026-05-21-hds-open-questions.md`

- [ ] **Step 1: Create the note**

```markdown
# HDS open questions (resolve before phase 4 ships)

These are from spec §13. Phase 0 commits use the default-listed answers; revisit before phase 4 frontend lockup.

1. **OpenAI embeddings tier**: Confirm the `LlmProviderConfig` row for the active OpenAI provider supports embeddings + tier-1 rate limits. If not, ingestion of a 1GB doc will take >12h.
   - Default assumed: tier-1 OK.

2. **`HDS_LIBRARY.READ` default**: Currently granted to all roles. If gating to project-access-having users only is desired, restrict in the role-permission matrix.
   - Default assumed: all roles.

3. **New-conversation default scope**: Empty scope (user must explicitly pick versions before first HDS query).
   - Default assumed: empty.

4. **Discipline taxonomy**: Spec uses `HIGHWAY|BRIDGE|GEOTECH|PAVEMENT|TRAFFIC|DRAINAGE|OTHER`. Engineering may want extra: `STRUCTURAL`, `ELECTRICAL`, `ENVIRONMENT`.
   - Default assumed: the 7 in spec.

Update this file and the relevant code if any answer changes.
```

- [ ] **Step 2: Commit**

```bash
git add docs/superpowers/notes/2026-05-21-hds-open-questions.md
git commit -m "docs(hds): note open questions with default assumptions"
```

---

## Phase 0 verify gate

Run all of these. Each must succeed before Phase 1 dispatch.

```bash
# 1. Module compiles
(cd backend && mvn install -pl bipros-hds -am -DskipTests -q) && echo OK1

# 2. Schema + extension present
psql -h localhost -U bipros -d bipros -tAc "SELECT 1 FROM pg_namespace WHERE nspname='hds'" | grep -q 1 && echo OK2
psql -h localhost -U bipros -d bipros -tAc "SELECT 1 FROM pg_extension WHERE extname='vector'" | grep -q 1 && echo OK3

# 3. Docling reachable
curl -fsS http://localhost:5001 >/dev/null 2>&1 || curl -fsS http://localhost:5001/v1/convert -X POST >/dev/null 2>&1
echo OK4

# 4. MinIO bucket exists
docker exec bipros-minio mc ls local/hds >/dev/null 2>&1 || mc ls bipros-local/hds >/dev/null 2>&1
echo OK5

# 5. Permission code visible
ADMIN_TOKEN=$(curl -sS -X POST http://localhost:8080/v1/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"admin","password":"admin123"}' | jq -r '.data.accessToken')
curl -sS http://localhost:8080/v1/users/me -H "Authorization: Bearer $ADMIN_TOKEN" \
  | jq -e '.data.permissions[] | select(.=="HDS_LIBRARY.READ")' >/dev/null && echo OK6
```

All six `OKn` must print. If any fail, fix the corresponding task before fanning out Phase 1.
