# Evidence: C1 — whole system with one command

- **Date:** 2026-11-25
- **Requirement:** C1. `docker compose up` starts the whole system on an 8 GB laptop; no paid services or API keys. C2: no secrets in the repo.
- **Machine:** Windows 11, Docker Engine 29.9.3, JDK not used for the container build (Maven runs inside the build stage).
- **Before the run:** `./mvnw -B verify` green (176 gateway tests). Two tests changed from 4 to 5 expected rules, because the demo policy gained `support-track-parcel` (needed by the demo agent's parcel step).

## What was added

| File | Purpose |
|---|---|
| `gateway/Dockerfile`, `mock-tools/Dockerfile`, `agent-sim/Dockerfile` | Multi-stage: `eclipse-temurin:25-jdk` builds with `./mvnw`, `eclipse-temurin:25-jre-alpine` runs the jar as non-root user `app`. HEALTHCHECK on `/actuator/health` for gateway and mock-tools (agent-sim is a one-shot program, no web server). |
| `docker-compose.yml` | postgres, mock-tools, gateway. Gateway `depends_on` postgres and mock-tools with `condition: service_healthy`. `agent-sim` is under compose profile `demo`. |
| `.env.example` (committed), `.env` (git-ignored) | All passwords and client secrets. Compose stops with a clear message if a value is missing (`${VAR:?...}`). |
| `gateway/.../application.yml` profile `docker` | Container URLs; secrets only from environment, no defaults. Profile `local` is unchanged. |
| `DemoToolSeeder` (new, profile `docker`) | On an empty registry: OpenAPI import + `shop-parcels` MCP server import from mock-tools. Failure is logged, not fatal. |
| `DemoAgentSeeder`, `DemoPolicySeeder` | Now run in `local` and `docker`. Policy v1 got the rule `support-track-parcel`. |
| `agent-sim/pom.xml` | `spring-boot-maven-plugin` repackage, so the jar is runnable. |
| `.gitattributes`, `.dockerignore` | LF for `mvnw`, `*.sh`, Dockerfiles; small build context. |

## Commands and results (from zero)

```
$ cp .env.example .env
$ docker compose down -v          # volume agentgate_postgres-data removed
$ docker compose up --build -d    # real 0m58s (images were built for the first time; base images already pulled)
 Container agentgate-postgres Healthy
 Container agentgate-mock-tools Healthy
 Container agentgate-gateway Started          <- only after both were healthy

$ docker compose ps
agentgate-gateway      Up 17 seconds (healthy)   0.0.0.0:8080->8080/tcp
agentgate-mock-tools   Up 28 seconds (healthy)   0.0.0.0:8081->8081/tcp
agentgate-postgres     Up 27 seconds (healthy)   0.0.0.0:5432->5432/tcp

$ docker compose logs gateway | grep -i seeded
Started GatewayApplication in 11.122 seconds
DemoAgentSeeder   : Seeded demo agent 'support-bot' with LOW clearance
DemoAgentSeeder   : Seeded demo agent 'analytics-bot' with HIGH clearance
DemoToolSeeder    : Seeded demo tools from http://mock-tools:8081 (5 tools in the registry)
DemoPolicySeeder  : Seeded and activated demo policy version 1 (5 rules)

$ docker exec agentgate-gateway id
uid=100(app) gid=101(app) groups=101(app)          <- not root
```

Image sizes: gateway 459 MB, mock-tools 387 MB, agent-sim 352 MB.

### Demo agent

```
$ docker compose --profile demo run --rm agent-sim        # exit code 0
== agent 'support-bot' got an OAuth2 access token (client credentials)
== connected to MCP server 'agentgate' (protocol 2025-06-18)
-- 1. tools/list: getCustomer, getOrder, issueRefund, track_parcel
-- 2. getOrder 1001 (policy: allowed)              result: OK  (phone redacted: 017******78)
-- 3. getCustomer 2 while acting for customer 1    result: ERROR/BLOCKED  (default deny; rule 'support-own-customer-only' did not apply)
-- 4. track_parcel BD1001 (downstream MCP tool)    result: OK
-- 5. issueRefund 500 BDT (HIGH risk)              HELD -> approver approves -> state APPROVED -> result: OK, refund RF-5001
```

### Audit chain

```
PS> .\scripts\audit-verify.ps1        # ADMIN_* from .env
AUDIT CHAIN OK - 4 records verified     (exit 0)
```

4 records = steps 2 to 5 (tools/list is not a tool call).

### Stop

```
$ docker compose down            # containers and network removed, volume kept
$ docker compose ps -a           # empty
```

## Notes and known limits

- **One extra step on a fresh clone:** `cp .env.example .env`. This is on purpose (C2): the repo contains no passwords, so compose refuses to start without `.env` and says what to do. A passing `docker compose up --build` with no `.env` would need passwords committed in the compose file.
- agent-sim prints `SLF4J(W): No SLF4J providers were found` (harmless: it has no logging backend; its own output is plain `System.out`).
- The build stage does not run tests (`-DskipTests`); tests run with `./mvnw verify` (Testcontainers) and in CI.
- Not measured here: memory use of the whole stack against the 8 GB target (JVMs are capped with `MaxRAMPercentage=60`). Add `docker stats` output to the evidence report in Phase 6.
