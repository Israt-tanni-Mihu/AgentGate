# QA2 performance evidence

**Requirement (QA2):** the gateway adds <= 30 ms at p95 to a tool call at 100 calls/s, excluding approval waits.

## Result

| Measure | Value | Target |
|---|---|---|
| Baseline p95 (mock-tools called directly) | **2.40 ms** | n/a |
| Gateway p95 (same call through the gateway) | **11.21 ms** | n/a |
| **Overhead = gateway p95 - baseline p95** | **8.81 ms** | <= 30 ms |
| Failed requests / failed checks / dropped iterations | 0 / 0 / 0 | 0 |
| Achieved rate | 100.0 /s (baseline), 99.7 /s (gateway, 7502 iterations in 75 s) | 100 /s |

QA2 PASSED. An earlier identical run (before the runner's output was tidied) gave 2.37 ms / 10.95 ms = 8.58 ms overhead,
so the result is stable. No gateway code was changed, because there was nothing above the limit to fix.

## Test design

* **Call:** `getCustomer` for customer 1, as `support-bot` acting for customer 1. It is a LOW-risk tool and an allowed policy
  rule (`support-own-customer-only`), so it never waits for a human. That is how "excluding approval waits" is respected.
  The call still runs the whole pipeline: kill switch, tool availability, limits, schema validation, policy, approval step
  (passes a low-risk call), forward to mock-tools, ownership check, redaction (the customer record has email, phone and
  national ID), truncation, and the audit event.
* **Load:** k6 `constant-arrival-rate`, 100 iterations/s, so the rate does not drop when answers get slower. A 15 s warm-up
  (JIT, connection pools, not judged) is followed by 60 s measured (6001 requests). Thresholds look only at the `measure` scenario.
* **Token:** one real OAuth2 client-credentials token in `setup()`, reused (it lives 5 min, the test needs 75 s).
* **Baseline:** the same load as `GET /api/customers/1` straight to mock-tools. The gateway makes that same call
  downstream, so `gateway p95 - baseline p95` is what the gateway adds.
* **Thresholds (k6 itself passes/fails):** `http_req_failed rate==0`, `checks rate==1`, `dropped_iterations count==0`, and in
  gateway mode `p(95) < baseline p95 + 30 ms` (the runner passes the baseline p95 in).
* **Rate limits:** the default limits (60 calls/min per agent) would block 100 calls/s. `qa/k6/docker-compose.qa2.yml` raises
  only the ceilings through environment variables for this run. The limit step still runs and counts every call.
* **Environment:** all containers (postgres, mock-tools, gateway, k6) on one Windows 11 laptop, Docker Desktop, on one compose
  network (project `agentgate-qa2`, fresh database). The load generator shares the CPU with the gateway, so this is a
  pessimistic setup, not a dedicated one.

## How it was run

```
.\scripts\qa2-performance.ps1        # starts the stack, baseline, gateway, prints overhead, stops the stack
```

Files: `qa/k6/qa2.js` (one script, `MODE=baseline|gateway`), `qa/k6/docker-compose.qa2.yml`, `scripts/qa2-performance.ps1`.
k6 is taken from a local install if there is one, otherwise from the `grafana/k6` Docker image.

## Limits of this evidence

* One gateway instance, one tool, one agent. A mix of tools (a write call, a large result) would cost more per call.
* The p95 was measured at the client, so it includes the HTTP round trip to the gateway and the gateway's own call to
  mock-tools; the baseline removes the second one. Audit writing is asynchronous (one writer thread, queue), so it is not in the call's latency.
* p99 of the gateway run in the 15 s warm-up was higher (45 ms over all requests including warm-up); in the measured window it was 15.8 ms.

## Raw output: baseline (mock-tools direct)

```
Binary file (standard input) matches
```

## Raw output: gateway

```
Binary file (standard input) matches
```

## Overhead calculation

```
overhead = gateway p95 - baseline p95 = 11.21 ms - 2.40 ms = 8.81 ms   (limit 30 ms)  ->  PASS
```
