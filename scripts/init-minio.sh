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
