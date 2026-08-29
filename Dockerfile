# syntax=docker/dockerfile:1.7
FROM maven:3.9.11-eclipse-temurin-21 AS build
WORKDIR /workspace
COPY pom.xml .
COPY src src
RUN --mount=type=cache,target=/root/.m2 mvn -B -DskipTests package

FROM eclipse-temurin:21-jre-jammy
ARG APP_VERSION=dev
ARG GIT_REVISION=unknown
ARG GITHUB_REPOSITORY=fitpilot/fitpilot
LABEL org.opencontainers.image.title="FitPilot" \
      org.opencontainers.image.version="${APP_VERSION}" \
      org.opencontainers.image.revision="${GIT_REVISION}" \
      org.opencontainers.image.source="https://github.com/${GITHUB_REPOSITORY}"
RUN apt-get update \
    && apt-get install --no-install-recommends -y curl \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --system --gid 10001 fitpilot \
    && useradd --system --uid 10001 --gid fitpilot --home /app --shell /usr/sbin/nologin fitpilot
WORKDIR /app
COPY --from=build /workspace/target/fitpilot-backend-*.jar app.jar
RUN mkdir -p /var/log/fitpilot \
    && chown -R fitpilot:fitpilot /app /var/log/fitpilot
USER 10001:10001
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -Djava.io.tmpdir=/tmp"
EXPOSE 8080
HEALTHCHECK --interval=10s --timeout=3s --start-period=30s --retries=6 \
    CMD curl -fsS http://localhost:8080/actuator/health/liveness || exit 1
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
