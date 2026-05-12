# Pre-built artifact packaging for the Bipros EPPM Spring Boot monolith.
#
# Build context: project root (bipros-eppm/)
# Requires: backend/bipros-api/target/bipros-api-*.jar to exist (built on host via mvn package)

FROM eclipse-temurin:23-jre-alpine AS runtime

RUN addgroup -S -g 1000 spring && \
    adduser -S -u 1000 -G spring spring

WORKDIR /app

COPY --chown=spring:spring backend/bipros-api/target/bipros-api-*.jar app.jar

RUN mkdir -p /app/storage/documents /app/data/satellite /tmp && \
    chown -R spring:spring /app /tmp

USER spring:spring

EXPOSE 8080

ENV JAVA_OPTS="-Xms256m -Xmx512m -XX:+UseG1GC -XX:+ExitOnOutOfMemoryError -Djava.security.egd=file:/dev/./urandom"

HEALTHCHECK --interval=30s --timeout=10s --start-period=90s --retries=5 \
  CMD wget -qO- http://localhost:8080/actuator/health || exit 1

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
