# syntax=docker/dockerfile:1.7
# =============================================================================
# account-service — multi-stage Docker build (context: monorepo root)
# HOST pre-build required:  ./gradlew :apps:account-service:bootJar
# =============================================================================
FROM eclipse-temurin:21-jdk AS build
WORKDIR /workspace
COPY apps/account-service/build/libs/ ./libs/
RUN set -eux; cd libs; \
    jar="$(ls -1 *.jar | grep -v -E 'plain|sources|javadoc' | head -n1)"; \
    test -n "$jar" || (echo "bootJar not found. Run: ./gradlew :apps:account-service:bootJar" >&2; exit 1); \
    cp "$jar" /workspace/app.jar

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
EXPOSE 8082
ENV JAVA_OPTS="-XX:+UseContainerSupport -XX:MaxRAMPercentage=75"
HEALTHCHECK --interval=10s --timeout=5s --start-period=60s --retries=6 \
  CMD curl -fsS http://localhost:8082/actuator/health || exit 1
ENTRYPOINT ["sh","-c","exec java $JAVA_OPTS -jar /app/app.jar"]
