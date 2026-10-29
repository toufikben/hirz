# Hirz Core image. Build after `./gradlew :server:installDist`; multi-arch (amd64 + arm64) in CI.
FROM eclipse-temurin:21-jre
RUN groupadd --system hirz && useradd --system --gid hirz --home /opt/hirz hirz \
    && apt-get update && apt-get install -y --no-install-recommends curl && rm -rf /var/lib/apt/lists/*
COPY server/build/install/server /opt/hirz
USER hirz
EXPOSE 8080
HEALTHCHECK --interval=30s --timeout=5s --start-period=60s CMD curl -fsS http://localhost:8080/health || exit 1
ENTRYPOINT ["/opt/hirz/bin/server"]
