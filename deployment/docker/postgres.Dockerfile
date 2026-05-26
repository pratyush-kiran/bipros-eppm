# Postgres 17 + PostGIS 3.5 (for the gis module) + pgvector (for the HDS
# knowledge base). Identical to the legacy docker/postgres/Dockerfile.
FROM --platform=linux/amd64 postgis/postgis:17-3.5

RUN apt-get update \
 && apt-get install -y --no-install-recommends postgresql-17-pgvector \
 && rm -rf /var/lib/apt/lists/*
