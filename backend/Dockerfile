# syntax=docker/dockerfile:1.7

# -----------------------------------------------------------------------------
# Stage 1 — build (Maven + Temurin 23)
# -----------------------------------------------------------------------------
FROM maven:3.9-eclipse-temurin-23 AS build
WORKDIR /workspace

# Copy the entire backend tree. A code-only change still benefits from the
# BuildKit cache mount on ~/.m2 below, which makes dependency resolution free.
COPY . .

RUN --mount=type=cache,target=/root/.m2 \
    mvn -q -pl bipros-api -am package -DskipTests

# -----------------------------------------------------------------------------
# Stage 2 — runtime (JRE 23, non-root)
# -----------------------------------------------------------------------------
FROM eclipse-temurin:23-jre AS runtime

RUN groupadd --system bipros && useradd --system --gid bipros --create-home bipros
WORKDIR /app

COPY --from=build /workspace/bipros-api/target/bipros-api-0.1.0-SNAPSHOT.jar app.jar

# Bipros writes runtime artefacts under ./storage/* (DPR photos, attachments,
# export reports). The default path is relative to the working dir, so make
# /app/storage writable by the non-root runtime user.
RUN mkdir -p /app/storage && chown -R bipros:bipros /app

EXPOSE 8080

USER bipros

# Container respects cgroup memory limits; JVM defaults are conservative.
# Override JAVA_OPTS in compose for tuning (e.g. -Xmx2g for heavier loads).
ENV JAVA_OPTS=""

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
