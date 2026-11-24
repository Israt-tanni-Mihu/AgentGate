# AgentGate — project guide for the coding agent

## Who you are working with

- A university student (CSE 444: Software Architecture and Design Patterns) building this project **individually**.
- Knows Java core OOP and basic SQL. Is **learning Spring Boot while building this project**.
- Native language: Bangla. **After every task, explain what you did in simple Bangla** (technical terms can stay in English).
- The student must defend this code live (explain an ADR and make a change on the spot), so understanding matters more than speed.

## Working rules (follow strictly)

1. **One task at a time.** Do only the task asked. Do not jump ahead to other FRs.
2. After each task, give a short Bangla explanation:
   - which files you created/changed and what each one does,
   - which Spring concept or design pattern is used and *why* (which requirement it serves),
   - how to run/test it (exact commands, and what output to expect).
3. **Never run `git commit` or `git push`.** The student commits. At the end of each task, suggest a commit message (Conventional Commits style, e.g. `feat(registry): add tool registry CRUD`).
4. Keep `./mvnw verify` green. Every new feature comes with at least one test.
5. **No pattern without a requirement.** When you introduce a design pattern, add a row to `docs/patterns.md` (pattern, requirement it serves, participating classes, alternative rejected). Patterns not tied to a requirement earn no marks.
6. Prefer simple, readable code over clever code. Beginner-friendly names. Short comments only where the *why* is not obvious.
7. If something is uncertain (library version, API), check by compiling/running rather than guessing. If a dependency does not exist or an API changed, say so plainly.
8. Remind the student to log notable AI mistakes in `docs/AI-USAGE-LOG.md` when a fix was needed.

## The product

AgentGate is a governance gateway between AI agents and the tools they call (via MCP and plain REST). Every tool call is: authenticated → checked against policy → guarded (schema validation, redaction, limits) → held for human approval if high-risk → forwarded → audited in a tamper-evident log.

### Functional requirements

- **FR1 Tool registry** — register downstream MCP servers and REST endpoints as tools (name, description, input JSON schema, risk level low/medium/high, owning team). Import REST endpoints from an OpenAPI file. Agents see all permitted tools through one MCP endpoint.
- **FR2 Agent identity** — agents authenticate with OAuth2 client credentials. A call may also carry the end user the agent acts for; policies can use both identities.
- **FR3 Policies** — allow/deny by agent, tool and argument conditions (e.g. refund amount ≤ 2000 BDT; reads only for the requesting customer's own records). **Default deny.** Versioned; take effect within 10 s without restart; dry-run mode logs what would be blocked.
- **FR4 Human approval** — high-risk calls pause as pending approvals; approver notified (web page + webhook); can approve, reject or edit arguments; unanswered requests rejected after configurable timeout; agent always gets a clear result.
- **FR5 Guards** — validate arguments against the tool's schema before forwarding; redact results by agent clearance (emails, Bangladeshi phone numbers, national ID numbers); cut oversized results with a notice.
- **FR6 Limits** — rate limits and daily quotas per agent and per tool; blast-radius cap on write calls (e.g. 20/hour/agent). A breach blocks further calls and raises an alert.
- **FR7 Audit trail** — record agent, user, tool, redacted arguments, deciding policy rule, approval details, latency, outcome. Hash-chained (tampering detectable), searchable, exportable.
- **FR8 Kill switch** — admin disables one agent, one tool, or all write tools instantly; pending approvals for them are cancelled.
- **FR9 Policy playground** — test a sample call against current or draft policies; show decision and the rule that produced it.
- **FR10 Downstream health** — per-tool timeouts and circuit breakers; tools that are down are hidden from agents' tool lists until they recover.

### Quality attributes (success measures — each needs a runnable script)

- **QA1 Security** — red-team suite of 30 scripted malicious calls (injected refunds, cross-customer reads, argument smuggling): 100% blocked or held; ≤ 5% of 200 legitimate calls wrongly blocked.
- **QA2 Performance** — gateway adds ≤ 30 ms at p95 at 100 calls/s (excluding approval waits).
- **QA3 Auditability** — for any call, find who/on whose behalf/what/which rule in < 1 min; editing any stored audit row is detected by a verify command.
- **QA4 Fail-safe** — if the policy store is unreachable: medium/high-risk tools fail closed; low-risk tools keep working on last known policies for up to 5 minutes.
- **QA5 Adoption** — a classmate puts a new REST tool behind the gateway (import, risk level, policy) in < 10 minutes with no code changes.

### Common course requirements (C1–C12, summary)

- C1 Java 21 or 25 (we use 25), Spring Boot 4.x, Maven. Whole system starts with **one `docker compose up`** on an 8 GB laptop. No paid services or API keys.
- C2 Profiles + env vars; typed `@ConfigurationProperties` with validation; no secrets in the repo.
- C3 springdoc OpenAPI; Spring Security on every endpoint; Bean Validation; errors as RFC 9457 Problem Details; pagination on every list endpoint.
- C4 Actuator health/readiness; Micrometer metrics + tracing; structured JSON logs with trace ID.
- C5 JUnit 5 unit + slice tests; Testcontainers integration tests; every QA as a runnable script (k6 for load); CI (GitHub Actions) runs all.
- C6 Architecture rules as tests (Spring Modulith verification and/or ArchUnit).
- C7 C4 diagrams (context, container, component); ≥ 4 ADRs with measured evidence; utility tree ≥ 6 scenarios (mini-ATAM).
- C8 Pattern table (`docs/patterns.md`).
- C9 Evidence report with raw outputs, charts, failed runs and what changed.
- C10 Instructor change request mid-project — note files touched in core vs new components.
- C11 Incremental git history; AI-usage log with ≥ 3 cases where the agent was wrong.
- C12 Live defence.

Out of scope: building real agents (scripted MCP clients act as agents), a full identity-provider product, multi-region deployment.

## Initial technical decisions (may change; record changes as ADRs)

- **Build:** Maven multi-module, Java 25 (Temurin, installed on the student's laptop), Spring Boot 4.1.x (latest stable patch), Spring AI 2.0.x for MCP.
- **Modules (repo layout):**
  - `gateway/` — the AgentGate Spring Boot app (port 8080).
  - `mock-tools/` — a fake "ShopBD" REST tool server: `getOrder`, `issueRefund`, `sendSms`, `getCustomer` (port 8081). Has an OpenAPI spec so FR1 import can be demonstrated.
  - `agent-sim/` — scripted fake agents (legit calls + red-team calls) used for QA1/QA2.
  - `docs/` — ADRs, C4 diagrams, patterns table, evidence report, AI-usage log.
  - `perf/` — k6 scripts.
- **Web stack:** Spring MVC with virtual threads (not WebFlux) — candidate ADR-001.
- **Database:** PostgreSQL + Spring Data JPA + Flyway migrations. Testcontainers for integration tests.
- **Identity (FR2):** Spring Authorization Server embedded in the gateway for OAuth2 client credentials (avoids an extra Keycloak container on an 8 GB laptop) — candidate ADR.
- **Gateway modules (Spring Modulith packages under `gateway`):** `registry`, `identity`, `policy`, `guard`, `limits`, `approval`, `audit`, `admin` (kill switch), `playground`, `health`, `mcp` (MCP edge), `shared`.
- **Call pipeline:** an ordered chain of `CallGuard`/`PolicyStep` Spring beans (Chain of Responsibility); adding a guard = adding a bean, not editing existing code.
- **Build order:** first a plain REST endpoint `POST /api/calls/{tool}` through the full pipeline; add the MCP endpoint (Spring AI) on top later, so MCP library issues cannot block core work.
- **Resilience:** Resilience4j (timeouts, circuit breakers). **Observability:** Actuator, Micrometer, JSON logs.
- **Audit hash chain:** `hash = SHA-256(previousHash + canonicalRowContent)`; a `verify` endpoint/CLI recomputes the chain.

## Roadmap (check off as completed)

- [x] Phase 1 — Skeleton: multi-module Maven, Docker Compose (Postgres), health endpoint, mock-tools server, FR1 tool registry CRUD + OpenAPI import
- [x] Phase 2 — FR2 agent identity (OAuth2 client credentials), FR3 policy engine (default deny, versions, hot reload, dry-run), FR9 playground
- [x] Phase 3 — FR5 guards (schema validation, redaction, truncation), FR6 limits, FR8 kill switch
- [ ] Phase 4 — FR4 approval state machine + web page + webhook, FR7 hash-chained audit + verify
- [ ] Phase 5 — MCP endpoint (custom handler, done), downstream MCP servers (done), FR10 circuit breakers/health (done), metrics
- [ ] Phase 6 — QA scripts (red-team suite, k6 load, fail-safe test, audit tamper test, quick-start), Testcontainers, ArchUnit/Modulith tests, CI
- [ ] Phase 7 — Docs: C4, ADRs, utility tree, patterns table, evidence report, AI log

## Useful commands

- Start infrastructure: `docker compose up -d`
- Run gateway: `./mvnw -pl gateway spring-boot:run` (Windows: `mvnw.cmd -pl gateway spring-boot:run`)
- Run all tests: `./mvnw verify`
- Health check: `http://localhost:8080/actuator/health`
- API docs: `http://localhost:8080/swagger-ui.html`
