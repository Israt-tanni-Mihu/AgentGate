# AgentGate

A governance gateway between AI agents and the tools they call. Every tool call is authenticated,
checked against policy, guarded, held for human approval if high-risk, forwarded, and audited.
(CSE 444 project. See `CLAUDE.md` for requirements and roadmap.)

## Modules

| Folder | What it is | Port |
|---|---|---|
| `gateway/` | The AgentGate Spring Boot app | 8080 |
| `mock-tools/` | Fake "ShopBD" REST tool server used for demos and tests | 8081 |
| `agent-sim/` | Scripted demo agent (MCP client) | – |
| `docs/` | ADRs, diagrams, patterns table, AI-usage log | – |

**Evidence report (C9):** [docs/evidence-report.md](docs/evidence-report.md) shows each requirement's status, key number, evidence file and how to reproduce it.

## Requirements

- JDK 25 (Temurin)
- Docker Desktop (for PostgreSQL and for the tests)
- No Maven install needed: use the wrapper (`mvnw` / `mvnw.cmd`)

## Quick start (one command)

Prerequisite: Docker Desktop (Compose v2). No JDK needed, the images build the code themselves.

```bash
cp .env.example .env              # Windows cmd: copy .env.example .env  (.env is git-ignored; edit the passwords if you like)
docker compose up --build         # postgres + mock-tools + gateway; the first build takes a few minutes
```

Demo data (ShopBD tools, 2 agents, policy version 1) is created automatically on the first start.
Wait until `docker compose ps` shows all three as `healthy`, then open http://localhost:8080/swagger-ui.html.

Run the demo agent (one-shot, not started by `up`):

```bash
docker compose --profile demo run --rm agent-sim
```

Check the audit chain (Windows PowerShell; the script reads `ADMIN_*` from your `.env` automatically):

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\audit-verify.ps1
```

`-ExecutionPolicy Bypass` applies only to this one run. It is needed because Windows blocks unsigned `.ps1` files by default, and it does not change any system setting.

Stop: `docker compose down` (keeps data) or `docker compose down -v` (also deletes the database, so demo data is re-seeded next time).

## Development quick start (without containers for the apps)


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
