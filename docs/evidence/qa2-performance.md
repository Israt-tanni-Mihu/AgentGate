# QA2 performance evidence

**Requirement (QA2):** the gateway adds <= 30 ms at p95 to a tool call at 100 calls/s, excluding approval waits.

## Run details

| Item | Value |
|---|---|
| Date | 2026-11-26 |
| Machine | Intel Core i5-1135G7 @ 2.40 GHz (4 cores / 8 threads), 15.7 GB RAM, Windows 11 Pro |
| Docker | Docker Desktop, engine 29.9.3; Docker VM: 8 CPUs, 7.6 GB |
| k6 | v2.4.0 (`grafana/k6:2.4.0` image, on the compose network) |
| Load | 100 requests/s, constant-arrival-rate; 15 s warm-up (not judged) + 60 s measured |

## Result (measured window only, `scenario:measure`)

| Measure | Baseline (mock-tools direct) | Gateway (full pipeline) |
|---|---|---|
| Requests sent (measured / total incl. warm-up) | 6001 / 7501 | 6001 / 7502 |
| Achieved rate | 100.0 /s | 99.8 /s |
| Error rate (`http_req_failed`) | 0.00 % | 0.00 % |
| Failed checks / dropped iterations | 0 / 0 | 0 / 0 |
| p50 (median) | 1.22 ms | 4.91 ms |
| **p95** | **1.83 ms** | **7.16 ms** |
| p99 | 2.26 ms | 9.03 ms |
| max | 6.61 ms | 28.75 ms |

## Overhead calculation

```
overhead = gateway p95 - baseline p95 = 7.16 ms - 1.83 ms = 5.33 ms   (limit 30 ms)  ->  PASS
```

**QA2: PASS.** The gateway adds 5.33 ms at p95. That leaves about 25 ms of headroom under the limit.
k6 also checked this itself: the gateway run had the threshold `p(95) < 31.83 ms` (baseline p95 + 30), and it held.

The earlier evidence (before this re-capture) reported 2.40 / 11.21 ms = 8.81 ms overhead. That is the same order of magnitude.
The difference comes from normal run-to-run variance on a shared laptop. No gateway code was changed between the runs.

## How it was run

```powershell
.\scripts\qa2-performance.ps1
```

The runner does the following:
1. Starts the stack: `docker compose -p agentgate-qa2 -f docker-compose.yml -f qa/k6/docker-compose.qa2.yml up --build -d --wait`.
2. Runs the baseline: `k6 run --quiet --no-color --summary-export /out/baseline-summary.json /scripts/qa2.js` with `MODE=baseline`.
3. Runs the gateway test with the same command, plus `MODE=gateway` and `BASELINE_P95_MS=<baseline p95>`.
4. Prints the overhead and copies the results to `docs/evidence/qa2/`.
5. Stops the stack with `docker compose ... down -v`.

**Note:** don't run it as `.\scripts\qa2-performance.ps1 *> log.txt` in Windows PowerShell 5.1. Redirecting stderr turns
docker's progress messages into errors, and the script stops (`$ErrorActionPreference = "Stop"`). This happened on the first attempt of this capture.

## Raw results (in `docs/evidence/qa2/`)

| File | Content |
|---|---|
| `baseline-summary.json`, `gateway-summary.json` | k6 `--summary-export` (all metrics; the numbers above come from `metrics."http_req_duration{scenario:measure}"`) |
| `baseline-output.txt`, `gateway-output.txt` | k6 end-of-test summary as plain UTF-8 text |
| `overhead.json` | baseline p95, gateway p95, overhead, pass |

In the summary JSON, `"thresholds": {"p(95)<...": false}` means the threshold was **not crossed**, so it passed.
This is k6's export format.

### Excerpt: gateway k6 summary

```
  █ THRESHOLDS

    checks{scenario:measure}
    ✓ 'rate==1' rate=100.00%

    dropped_iterations{scenario:measure}
    ✓ 'count==0' count=0

    http_req_duration{scenario:measure}
    ✓ 'p(95)<31.832801' p(95)=7.16ms

    http_req_failed{scenario:measure}
    ✓ 'rate==0' rate=0.00%

    HTTP
    http_req_duration..............: avg=6.31ms min=3.49ms med=5.23ms p(90)=8.57ms p(95)=10.52ms p(99)=22.03ms max=177.05ms
      { scenario:measure }.........: avg=5.22ms min=3.49ms med=4.9ms  p(90)=6.53ms p(95)=7.16ms  p(99)=9.02ms  max=28.74ms
    http_req_failed................: 0.00%  0 out of 7502
      { scenario:measure }.........: 0.00%  0 out of 6001
    http_reqs......................: 7502   99.790247/s
    dropped_iterations.............: 0      0/s
```

### Excerpt: baseline k6 summary

```
    http_req_duration{scenario:measure}
    ✓ 'p(95)<1000' p(95)=1.83ms

    HTTP
    http_req_duration..............: avg=1.42ms min=599.4µs  med=1.28ms p(90)=1.94ms p(95)=2.25ms p(99)=3.59ms max=56.38ms
      { scenario:measure }.........: avg=1.26ms min=599.4µs  med=1.21ms p(90)=1.63ms p(95)=1.83ms p(99)=2.26ms max=6.6ms
    http_req_failed................: 0.00%  0 out of 7501
    http_reqs......................: 7501   100.008833/s
```

## Test design

* **Call:** `getCustomer` for customer 1, made by `support-bot` acting for customer 1. It is a LOW-risk tool with an allowing policy
  rule (`support-own-customer-only`), so it never waits for a human. That is how "excluding approval waits" is respected.
  The call still runs the whole pipeline: kill switch, tool availability, limits, schema validation, policy, the approval step
  (which passes a low-risk call), forward to mock-tools, ownership check, redaction (the record has an email, phone and national ID),
  truncation, and the audit event.
* **Load:** k6 `constant-arrival-rate` at 100 iterations/s, so the rate does not drop when answers get slower. Thresholds look only
  at the `measure` scenario. The warm-up handles JIT and connection pools.
* **Token:** one real OAuth2 client-credentials token from `setup()`, reused for the whole run (the token lives 5 min; the test needs 75 s).
* **Baseline:** the same load as `GET /api/customers/1` sent straight to mock-tools. The gateway makes that same call downstream,
  so `gateway p95 - baseline p95` is what the gateway adds.
* **Rate limits:** the default limits (60 calls/min per agent) would block 100 calls/s. `qa/k6/docker-compose.qa2.yml` raises
  only the ceilings for this run. The limit step still runs and counts every call.

## Limits of this evidence

* There is one gateway instance, one tool and one agent. A mix of tools (write calls, large results) would cost more per call.
* All containers, including k6, share one laptop's CPU. That makes this a pessimistic setup, not a dedicated one.
* The p95 is measured at the client, so it includes the HTTP round trip. Audit writing is asynchronous, so it is not in the call latency.
* The warm-up is excluded from judging. Over all requests including warm-up, the gateway p95 was 10.52 ms and p99 was 22.03 ms
  (JIT start-up). Both are still under the limit.
