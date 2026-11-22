# ============================================
# Stage 1: Build with Gradle Wrapper
# Gradle
# JDK (javac compiler, etc.)
# Source Code
# Build Cache
# ============================================

# AS builder --> Alias of this stage
FROM eclipse-temurin:17-jdk AS builder

WORKDIR /app

# Build files change less often than sources, so these layers stay cached on code-only edits
COPY gradlew ./
COPY gradle ./gradle
COPY build.gradle settings.gradle ./

COPY src ./src

# Cache mount: keeps downloaded dependencies between builds without storing them in the image
# -x test: @SpringBootTest needs a database, which does not exist at build time
# --no-daemon: a one-off build has no use for a background Gradle process
RUN --mount=type=cache,target=/root/.gradle sed -i 's/\r$//' gradlew && sh ./gradlew bootJar --no-daemon -x test

# ============================================
# Stage 2: Runtime (minimal, deployed)
# JRE
# Build Results (jar)
# ============================================
FROM eclipse-temurin:17-jre

WORKDIR /app

# bootJar produces a single jar; `./gradlew build` would also create *-plain.jar and break this copy
RUN groupadd --system foodguard && useradd --system --gid foodguard foodguard
COPY --from=builder --chown=foodguard:foodguard /app/build/libs/*.jar app.jar
USER foodguard

# Documents server.port in application.yml; the host port is mapped in docker-compose.yml
EXPOSE 8080

# Exec form makes java PID 1, so it receives SIGTERM from `docker stop` and shuts down gracefully
ENTRYPOINT ["java", "-jar", "app.jar"]
