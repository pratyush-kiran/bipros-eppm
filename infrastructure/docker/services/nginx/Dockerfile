FROM nginx:1.27-alpine

RUN rm /etc/nginx/conf.d/default.conf

COPY nginx.conf /etc/nginx/nginx.conf

EXPOSE 80

HEALTHCHECK --interval=15s --timeout=5s --start-period=10s --retries=3 \
  CMD wget -q --spider http://localhost/health || exit 1

CMD ["nginx", "-g", "daemon off;"]
