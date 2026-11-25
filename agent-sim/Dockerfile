# syntax=docker/dockerfile:1
# Build from the repository root:  docker build -f agent-sim/Dockerfile .
# One-shot program (no web server, so no HEALTHCHECK): it runs the demo script and exits.
# ---- stage 1: build the runnable jar with the Maven wrapper (JDK) ----
FROM eclipse-temurin:25-jdk AS build
WORKDIR /src
COPY . .
RUN --mount=type=cache,target=/root/.m2 \
    ./mvnw -B -q -pl agent-sim -am -DskipTests package \
    && cp agent-sim/target/agent-sim-*-SNAPSHOT.jar /app.jar

# ---- stage 2: small runtime image (JRE only), non-root ----
FROM eclipse-temurin:25-jre-alpine
RUN addgroup -S app && adduser -S app -G app
USER app
WORKDIR /app
COPY --from=build /app.jar app.jar
# Case files of the QA1 red-team suite (cases are data: qa/redteam/*.json)
COPY --from=build /src/qa /app/qa
ENTRYPOINT ["java", "-jar", "app.jar"]
