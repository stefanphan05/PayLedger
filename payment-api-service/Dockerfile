# ---- Stage 1: build ----
FROM eclipse-temurin:17-jdk AS build
WORKDIR /app

# Build files first, source later. These four change rarely, so Docker caches
# the dependency download below and reuses it on every source-only rebuild.
COPY gradlew settings.gradle.kts build.gradle.kts ./
COPY gradle ./gradle
RUN chmod +x gradlew && ./gradlew dependencies --no-daemon

# Source last: editing a Kotlin file invalidates this layer and nothing above it.
COPY src ./src

# -x test because the tests are Testcontainers-based and there is no Docker
# daemon inside a Docker build. Tests run on your machine and in CI, not here.
RUN ./gradlew bootJar --no-daemon -x test

# ---- Stage 2: runtime ----
FROM eclipse-temurin:17-jre
WORKDIR /app

# Non-root. If anything ever escapes the process, it should not land on a root shell.
RUN useradd --system --create-home --shell /usr/sbin/nologin appuser
USER appuser

# Exactly one jar here, because build.gradle.kts:65 disables the plain jar.
COPY --from=build /app/build/libs/*.jar app.jar

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "/app/app.jar"]