# Pre-built artifact packaging for the Docusaurus docs site.
#
# Build context: repo root (../../)
# Requires: user-guide/build/ to exist (built on host via npm run build inside user-guide/)

FROM nginx:1.27-alpine

RUN rm /etc/nginx/conf.d/default.conf

COPY user-guide/build /usr/share/nginx/html/docs

COPY infrastructure/docker/services/docs/nginx.conf /etc/nginx/conf.d/docs.conf

EXPOSE 80

HEALTHCHECK --interval=30s --timeout=5s --start-period=15s --retries=3 \
  CMD wget -q --spider http://localhost/ || exit 1

CMD ["nginx", "-g", "daemon off;"]
