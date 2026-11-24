# Limits (FR6) and kill switch (FR8)

Both are steps in the call pipeline. The kill switch is the **first** step, then the limits.

```
call → KillSwitchStep (100) → LimitStep (150) → SchemaValidationStep (200) → PolicyStep (300) → … → ForwardStep
```

## 1. Tool classification: READ or WRITE

Every tool has an `accessMode`:

| Tool | Default | Why |
|---|---|---|
| REST, method `GET` | `READ` | only fetches data |
| REST, any other method | `WRITE` | may change something (fail safe) |
| MCP | `WRITE` | we cannot see what it does |

An admin can override it with `PUT /api/admin/tools/{id}` and `"accessMode": "READ"` or `"WRITE"`.
If the field is **omitted** on a PUT, the default from the HTTP method is used again. Send the field on every PUT to keep an override.
A re-import from OpenAPI recomputes it only when the HTTP method changes.

## 2. Limits

| Limit id | Counts | Window | Default | Config key |
|---|---|---|---|---|
| `agent-rate-per-minute` | calls per agent | 1 minute | 60 | `agentgate.limits.agent-per-minute` |
| `tool-rate-per-minute` | calls per tool | 1 minute | 120 | `agentgate.limits.tool-per-minute` |
| `agent-daily-quota` | calls per agent | 1 UTC day | 1000 | `agentgate.limits.agent-daily-quota` |
| `tool-daily-quota` | calls per tool | 1 UTC day | 5000 | `agentgate.limits.tool-daily-quota` |
| `write-blast-radius-per-hour` | **WRITE** calls per agent | 1 hour | 20 | `agentgate.limits.writes-per-hour-per-agent` |

The three terms mean different things:

- **Rate limit** (per minute): how *fast* an agent or tool may be called. It stops floods.
- **Daily quota**: how *much* in total per day. It caps the cost of a slow loop that a rate limit would not catch.
- **Blast-radius cap** (writes per hour): how much *damage* an agent can do with changes. READ calls do not count.

Rules of the implementation:

- **Fixed windows** aligned to the clock (a day starts at 00:00 UTC). A window resets at once, so a burst at the end of one window and the start of the next can go through (up to 2× the limit). The simple counter is the price of simplicity.
- **Every attempt counts**, including calls that are then blocked. Retrying a blocked tool does not give free calls.
- The checks run in the order of the table. The first breach stops the call, so the later counters do not count that call.
- A breach returns **429 Problem Details** with `limit`, `max`, `tool`, `resetsAt`, `retryAfterSeconds` and a `Retry-After` header.
- The first breach in each window publishes `LimitBreachedEvent`, so there is **one alert per breach**, not one per blocked call.

Alerts: `GET /api/admin/alerts` (paged, newest first).

### Why the limit types are plain values, not a Strategy class each

The five limits differ only in data (who is counted, the window length, the maximum, WRITE-only). They are one record, `LimitRule`, built from the configuration. A Strategy per limit would be five classes with the same code. If a limit ever needs different *logic* (for example a token bucket that refills), that is the time to add a Strategy.

## 3. Kill switch

| Admin call | Effect |
|---|---|
| `PUT /api/admin/kill-switch/agents/{clientId}` with `{"engaged": true}` | that agent gets 403 on every call |
| `PUT /api/admin/kill-switch/tools/{name}` | that tool gets 403 for every agent |
| `PUT /api/admin/kill-switch/all-writes` | every WRITE tool gets 403; READ tools keep working |
| `GET /api/admin/kill-switch` | list of switches (paged), engaged and released |

Setting `"engaged": false` releases a switch. The target must exist (404 otherwise), so a typo cannot silently do nothing. The denial names the rule: `kill-switch-agent`, `kill-switch-tool` or `kill-switch-all-writes`.

### Why it is the first step and works instantly

- A disabled agent or tool must not reach anything else. If the kill switch ran after the limits, a killed agent would still use up its quota and the alerts would fire for calls that were never allowed.
- In an emergency, it must not depend on a database read per call. The state is a set in memory (`KillSwitchState`), loaded from PostgreSQL at startup. An admin change updates the set in the same request, so the next call is already blocked. There is no cache refresh to wait for, unlike the policy cache (every 5 s).
- Tests prove both: a killed call does not consume the write cap (`killedCallsDoNotUseUpTheLimitsBecauseTheKillSwitchRunsFirst`), and a tool kill takes effect on the very next call.

### Event for the approvals feature (TODO)

Turning a switch ON publishes `admin.KillSwitchActivated(scope, target)`. It is published only on the change OFF → ON.

> **TODO (FR4, approvals, not built yet):** the approval module must listen to `KillSwitchActivated` and cancel every pending approval the switch covers: `AGENT` → that agent's, `TOOL` → that tool's, `ALL_WRITES` → every pending WRITE approval. Until then, a pending approval can still be approved after a kill switch is on. Approving it must be blocked when the approval is completed (the kill switch is checked again by the forward step, or the approval module checks it first).

## 4. Known limits of this version

- **Per instance.** Counts and the kill-switch set live in one JVM. With two gateway instances, each counts on its own, and a switch reaches the other instance only after a restart. See `docs/adr/ADR-candidate-in-memory-limits.md`.
- Counts are **lost on restart**. A restart gives every agent a fresh window.
- Per-agent or per-tool **overrides** of the limits are not built (bonus, skipped for time).
- The kill-switch memory update happens inside the database transaction. If the commit fails after the memory update, memory and database disagree until the next restart. The window is very small, and the fix is to update memory after commit (`TransactionSynchronization`).

## 5. Try it locally

With `docker compose up -d` and the gateway running (`./mvnw -pl gateway spring-boot:run`):

```bash
# 1. Kill one tool, call it, release it
curl -u admin:admin-local-only -X PUT http://localhost:8080/api/admin/kill-switch/tools/getOrder \
     -H "Content-Type: application/json" -d '{"engaged": true}'
# → 403, "rule": "kill-switch-tool"
curl -u admin:admin-local-only -X PUT http://localhost:8080/api/admin/kill-switch/tools/getOrder \
     -H "Content-Type: application/json" -d '{"engaged": false}'

# 2. Alerts after a breach (the integration test LimitsIntegrationTest shows the 21st write)
curl -u admin:admin-local-only "http://localhost:8080/api/admin/alerts?size=5"
```

Tests: `./mvnw -pl gateway test -Dtest='LimitStepTest,LimitsIntegrationTest,KillSwitchIntegrationTest'`.
