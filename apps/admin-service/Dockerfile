# syntax=docker/dockerfile:1.7
# =============================================================================
# admin-service — multi-stage Docker build
# Build context: monorepo ROOT (see docker-compose.e2e.yml)
#
# Strategy: Gradle bootJar is produced on the HOST by
#   ./gradlew :apps:admin-service:bootJar
# (documented in docs/guides/docker-build.md). The "build" stage below then
# selects the freshly produced jar using a shell glob and copies it into the
# minimal JRE runtime stage. Building outside the container keeps layer
# cache-friendly, avoids Gradle network dependencies inside BuildKit, and
# still preserves a multi-stage graph so the final image only ships JRE + jar.
# =============================================================================

# -----------------------------------------------------------------------------
# Stage 1: Build — pick the bootJar produced on the host
# -----------------------------------------------------------------------------
FROM eclipse-temurin:21-jdk AS build
WORKDIR /workspace
COPY apps/admin-service/build/libs/ ./libs/
RUN set -eux; \
    cd libs; \
    jar="$(ls -1 *.jar | grep -v -E 'plain|sources|javadoc' | head -n1)"; \
    test -n "$jar" || (echo "bootJar not found. Run: ./gradlew :apps:admin-service:bootJar" >&2; exit 1); \
    cp "$jar" /workspace/app.jar

# -----------------------------------------------------------------------------
# Stage 2: Runtime — JRE only, non-root user, healthcheck
# -----------------------------------------------------------------------------
FROM eclipse-temurin:21-jre
WORKDIR /app
RUN apt-get update \
 && apt-get install -y --no-install-recommends curl \
 && rm -rf /var/lib/apt/lists/* \
 && (userdel -r ubuntu 2>/dev/null || true) \
 && groupadd -g 1000 app \
 && useradd -u 1000 -g 1000 -s /usr/sbin/nologin -M app

COPY --from=build /workspace/app.jar /app/app.jar
RUN chown -R app:app /app

USER app
EXPOSE 8085

ENV JAVA_OPTS="-XX:+UseContainerSupport -XX:MaxRAMPercentage=75"

HEALTHCHECK --interval=10s --timeout=5s --start-period=60s --retries=6 \
  CMD curl -fsS http://localhost:8085/actuator/health || exit 1

ENTRYPOINT ["sh","-c","exec java $JAVA_OPTS -jar /app/app.jar"]
