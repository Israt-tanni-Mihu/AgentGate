# AgentGate — evidence report (C9)

Date: 2026-11-26. Every number below is copied from a file under `docs/evidence/` (linked). Where there is no
evidence file for a claim, the row says **PARTIAL** and names what is missing. Test names refer to
`gateway/src/test/java/com/agentgate/gateway/` (shortened to `T/` in this file).

**Status meaning:** PASS = requirement met and a captured run (or, for C6/C8, the artefact itself) shows it.
PARTIAL = built and covered by automated tests, but part of the requirement has no captured run output.
PENDING = not measured yet.

## 1. Summary

| ID | Requirement (one line) | Status | Key result | Evidence | How to reproduce |
|---|---|---|---|---|---|
| FR1 | Tool registry: REST (OpenAPI import) + MCP servers, one MCP endpoint | PASS | OpenAPI import → 5 tools seeded on first start; MCP server `shop-parcels` imported; `/mcp` gives a different `tools/list` per agent | [mcp-endpoint.md](evidence/mcp-endpoint.md), [mcp-downstream.md](evidence/mcp-downstream.md), [c1-one-command.md](evidence/c1-one-command.md) | `bash scripts/mcp-demo.sh` |
| FR2 | Agents authenticate with OAuth2 client credentials; end user carried | PASS | Token 200 (300 s JWT); no / wrong / malformed token → 401; `whoami` returns agent + end user | [fr2-oauth2.md](evidence/fr2-oauth2.md) | `./mvnw -pl gateway test -Dtest=AgentIdentityIntegrationTest` |
| FR3 | Policies: default deny, argument conditions, versions, hot reload ≤ 10 s, dry-run | PARTIAL | Default deny and conditions shown live (refund > 2000 and cross-customer read → 403 `default-deny`). Hot reload and dry-run: **tests only**, no captured run | [qa1-redteam.md](evidence/qa1-redteam.md), [fr7-audit.md](evidence/fr7-audit.md) | `./mvnw -pl gateway test -Dtest=CallPipelineIntegrationTest` |
| FR4 | Human approval: hold, approve / reject / edit, timeout, webhook | PARTIAL | Held → approved → agent got 201 (`latencyMs` 2356); MAL-04/05/07 held → rejected → 403. Edit, timeout, webhook, kill-switch cancel: **tests only** | [fr7-audit.md](evidence/fr7-audit.md), [qa1-redteam.md](evidence/qa1-redteam.md) | `./mvnw -pl gateway test -Dtest=ApprovalIntegrationTest,ApprovalWebhookNotifierTest` |
| FR5 | Guards: schema validation, redaction by clearance, truncation | PARTIAL | 9/9 argument-smuggling cases → 400; email / phone / NID masked (`r***@example.com`, `017******78`, `19******21`). Truncation: **test only** | [qa1-redteam.md](evidence/qa1-redteam.md), [mcp-endpoint.md](evidence/mcp-endpoint.md) | `./mvnw -pl gateway test -Dtest=GuardPipelineIntegrationTest,RedactorsTest` |
| FR6 | Rate limits, daily quotas, write blast-radius cap, alert | PARTIAL | MAL-08: 25 refunds → 20 held, then 5 blocked with 429 `write-blast-radius-per-hour`. Rate/quota and the stored alert: **tests only** | [qa1-redteam.md](evidence/qa1-redteam.md) | `./mvnw -pl gateway test -Dtest=LimitsIntegrationTest,LimitStepTest` |
| FR7 | Hash-chained audit: search, export, verify | PASS | Lookup by callId, filtered search, CSV/JSON export; editing row 2 → `BROKEN at sequence number 2` (exit 1) | [fr7-audit.md](evidence/fr7-audit.md) | `.\scripts\audit-verify.ps1` |
| FR8 | Kill switch: agent / tool / all writes; cancels pending approvals | PARTIAL | MAL-30: killed tool → 403 `kill-switch-tool`. Agent and all-writes switches, approval cancel: **tests only** | [qa1-redteam.md](evidence/qa1-redteam.md) | `./mvnw -pl gateway test -Dtest=KillSwitchIntegrationTest,ApprovalIntegrationTest` |
| FR9 | Policy playground: decision + rule for active or draft policy | PARTIAL | 6 tests (allowed 1500, denied 5000 with reason, draft does not affect live traffic). **No evidence file** | [T/playground/PolicyPlaygroundIntegrationTest.java](../gateway/src/test/java/com/agentgate/gateway/playground/PolicyPlaygroundIntegrationTest.java) | `./mvnw -pl gateway test -Dtest=PolicyPlaygroundIntegrationTest` |
| FR10 | Per-tool timeouts, circuit breakers, hide down tools | PASS | 4 timeouts (~3.04 s, 504) → breaker OPEN → 503 in 0.034 s, tool hidden; back after HALF_OPEN + 3 good calls | [fr10-health.md](evidence/fr10-health.md) | `./mvnw -pl gateway test -Dtest=ToolHealthIntegrationTest` |
| QA1 | 30 malicious calls 100% blocked/held; ≤ 5% of 200 legit wrongly blocked | PASS | **30/30** blocked or held; **0/200 (0.0%)** wrongly blocked | [qa1-redteam.md](evidence/qa1-redteam.md) | `docker compose -p agentgate-qa1 --profile demo run --rm -T agent-sim qa1` (stack up first) |
| QA2 | Gateway adds ≤ 30 ms p95 at 100 calls/s | PASS | p95 7.16 ms − baseline 1.83 ms = **5.33 ms** overhead; 0% errors, 0 dropped | [qa2-performance.md](evidence/qa2-performance.md), [qa2/overhead.json](evidence/qa2/overhead.json) | `.\scripts\qa2-performance.ps1` |
| QA3 | Find who / for whom / what / which rule in < 1 min; edited row detected | PARTIAL | One GET by callId returns all four answers; edited row detected at seq 2. **The "< 1 min" time was not stop-watched** | [fr7-audit.md](evidence/fr7-audit.md) | `.\scripts\audit-verify.ps1` |
| QA4 | Store down: medium/high fail closed; low-risk on last policies ≤ 5 min | PASS | Refund → 503 `fail-closed-policy-store`; read → 200 `stale-policy-allowed`; read → 503 after max-stale; chain OK after recovery (50 records) | [qa4-failsafe.md](evidence/qa4-failsafe.md) | `bash scripts/qa4-failsafe-demo.sh 60` |
| QA5 | Classmate adds a new REST tool in < 10 min, no code changes | PENDING | Runbook and check script ready; no classmate run yet | [qa5-template.md](evidence/qa5-template.md) | `.\scripts\qa5-start.ps1` then `.\scripts\qa5-check.ps1` |
| C1 | Whole system with one `docker compose up` on an 8 GB laptop | PARTIAL | 3 containers healthy, `up --build` in 58 s, demo agent exit 0. **Memory use vs 8 GB not measured** | [c1-one-command.md](evidence/c1-one-command.md) | `docker compose up --build` |
| C2 | No secrets in the repo; config from env vars | PASS | `.env` git-ignored; `docker` profile has no secret defaults; compose refuses to start without `.env` | [c1-one-command.md](evidence/c1-one-command.md), [../.env.example](../.env.example) | `git ls-files .env` (prints nothing) |
| C4 | JSON logs with call id, metrics | PARTIAL | JSON logs carry `callId` + `agentId`; Prometheus counters per tool/decision; `/actuator/prometheus` admin-only. **No tracing (traceId/spanId)** | [c4-observability.md](evidence/c4-observability.md) | `./mvnw -pl gateway test -Dtest=MetricsIntegrationTest` |
| C5 | Tests + CI runs everything | PARTIAL | CI runs `./mvnw -B verify` (unit, Testcontainers, ArchUnit) and builds all images. Local `./mvnw verify` on 2026-11-26: 199 gateway + 24 mock-tools tests, 0 failures, BUILD SUCCESS (2:41 min). **QA1/QA2/QA4 scripts are not in CI; no CI run log saved** | [../.github/workflows/ci.yml](../.github/workflows/ci.yml) | `./mvnw verify` |
| C6 | Architecture rules as tests | PASS | 8 ArchUnit rules (no cycles, steps independent, only `policy` touches policy tables, …); part of `verify` | [T/ArchitectureTest.java](../gateway/src/test/java/com/agentgate/gateway/ArchitectureTest.java) | `./mvnw -pl gateway test -Dtest=ArchitectureTest` |
| C7 | C4 diagrams, ≥ 4 ADRs with evidence, utility tree ≥ 6 scenarios | PASS | Context / container / component diagrams; 5 accepted ADRs + 1 candidate; utility tree with 8 scenarios | [architecture/](architecture/c4-context.md), [adr/](adr/0001-synchronous-approval-hold.md), [utility-tree.md](architecture/utility-tree.md) | – (documents) |
| C8 | Pattern table tied to requirements | PASS | 8 patterns, each with classes, requirement, rejected alternative and test | [patterns.md](patterns.md) | – (document) |
| C11 | AI-usage log with ≥ 3 wrong-AI cases | PASS | 6 cases (3 found by the QA1 red-team suite) | [AI-USAGE-LOG.md](AI-USAGE-LOG.md) | – (document) |

## 2. Quality attributes

### QA1 — Security (red team)

- **Measure:** 30 scripted malicious calls 100% blocked or held; ≤ 5% of 200 legitimate calls wrongly blocked.
- **How:** `agent-sim` runner (`Qa1Runner`) sends the cases in `qa/redteam/malicious.json` and `legitimate.json` over REST and MCP to the real gateway in Docker. Each call is judged by its own audit row.
- **Result:** **30/30** (27 blocked, 3 held and rejected) and **0/200 (0.0%)** false blocks. PASS.
- **Failed run first:** MAL-14 and MAL-15 got through and MAL-22 hung the audit writer (ReDoS). All three fixed in code with tests before the final run. See the "Failed run" table in [qa1-redteam.md](evidence/qa1-redteam.md).

### QA2 — Performance

- **Measure:** gateway adds ≤ 30 ms at p95 at 100 calls/s, excluding approval waits.
- **How:** k6 `constant-arrival-rate` at 100/s, 15 s warm-up + 60 s measured. Same call sent straight to mock-tools (baseline) and through the full gateway pipeline. LOW-risk `getCustomer`, so no approval wait.
- **Result:** gateway p95 7.16 ms − baseline p95 1.83 ms = **5.33 ms** (limit 30 ms), 6001 measured requests, 0% errors. PASS. Raw k6 output: [qa2/gateway-output.txt](evidence/qa2/gateway-output.txt), [qa2/baseline-output.txt](evidence/qa2/baseline-output.txt).
- **Note:** an earlier run gave 8.81 ms; no code changed between runs (laptop variance).

### QA3 — Auditability

- **Measure:** find who / on whose behalf / what / which rule for any call in < 1 min; editing a stored row is detected by a verify command.
- **How:** the agent receives an `X-Call-Id` header. `GET /api/admin/audit/{callId}` returns `agentId`, `endUserId`, `toolName`, `arguments`, `ruleId`, `policyVersion`, approval details. Then one row was changed with `psql` and `scripts/audit-verify.ps1` was run.
- **Result:** the lookup is **one HTTP request** and answers all four questions. Tamper test: `AUDIT CHAIN BROKEN at sequence number 2`, exit code 1. **PARTIAL:** the "< 1 minute" was not timed with a stopwatch, and no one other than the developer did the lookup.

### QA4 — Fail-safe

- **Measure:** policy store unreachable → medium/high-risk tools fail closed; low-risk tools work on last known policies for up to 5 min.
- **How:** `scripts/qa4-failsafe-demo.sh 60` really stops the PostgreSQL container. The gateway was started with `POLICY_MAX_STALE=60s` to make the run short (default is `5m` in `application.yml`; same code path).
- **Result:** `issueRefund` (HIGH) → **503** `fail-closed-policy-store`; `getCustomer` (LOW) → **200** `stale-policy-allowed`; after max-stale → **503**; health `policyStore: STALE`; after restart → 200, queued audit records written, `verify` OK (50 records). PASS.

### QA5 — Adoption

- **Status: PENDING.** Runbook ([qa5-runbook.md](qa5-runbook.md)), start/check scripts and the evidence template ([qa5-template.md](evidence/qa5-template.md)) are ready. A classmate has not done the timed run yet.

## 3. Known limitations

| # | Limitation | Impact | How we would fix it |
|---|---|---|---|
| 1 | Deleting the **last** audit rows (or rebuilding the whole chain) is not detected by the hash chain alone. | Someone with DB write access can hide the most recent calls. | Store the latest `seq` + `hash` outside the DB (file / signed message) and compare in `verify`; give the app DB user INSERT + SELECT only. ([patterns.md](patterns.md), audit note) |
| 2 | Single async audit writer: records still in the in-memory queue are lost if the JVM crashes; a full queue (10 000) drops new records (counted in `agentgate_audit_dropped_total`). | Calls during a crash or a very long DB outage may have no audit row. | Transactional outbox table written on the call path, hashed by a background job ([ADR-0002](adr/0002-single-writer-async-audit.md)). |
| 3 | One gateway instance only: limits, kill-switch state, approval waiters and the audit writer live in one JVM. | A second instance would count limits separately, miss kill switches until restart, and race the audit chain (fails loudly on `seq` key). | Redis for counters/kill switch, a shared audit writer or outbox ([ADR-candidate](adr/ADR-candidate-in-memory-limits.md)). |
| 4 | Held calls are lost on restart (agent gets a connection error). | Agent must call again. Orphaned PENDING rows are now cancelled and audited on startup. | Accepted ([ADR-0001](adr/0001-synchronous-approval-hold.md)); async 202 + poll would avoid it at the cost of agent complexity. |
| 5 | Write-tool timeout gives an unknown outcome (refund may have happened, audit says `ERROR 504`). No retries. | A reviewer must check the tool. | Idempotency keys (`callId`) in downstream tools ([ADR-0004](adr/0004-no-retry-on-write-tools.md)). |
| 6 | Cross-customer reads of orders/profiles are **forwarded** and only the result is withheld; cross-customer refunds are only **held**, not blocked. | The downstream tool sees the request; a human must spot the wrong customer. | Ownership lookup before forwarding (tool would need an "owner of order" endpoint). ([qa1-redteam.md](evidence/qa1-redteam.md)) |
| 7 | The end user in `X-On-Behalf-Of` is stated by the agent, not proven. | A compromised agent can claim any customer. | Signed end-user token (token exchange) — out of scope. |
| 8 | Fixed-window limits allow up to 2× the limit across a window edge; counts reset on restart. | Short bursts can exceed a limit. | Sliding window or token bucket (Bucket4j). ([limits-and-kill-switch.md](limits-and-kill-switch.md)) |
| 9 | No distributed tracing (traceId/spanId). | Cross-service debugging relies on `callId`. | Add the Micrometer tracing bridge ([c4-observability.md](evidence/c4-observability.md)). |
| 10 | QA2 measured one tool, one agent, one instance on a shared laptop; not measured with many held calls at once. | Overhead for write tools / large results is unknown. | k6 mix scenario + 500 held calls during load ([ADR-0001](adr/0001-synchronous-approval-hold.md)). |
