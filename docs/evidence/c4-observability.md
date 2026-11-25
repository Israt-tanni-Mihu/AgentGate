# C4 observability evidence: JSON logs and metrics

Date: 2026-11-25. Stack: `docker compose up -d --build` (docker profile), then `docker compose --profile demo run --rm agent-sim`
(one allowed call, one denied, one MCP downstream call, one HIGH-risk refund held and approved). Stack stopped afterwards.

## What changed
* `docker` profile: JSON logs (Spring Boot's built-in `logstash` layout) for gateway and mock-tools; `local` keeps plain logs, now with `[callId]`.
* `CallPipeline` puts `callId` and `agentId` into the MDC at the start and removes them in `finally`; `AuditWriter` does the same on its own thread.
* `CallMetrics` (new) records the call counters and the latency timer from the same place the audit event is made.
* Gauges: pending approvals (new), audit queue size/dropped (existing), circuit breaker state (Resilience4j's own binding, already exposed).
* Security: `/actuator/prometheus` added to the admin chain (it is a new endpoint, so before this change it did not exist; `/actuator/metrics` was already admin-only).
* Test: `MetricsIntegrationTest` (counters move for allowed/denied/held, unknown tool names are not tags, prometheus is admin-only).
  `./mvnw verify` green (195 gateway + 20 mock-tools tests).

## Access control (curl against /actuator/prometheus)
```
no auth:     401
agent token: 401
approver:    403
admin:       200
```
`/actuator/health` stays public (needed by the Docker health check); it shows component status only, no details.

## Prometheus output after the demo (selected lines)
```
agentgate_approvals_pending 0.0
agentgate_audit_dropped_total 0.0
agentgate_audit_queue_size 0.0
agentgate_calls_total{decision="allowed",risk="low",tool="getOrder"} 1.0
agentgate_calls_total{decision="allowed",risk="low",tool="track_parcel"} 1.0
agentgate_calls_total{decision="denied",risk="low",tool="getCustomer"} 1.0
agentgate_calls_total{decision="held",risk="high",tool="issueRefund"} 1.0
agentgate_calls_blocked_total{step="PolicyStep"} 1.0
agentgate_pipeline_latency_seconds_count{decision="allowed"} 2
agentgate_pipeline_latency_seconds_sum{decision="allowed"} 0.832510492
agentgate_pipeline_latency_seconds_count{decision="denied"} 1
agentgate_pipeline_latency_seconds_sum{decision="denied"} 0.008335177
agentgate_pipeline_latency_seconds_count{decision="held"} 1
agentgate_pipeline_latency_seconds_sum{decision="held"} 0.645922204
resilience4j_circuitbreaker_state{name="getOrder",state="closed"} 1.0
resilience4j_circuitbreaker_state{name="getOrder",state="open"} 0.0
resilience4j_circuitbreaker_state{name="issueRefund",state="closed"} 1.0
resilience4j_circuitbreaker_state{name="issueRefund",state="open"} 0.0
resilience4j_circuitbreaker_state{name="track_parcel",state="closed"} 1.0
resilience4j_circuitbreaker_state{name="track_parcel",state="open"} 0.0
```
Note `decision="held"` for the refund although the approver approved it: `held` means "needed a human". Its latency (0.65 s)
contains the approval wait, which is why QA2 must read `decision="allowed"` only.

## One call's full story from the logs (callId f59728ec-..., the held refund)
```
{"timestamp":"2026-11-25T07:36:01.639983572Z","message":"Unknown keyword example - you should define your own Meta Schema. If the keyword is irrelevant for validation, just use a NonValidationKeyword or if it should generate annotations AnnotationKeyword","logger":"com.networknt.schema.keyword.UnknownKeywordFactory","thread_name":"tomcat-handler-9","level":"WARN","callId":"f59728ec-f3df-499d-8e5d-b191ac7387b6","agent
{"timestamp":"2026-11-25T07:36:01.66786721Z","message":"HOLD agent=support-bot tool=issueRefund approvalId=bd5c012e-58e7-422a-8be8-5bbc881e32ac","logger":"com.agentgate.gateway.approval.ApprovalStep","thread_name":"tomcat-handler-9","level":"INFO","callId":"f59728ec-f3df-499d-8e5d-b191ac7387b6","agentId":"support-bot"}
{"timestamp":"2026-11-25T07:36:02.278900233Z","message":"call finished tool=issueRefund decision=ALLOWED step=PolicyStep rule=support-refund-up-to-2000 status=201 latencyMs=645","logger":"com.agentgate.gateway.pipeline.CallPipeline","thread_name":"tomcat-handler-9","level":"INFO","callId":"f59728ec-f3df-499d-8e5d-b191ac7387b6","agentId":"support-bot"}
```
(first line is an unrelated schema-library warning that also carries the callId: this is exactly the point of the MDC.)
Find it with: `docker logs agentgate-gateway | grep <callId>`; the audit record has the same id.

## One mock-tools log line
```
{"timestamp":"2026-11-25T07:35:28.088671806Z","message":"Starting MockToolsApplication ...","logger":"com.agentgate.mocktools.MockToolsApplication","thread_name":"main","level":"INFO"}
```

## Not done
* agent-sim keeps plain console output (it is a human-readable demo script, not a service).
* No `traceId`/`spanId`: tracing is not wired in the gateway yet.
* `agentgate_approvals_pending` counts held calls of this instance in memory, not rows in the database.

## Metric reference
(copy of docs/metrics.md at this date)

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

