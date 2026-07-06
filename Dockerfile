# syntax=docker/dockerfile:1

# --- Build stage: compile and package the Spring Boot jar ---
# Pinned to $BUILDPLATFORM so the Maven build always runs natively on the builder (never under QEMU
# emulation) even for multi-arch builds. The jar is architecture-independent, so it's built once and
# reused; only the runtime stage below is built per target architecture.
FROM --platform=$BUILDPLATFORM eclipse-temurin:21-jdk AS build
WORKDIR /app

# Cache dependencies first (only re-runs when pom/wrapper change)
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN ./mvnw -B -ntp dependency:go-offline

# Build the application (tests need Docker/Testcontainers, so skip them here)
COPY src/ src/
RUN ./mvnw -B -ntp clean package -DskipTests

# --- Runtime stage: slim JRE with just the fat jar ---
FROM eclipse-temurin:21-jre AS runtime
WORKDIR /app

# curl is used by the container healthcheck to probe the actuator endpoint
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*

# Run as an unprivileged user
RUN groupadd --system spring && useradd --system --gid spring spring
USER spring

COPY --from=build /app/target/*.jar app.jar

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]