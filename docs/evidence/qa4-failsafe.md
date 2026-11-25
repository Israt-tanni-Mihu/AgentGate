# Evidence: QA4 Fail-safe availability — policy store (PostgreSQL) unreachable

- **Date:** 2026-11-25
- **Requirement:** QA4. If the policy store is unreachable, MEDIUM/HIGH-risk tools fail closed; LOW-risk tools keep working on the last known policies for up to 5 minutes (`agentgate.policy.max-stale`).
- **Method:** real outage. `docker compose up -d`, mock-tools (8081) and gateway (8080) started from the built jars, profile `local`. The gateway was started with `POLICY_MAX_STALE=60s` so that step (d) is quick to show. The script `scripts/qa4-failsafe-demo.sh 60` stops and starts the container `agentgate-postgres` (`docker stop` / `docker start`). `docker compose down` at the end.
- **Tools in the demo:** `getCustomer` LOW, `issueRefund` HIGH (the registry already held them from earlier runs).
- **Tests:** `./mvnw verify` green, 176 gateway tests, 0 failures. New: `PolicyStoreFailSafeIntegrationTest` (4 tests: store down → refund 503 and read 200; down longer than max-stale → read 503; store back → normal, queued audit records written in order, chain verifies; health STALE and metric) and `AuditQueueFullTest` (full queue drops loudly and counts).
- **Credentials:** local-only defaults from `application.yml`.

## Raw output of `scripts/qa4-failsafe-demo.sh 60` (exit code 0)

```

=== 0. Get a token while the database is up (new tokens need the database)
token: eyJraWQiOiJmYTNhZDg0...
PASS  low-risk read works while healthy (got 200)
health: "policyStore":{"status":"UP"}

=== 1. docker stop agentgate-postgres

=== (a) low-risk read on last known policies
PASS  getCustomer (got 200)
{"id":1,"name":"Rahim Uddin","email":"r***@example.com","phone":"017******78","nationalId":"19******21","city":"Dhaka"}

=== (b) refund fails closed
PASS  issueRefund (got 503)
{"detail":"policy store unavailable, failing closed (HIGH risk tool)","instance":"/api/agent/calls/issueRefund","status":503,"title":"Policy store unavailable","rule":"fail-closed-policy-store","tool":"issueRefund"}

=== (c) health
{"components":{"db":{"status":"DOWN"},"diskSpace":{"status":"UP"},"livenessState":{"status":"UP"},"ping":{"status":"UP"},"policyStore":{"status":"STALE"},"readinessState":{"status":"UP"},"ssl":{"status":"UP"}},"groups":["liveness","readiness"],"status":"DOWN"}
PASS  policyStore status (got "STALE")
"measurements":[{"statistic":"VALUE","value":1.0}]
"measurements":[{"statistic":"VALUE","value":2.0}]

=== (d) wait until the last known policies are older than max-stale (60s)
PASS  getCustomer after max-stale (got 503)
{"detail":"policy store unavailable, failing closed (last known policies are older than PT1M)","instance":"/api/agent/calls/getCustomer","status":503,"title":"Policy store unavailable","rule":"fail-closed-policy-store","tool":"getCustomer"}

=== (e) docker start agentgate-postgres
"policyStore":{"status":"UP"}
PASS  getCustomer after recovery (got 200)
PASS  issueRefund after recovery (not 503) (got true)

=== audit chain after recovery
{"status":"OK","records":50,"firstBrokenSeq":null,"problem":null}
PASS  audit verify (got OK)
fail-safe rows in the audit log:
"toolName":"getCustomer"	"ruleId":"support-own-customer-only"	"httpStatus":200
"toolName":"getCustomer"	"ruleId":"fail-closed-policy-store"	"httpStatus":503
"toolName":"issueRefund"	"ruleId":"fail-closed-policy-store"	"httpStatus":503
"toolName":"getCustomer"	"ruleId":"stale-policy-allowed"	"httpStatus":200
"toolName":"getCustomer"	"ruleId":"support-own-customer-only"	"httpStatus":200
"toolName":"issueRefund"	"httpStatus":201	"ruleId":"support-track-parcel"
"httpStatus":200	"toolName":"getCustomer"	"ruleId":"default-deny"
"httpStatus":403		
```

## What the output shows

| Step | Expected | Result |
|---|---|---|
| (a) DB stopped, low-risk `getCustomer` with a token issued earlier | 200 on last known policies | 200; audit rule `stale-policy-allowed` |
| (b) DB stopped, `issueRefund` | 503 Problem Details, fail closed | 503, `rule: fail-closed-policy-store` |
| (c) health | `policyStore` = STALE, metric 1 | STALE (overall `db` DOWN), `agentgate.policy.store.stale` = 1, `agentgate.audit.queue.size` = 2 |
| (d) after max-stale (60 s) | low-risk also 503 | 503, "last known policies are older than PT1M" |
| (e) DB started | normal again, queued audit records written, chain OK | 200, health UP, `verify` → `OK`, 50 records |

Notes
- The token was issued **before** the outage. JWT signature/expiry checking needs no database; the agent lookup after it comes from `AgentDirectory` (last known copy). Issuing a NEW token during an outage fails, which QA4 accepts.
- The audit rows made during the outage (`stale-policy-allowed`, `fail-closed-policy-store`) were kept in memory and written in order after recovery; `verify` confirms the hash chain.
- Detection: any database failure on the call path (tool lookup, agent lookup, audit write) flips the state at once; the 5 s policy refresh is the probe that notices recovery.
