#!/usr/bin/env bash
# Show container + DB state. Read-only.
set -eo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/.."

echo "=== Containers ==="
docker compose ps --format "table {{.Name}}\t{{.Status}}\t{{.Ports}}" 2>/dev/null

echo ""
echo "=== Backend ==="
curl -fsS http://localhost:${API_HOST_PORT:-8080}/actuator/health 2>&1 | head -1 || echo "(unreachable)"

echo ""
echo "=== Khasab data ==="
docker exec bipros-postgres psql -U "${POSTGRES_USER:-bipros}" -d "${POSTGRES_DB:-bipros}" -c "
SELECT
  (SELECT COUNT(*) FROM project.projects WHERE code='KHASAB-2026') AS project,
  (SELECT COUNT(*) FROM project.daily_progress_reports) AS dprs,
  (SELECT COUNT(*) FROM activity.activities WHERE project_id=(SELECT id FROM project.projects WHERE code='KHASAB-2026')) AS activities,
  (SELECT COUNT(*) FROM resource.resource_assignments) AS role_assigns,
  (SELECT COUNT(*) FROM risk.risks) AS risks,
  (SELECT COUNT(*) FROM project.dpr_issues) AS dpr_issues;" 2>/dev/null || echo "(postgres unreachable)"
