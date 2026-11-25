# AgentGate metrics (C4)

Scrape: `GET /actuator/prometheus` on the gateway (port 8080). **Admin HTTP Basic only**: no login = 401, an agent token = 401,
an approver = 403. `/actuator/metrics` has the same rule. Only `/actuator/health` (status of components, no details) is public.
Prometheus names: dots become `_`, counters get `_total`, timers become `_seconds_*`.

| Metric (Prometheus name) | Type | Tags | Meaning |
|---|---|---|---|
| `agentgate_calls_total` | counter | `tool`, `decision`, `risk` | Every call that reached the pipeline. `decision` = `allowed` (tool answered), `denied` (a step refused), `error` (tool failed / internal error), `held` (call needed human approval, whatever the human then decided; the final result is in the audit log). `tool` is `unknown` when the name is not in the registry (never the agent-made name, so no tag explosion). `risk` = `low`/`medium`/`high`/`unknown`. |
| `agentgate_calls_blocked_total` | counter | `step` | Calls with decision `denied`, by the step that denied them (`PolicyStep`, `LimitStep`, `KillSwitchStep`, `SchemaValidationStep`, `CallPipeline` for unknown/disabled tool...). |
| `agentgate_pipeline_latency_seconds` | timer + histogram | `decision` | Time inside the gateway for one call, from pipeline start to end (includes the tool call). Same thing QA2's k6 test measures from outside, minus HTTP/token overhead. For QA2 use `decision="allowed"`; `held` includes the human's waiting time. Buckets: `_bucket{le=..}` for `histogram_quantile`. |
| `agentgate_approvals_pending` | gauge | none | Held calls waiting for a human on this instance (read from memory, no DB). |
| `resilience4j_circuitbreaker_state` | gauge | `name` (= tool), `state` | 1 for the breaker's current state (`closed`, `open`, `half_open`, ...), 0 for the others. `open` = tool is down and hidden from agents (FR10). Created when the tool is first called. |
| `agentgate_audit_queue_size` | gauge | none | Audit records waiting in memory. 0 normally; grows while the database is down (QA4). |
| `agentgate_audit_dropped_total` | counter | none | Audit records lost because that queue was full. Must stay 0. |
| `agentgate_policy_store_stale` | gauge | none | 1 while the policy store is unreachable and last known policies are used (QA4). |
| `agentgate_policy_store_age_seconds` | gauge | none | Seconds since the policy store last answered. |

Logs: in the `docker` profile one JSON object per line (`timestamp`, `level`, `logger`, `message`, `thread_name`, and the MDC
fields `callId`, `agentId`). `callId` is the same id as the `X-Call-Id` header and the audit record. In `local` the line shows
`[callId]` after the level. Tracing (`traceId`/`spanId`) is not wired yet; once a tracing bridge is added, Boot puts both into
the same MDC and they appear without code changes.
