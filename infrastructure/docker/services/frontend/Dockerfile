# Pre-built artifact packaging for the Next.js frontend.
#
# Build context: frontend/ directory
# Requires: .next/standalone and .next/static to exist (built on host via pnpm build)
# Requires next.config.ts to have output: 'standalone'

FROM node:22-alpine AS runtime

RUN addgroup -S -g 1001 nodejs && \
    adduser -S -u 1001 -G nodejs nextjs

WORKDIR /app

COPY --chown=nextjs:nodejs public ./public
COPY --chown=nextjs:nodejs .next/standalone ./
COPY --chown=nextjs:nodejs .next/static ./.next/static

USER nextjs

EXPOSE 3000

ENV NODE_ENV=production
ENV NEXT_TELEMETRY_DISABLED=1
ENV PORT=3000
ENV HOSTNAME="0.0.0.0"

HEALTHCHECK --interval=30s --timeout=10s --start-period=30s --retries=3 \
  CMD wget -qO- http://localhost:3000/ > /dev/null || exit 1

CMD ["node", "server.js"]
