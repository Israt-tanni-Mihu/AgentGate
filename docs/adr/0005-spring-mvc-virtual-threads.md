# ADR-0005: Spring MVC on virtual threads instead of Spring WebFlux

- **Status:** Accepted (2026-11, Phase 1; confirmed by QA2 measurement)
- **Linked:** QA2 (≤ 30 ms p95 at 100 calls/s), FR4 (calls held for minutes), FR2 (embedded authorization server), C1 (8 GB laptop), C12 (student must explain the code)

## Context

The gateway is I/O-bound: it calls PostgreSQL (JPA), downstream REST/MCP tools, and holds HIGH-risk calls for up to 2 minutes (ADR-0001). Classic thread-per-request with ~200 platform threads would run out under held calls. The usual answer was WebFlux (non-blocking). Java 25 offers virtual threads.

## Decision

Use **Spring MVC** (blocking, servlet) with `spring.threads.virtual.enabled: true`: every request runs on its own virtual thread. Blocking calls (JDBC, `RestClient`, `CompletableFuture.get` in `ApprovalStep`) park the virtual thread and free the carrier thread.

## Alternatives considered

| Option | Why not |
|---|---|
| **Spring WebFlux + R2DBC** | Scales well, but: Spring Authorization Server is servlet-only (we embed it, FR2); Spring Data JPA/Flyway style code would become R2DBC; the pipeline (`CallStep.handle` returning a value) would become `Mono` chains; MDC and the `ScopedValue` call trace need extra context propagation. Much harder to read and defend live. |
| **Spring MVC on platform threads** | Simplest, but held approvals would use up the Tomcat pool and block every other call. |

## Consequences

**Good**
- Plain, readable, step-by-step code (`CallStep`, `chain.proceed`), easy to debug and to explain.
- Per-call context works naturally: MDC (`callId`, `agentId`) and `ScopedValue` `CallTrace.CURRENT` live with the request's thread.
- A held call costs a parked virtual thread, not a platform thread.
- `ResilientForwardStep` uses `Executors.newVirtualThreadPerTaskExecutor()` so the time limiter can stop waiting cheaply.

**Bad**
- The DB pool (HikariCP, default 10) is still a hard limit; virtual threads do not make more connections. Long work must not sit inside a transaction (`ApprovalService.open` commits before the wait; `open-in-view: false`).
- Pinning: code that blocks inside `synchronized` on older JDKs pinned the carrier; Java 25 (JEP 491) removes most of this, but native calls can still pin.
- Thread dumps can contain thousands of threads; tools must handle that.

## Evidence

- [`docs/evidence/qa2-performance.md`](../evidence/qa2-performance.md) (run of 2026-11-26): 100 calls/s, gateway p95 7.16 ms vs. baseline 1.83 ms → **5.33 ms overhead** (limit 30 ms), 0 failed, 0 dropped iterations, whole stack on one laptop. Raw numbers: [`qa2/overhead.json`](../evidence/qa2/overhead.json).
- `docs/evidence/c1-one-command.md`: whole system starts in ~1 min with `docker compose up` on the laptop.
- **Not measured:** the same load on WebFlux (no prototype was built, so the comparison is by argument, not by measurement), and QA2 load while many calls are held.
