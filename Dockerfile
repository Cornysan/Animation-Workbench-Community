# ============================================
# Animation Workbench Community Portal - Dockerfile
# Multi-stage build for Spring Boot application
# ============================================

# ============================================
# Stage 1: Build
# ============================================
FROM gradle:8.5-jdk17-alpine AS build

WORKDIR /app

COPY gradle ./gradle
COPY gradlew ./
RUN chmod +x gradlew

COPY gradle.properties ./
COPY settings.gradle.kts ./
COPY build.gradle.kts ./

RUN ./gradlew dependencies --no-daemon

COPY src ./src
RUN ./gradlew clean bootJar --no-daemon


# ============================================
# Stage 2: Runtime
# ============================================
FROM eclipse-temurin:17-jre-alpine

WORKDIR /app

# Create non-root user for security
RUN addgroup -S spring && adduser -S spring -G spring

# Copy JAR from build stage
COPY --from=build /app/build/libs/*.jar app.jar

# Change ownership. Hochgeladene Clips liegen im Volume unter
# /var/lib/aw-community/blobs (siehe server/docker-compose.yml).
RUN chown spring:spring app.jar     && mkdir -p /var/lib/aw-community/blobs && chown -R spring:spring /var/lib/aw-community

# Switch to non-root user
USER spring:spring

# Expose application port
EXPOSE 8080


# Run the application
ENTRYPOINT ["java", "-jar", "app.jar"]