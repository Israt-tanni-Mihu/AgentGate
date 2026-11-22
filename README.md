# AgentGate

A governance gateway between AI agents and the tools they call. Every tool call is authenticated,
checked against policy, guarded, held for human approval if high-risk, forwarded, and audited.
(CSE 444 project. See `CLAUDE.md` for requirements and roadmap.)

## Modules

| Folder | What it is | Port |
|---|---|---|
| `gateway/` | The AgentGate Spring Boot app | 8080 |
| `mock-tools/` | Fake "ShopBD" REST tool server used for demos and tests | 8081 |
| `docs/` | ADRs, diagrams, patterns table, AI-usage log | – |

## Requirements

- JDK 25 (Temurin)
- Docker Desktop (for PostgreSQL and for the tests)
- No Maven install needed: use the wrapper (`mvnw` / `mvnw.cmd`)

## Quick start

```bash
# 1. Start PostgreSQL 16
docker compose up -d

# 2. Build and run all tests (tests start their own throw-away Postgres via Testcontainers)
./mvnw verify            # Windows: mvnw.cmd verify

# 3. Run the gateway (uses the "local" profile by default)
./mvnw -pl gateway spring-boot:run       # Windows: mvnw.cmd -pl gateway spring-boot:run

# 4. Run the mock tool server (optional, in another terminal)
./mvnw -pl mock-tools spring-boot:run    # Windows: mvnw.cmd -pl mock-tools spring-boot:run
```

Check:

- Gateway health: http://localhost:8080/actuator/health → `{"status":"UP",...}`
- API docs: http://localhost:8080/swagger-ui.html
- Mock tools health: http://localhost:8081/actuator/health

Stop PostgreSQL with `docker compose down` (data is kept in the `postgres-data` volume;
`docker compose down -v` deletes it).

## Configuration

The gateway reads its database settings from environment variables:

| Variable | Local default |
|---|---|
| `DB_URL` | `jdbc:postgresql://localhost:5432/agentgate` |
| `DB_USERNAME` | `agentgate` |
| `DB_PASSWORD` | `agentgate` |

The defaults apply only in the `local` profile. Any other profile requires the variables to be set.
