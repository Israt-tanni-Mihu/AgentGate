# syntax=docker/dockerfile:1
# Build from the repository root:  docker build -f mock-tools/Dockerfile .
# ---- stage 1: build the jar with the Maven wrapper (JDK) ----
FROM eclipse-temurin:25-jdk AS build
WORKDIR /src
COPY . .
# The cache mount keeps downloaded libraries between builds. Tests run in CI / "./mvnw verify", not here.
RUN --mount=type=cache,target=/root/.m2 \
    ./mvnw -B -q -pl mock-tools -am -DskipTests package \
    && cp mock-tools/target/mock-tools-*-SNAPSHOT.jar /app.jar

# ---- stage 2: small runtime image (JRE only), non-root ----
FROM eclipse-temurin:25-jre-alpine
RUN addgroup -S app && adduser -S app -G app
USER app
WORKDIR /app
COPY --from=build /app.jar app.jar
HEALTHCHECK --interval=10s --timeout=3s --start-period=30s --retries=6 \
  CMD wget -q -O /dev/null http://localhost:8081/actuator/health || exit 1
EXPOSE 8081
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=60"
ENTRYPOINT ["java", "-jar", "app.jar"]
