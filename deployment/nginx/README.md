# nginx reverse proxy — frontend under /v2

Serves the Bipros EPPM frontend under the `/v2` sub-path (e.g.
`https://demo.unitysphere.site:4001/v2/`).

## How the pieces fit

The frontend is built with **`basePath=/v2`** — set by `NEXT_PUBLIC_BASE_PATH`
(committed in `frontend/.env.production`, overridable via `frontend/.env.local`
for dev). `basePath` is **inlined at build time**, so the value must be present
when `next build` runs. With it, Next emits assets at `/v2/_next/...` and serves
both the pages and the assets under `/v2`.

nginx's only job is to forward `/v2` to the Next server **without rewriting the
path**. The single thing to get right:

| `proxy_pass` | Effect |
| --- | --- |
| `http://upstream;` (no trailing slash) | keeps `/v2` → ✅ works |
| `http://upstream/;` (trailing slash) | strips `/v2` → ❌ 404s + `text/plain` CSS errors |

## Run

```bash
# 1. Build & start the frontend (basePath baked in from .env.production)
cd frontend && pnpm install && pnpm build && pnpm start -p 4001   # or its own container

# 2. Point conf.d/bipros.conf `upstream` at that server, then:
docker compose -f deployment/nginx/docker-compose.yml up -d

# 3. Verify
curl -sI http://localhost:4001/v2/auth/login          # 200 text/html
#   view-source should reference /v2/_next/static/... (200, correct MIME)
```

Reload after editing the config:

```bash
docker exec bipros-nginx nginx -t && docker exec bipros-nginx nginx -s reload
```

## Upstream options (`conf.d/bipros.conf`)

- Next running on the Docker host: `server host.docker.internal:4001;`
  (the compose `extra_hosts` entry enables this).
- Next as another compose service: `server frontend:4001;` (put both on the
  same compose network).
