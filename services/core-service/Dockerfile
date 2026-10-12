# syntax=docker/dockerfile:1.7
#
# Multi-stage, multi-arch (amd64 + arm64) image for core-service.
#   docker build -t payledger/core-service .
#
# Tests are NOT run here: they need Docker themselves (Testcontainers), so CI runs them before building the image.

# ---- build: compile once on the builder's native platform; the jar is platform-independent ----
FROM --platform=$BUILDPLATFORM eclipse-temurin:21-jdk AS build
WORKDIR /workspace

# Dependencies first: this layer is cached until pom.xml changes.
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN chmod +x mvnw
RUN --mount=type=cache,target=/root/.m2 ./mvnw -B -q dependency:go-offline

COPY src/ src/
RUN --mount=type=cache,target=/root/.m2 ./mvnw -B -q package -DskipTests \
 && java -Djarmode=tools -jar target/core-service-*.jar extract --layers --launcher --destination extracted

# ---- runtime: JRE only, non-root, layered for small incremental pulls ----
FROM eclipse-temurin:21-jre

LABEL org.opencontainers.image.title="payledger-core-service" \
      org.opencontainers.image.description="Budgets, households and groups: the product API (Spring Boot 4, Java 21)" \
      org.opencontainers.image.licenses="MIT"

RUN groupadd --system --gid 10001 app && useradd --system --uid 10001 --gid app --no-create-home app
WORKDIR /app

# Least-frequently-changing layers first, so a code change only re-ships the small "application" layer.
COPY --from=build /workspace/extracted/dependencies/ ./
COPY --from=build /workspace/extracted/spring-boot-loader/ ./
COPY --from=build /workspace/extracted/snapshot-dependencies/ ./
COPY --from=build /workspace/extracted/application/ ./

USER 10001:10001
EXPOSE 8060 8061

# Size the heap from the container memory limit; crash fast on OOM so the orchestrator restarts a clean JVM.
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"

# No curl in the JRE image: bash's /dev/tcp is enough for a readiness probe on the management port.
HEALTHCHECK --interval=15s --timeout=3s --start-period=60s --retries=5 \
  CMD bash -c 'exec 3<>/dev/tcp/127.0.0.1/8061 && printf "GET /actuator/health/readiness HTTP/1.0\r\nHost: localhost\r\n\r\n" >&3 && grep -q "\"UP\"" <&3'

ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]
