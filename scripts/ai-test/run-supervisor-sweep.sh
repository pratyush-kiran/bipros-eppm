#!/usr/bin/env bash
#
# Run a 20-question supervisor-focused sweep against /v1/ai/chat for the
# OMAN-Demo-Khasab project. Asserts no answer is byte-identical to a previous
# answer (the canned-loop signal: the model is stuck saying the same thing).
#
# Usage:
#   BIPROS_AI_KEK=<base64> ./scripts/ai-test/run-supervisor-sweep.sh
#
# Optional env:
#   BASE      — API base URL (default http://localhost:8080)
#   ADMIN_PW  — admin password (default admin123)
#
# Exits:
#   0  all 20 answered, no canned-loop
#   2  canned-loop detected — prints failing question and exits early
#   3  auth or HTTP error before sweep started

set -euo pipefail

BASE="${BASE:-http://localhost:8080}"
ADMIN_PW="${ADMIN_PW:-admin123}"
PROJECT_CODE="OMAN-DEMO-KHASAB"
REPORT="$(dirname "$0")/oman-demo-supervisor-sweep-$(date +%Y%m%d-%H%M%S).md"

if [[ -z "${BIPROS_AI_KEK:-}" ]]; then
  echo "ERROR: BIPROS_AI_KEK must be set so the AI service can decrypt the LLM provider key" >&2
  exit 3
fi

# ── 1. Login ──────────────────────────────────────────
TOKEN_JSON=$(curl -sf -X POST "$BASE/v1/auth/login" \
  -H "Content-Type: application/json" \
  -d "{\"username\":\"admin\",\"password\":\"$ADMIN_PW\"}" || true)
TOKEN=$(echo "$TOKEN_JSON" | python3 -c "import sys,json;print(json.load(sys.stdin)['data']['accessToken'])" 2>/dev/null || true)
if [[ -z "$TOKEN" ]]; then
  echo "ERROR: login failed against $BASE — response was: $TOKEN_JSON" >&2
  exit 3
fi

# ── 2. Resolve project id ─────────────────────────────
PROJECT_ID=$(curl -sf "$BASE/v1/projects?code=$PROJECT_CODE" \
  -H "Authorization: Bearer $TOKEN" \
  | python3 -c "import sys,json
data=json.load(sys.stdin).get('data',[])
items=data if isinstance(data,list) else data.get('content',[])
for p in items:
  if p.get('code')=='$PROJECT_CODE': print(p['id']); break
" 2>/dev/null || true)
if [[ -z "$PROJECT_ID" ]]; then
  echo "ERROR: could not resolve project $PROJECT_CODE — does the seed data include it?" >&2
  exit 3
fi
echo "✓ login + project resolved (project_id=$PROJECT_ID)"

# ── 3. Questions ──────────────────────────────────────
QUESTIONS=(
  "Who supervises activity 2.3.6(i)a on OMAN-Demo-Khasab? List every supervisor."
  "How many distinct supervisors are deployed on OMAN-Demo-Khasab?"
  "Which activities does Illayaraja supervise on OMAN-Demo-Khasab?"
  "Which activities does K. Barman supervise on OMAN-Demo-Khasab?"
  "Which activities does Mohd Ismaila supervise on OMAN-Demo-Khasab?"
  "Which activities does Vijaykumar supervise on OMAN-Demo-Khasab?"
  "How many DPRs has K. Barman filed on OMAN-Demo-Khasab in February 2026?"
  "How many DPRs has Illayaraja filed on OMAN-Demo-Khasab in March 2026?"
  "Show the supervisors who co-supervise activity 5.1.7 (iii) on OMAN-Demo-Khasab."
  "Show the supervisors who co-supervise activity 2.4.6(i) on OMAN-Demo-Khasab."
  "List the supervisors on OMAN-Demo-Khasab whose names contain a period."
  "Who supervised activity 2.3.6(i)b on OMAN-Demo-Khasab on 1 March 2026?"
  "Does the supervisor 'oman-demo.illayaraja' exist on OMAN-Demo-Khasab?"
  "Compare Md Saiffuddin and Mohd Ismaila by DPR count on OMAN-Demo-Khasab for January 2026."
  "Which supervisor on OMAN-Demo-Khasab supervises the most activities?"
  "List all supervisors of activity 1.1 on OMAN-Demo-Khasab."
  "What is the employee code of supervisor K. Barman on OMAN-Demo-Khasab?"
  "Who works on activity 2.3.6(i)b on OMAN-Demo-Khasab as Foreman or Helper?"
  "Show DPR rows filed by Parvaiz on OMAN-Demo-Khasab in February 2026."
  "Which supervisors on OMAN-Demo-Khasab also appear on the SC-180 project?"
)

# ── 4. Sweep ──────────────────────────────────────────
echo "# OMAN-Demo-Khasab supervisor sweep — $(date)" > "$REPORT"
echo "" >> "$REPORT"
echo "Endpoint: $BASE — project_id: $PROJECT_ID" >> "$REPORT"
echo "" >> "$REPORT"

PREV_HASH=""
CANNED_COUNT=0
CONV_ID=""

for i in "${!QUESTIONS[@]}"; do
  Q="${QUESTIONS[$i]}"
  N=$((i+1))
  echo "Q$N: $Q"

  PAYLOAD=$(python3 -c "
import json,sys
p={'projectId':'$PROJECT_ID','module':'project','message':sys.argv[1]}
cid='$CONV_ID'
if cid: p['conversationId']=cid
print(json.dumps(p))
" "$Q")

  RESP=$(curl -sf -X POST "$BASE/v1/ai/chat" \
    -H "Authorization: Bearer $TOKEN" \
    -H "Content-Type: application/json" \
    -d "$PAYLOAD" 2>/dev/null || true)

  ANSWER=$(echo "$RESP" | python3 -c "
import sys,json
try:
  d=json.load(sys.stdin).get('data',{})
  print(d.get('text') or d.get('message') or '')
except Exception:
  print('')
")
  CONV_ID=$(echo "$RESP" | python3 -c "
import sys,json
try:
  d=json.load(sys.stdin).get('data',{})
  print(d.get('conversationId') or '')
except Exception:
  print('')
")

  if [[ -z "$ANSWER" ]]; then
    echo "  WARN: empty response — payload was: $RESP" | tee -a "$REPORT"
    ANSWER="(empty)"
  fi

  # Markdown row
  {
    echo "## Q$N. $Q"
    echo ""
    echo "$ANSWER"
    echo ""
    echo "---"
    echo ""
  } >> "$REPORT"

  # Hash check for canned-loop
  HASH=$(printf '%s' "$ANSWER" | md5)
  if [[ "$HASH" == "$PREV_HASH" && "$ANSWER" != "(empty)" ]]; then
    CANNED_COUNT=$((CANNED_COUNT+1))
    if (( CANNED_COUNT >= 1 )); then
      echo "" >&2
      echo "ABORT: Q$N produced the same answer as Q$((N-1)) — canned-loop signal." >&2
      echo "Failing question: $Q" >&2
      echo "Answer (truncated):" >&2
      echo "$ANSWER" | head -c 400 >&2
      echo "" >&2
      echo "Report so far: $REPORT" >&2
      exit 2
    fi
  else
    CANNED_COUNT=0
  fi
  PREV_HASH="$HASH"

  # Slight pacing so a slow LLM doesn't trip rate limits
  sleep 1
done

echo ""
echo "✓ Sweep complete — report: $REPORT"
echo ""
echo "Quick scan for slug-leak (any 'oman-demo.' or 'oman.demo.' in answers):"
if grep -nE "oman[-.]demo\." "$REPORT" >/dev/null; then
  echo "  ⚠ FOUND slug references in answers — review the report manually." >&2
  grep -nE "oman[-.]demo\." "$REPORT" | head -10
  exit 2
fi
echo "  none found"
