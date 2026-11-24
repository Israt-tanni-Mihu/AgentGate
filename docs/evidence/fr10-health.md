# FR10 evidence — timeouts, circuit breakers, hidden tools

- **Date:** 2026-11-25. Manual run with `curl`; PostgreSQL from `docker compose up -d`; mock-tools (port 8081) and gateway (port 8080) from `./mvnw -q -pl <module> spring-boot:run`, profile `local`. `docker compose down` at the end.
- **Settings (application.yml, `agentgate.resilience`):** default timeout 3 s, window = last 10 calls, judged after 5 calls, opens at ≥ 50 % failures, stays open 15 s, then 3 trial calls (HALF_OPEN).
- **Chaos switch of mock-tools:** `POST http://localhost:8081/admin/chaos {"delayMs":4000,"failRate":0}` makes every tool call take 4 s (longer than the 3 s timeout).
- **Agent:** `support-bot` (token from `/oauth2/token`). Admin: `admin`.
- **Mistake made while preparing the demo (kept on purpose):** my first run sent `{"id":"1001"}` (a string). The imported schema wants an integer, so every call was answered `400 Invalid arguments` and never reached the breaker. This also shows that **bad arguments do not count as tool failures**. Fixed to `{"id":1001}`.

## (a) Normal

```
$ GET /api/agent/tools
"name":"getCustomer" "name":"getOrder" "name":"issueRefund"
$ POST /api/agent/calls/getOrder {"id":1001}
HTTP 200  time=0.355335s            (first call, cold JVM; later calls take ~0.05 s)
{"id":1001,"customerId":1,"items":["Walton smartphone"],"total":18500.0,"currency":"BDT","status":"DELIVERED", ... "deliveryPhone":"017******78"}
$ GET /api/admin/tools/health
  getOrder     state=CLOSED    visible=True  failureRate=None calls=1 failed=0 refused=0 timeoutMs=3000
  (the other tools: CLOSED, calls=0)
```

## (b) Chaos on → timeouts → breaker opens → tool disappears

```
$ POST /api/admin/... chaos {"delayMs":4000}
$ call #1 getOrder   HTTP 504  time=3.046s  {"title":"Tool timed out","rule":"tool-timeout","detail":"Tool 'getOrder' did not answer within 3000 ms."}
$ call #2 getOrder   HTTP 504  time=3.042s
$ call #3 getOrder   HTTP 504  time=3.033s
$ call #4 getOrder   HTTP 504  time=3.043s
$ call #5 getOrder   HTTP 503  time=0.034s  {"title":"Tool temporarily unavailable","rule":"circuit-open", ...}
$ call #6 getOrder   HTTP 503  time=0.031s
$ GET /api/agent/tools
"name":"getCustomer" "name":"issueRefund"          <- getOrder is hidden
```

The breaker opened after the 4th timeout: 1 success + 4 failures = 5 calls, 80 % ≥ 50 %. Call #5 was refused in 34 ms (the slow tool was not called any more; 3 s → 0.03 s).

## (c) Health, metrics, audit

```
$ GET /api/admin/tools/health
  getOrder     state=OPEN      visible=False failureRate=80.0 calls=5 failed=4 refused=0 timeoutMs=3000
  getCustomer  state=CLOSED    visible=True  ...
  issueRefund  state=CLOSED    visible=True  ...
  sendSms      state=CLOSED    visible=True  ...

$ GET /actuator/metrics/resilience4j.circuitbreaker.state?tag=name:getOrder
  (gauge, one series per state; value 1.0 = the current state)
  state=closed -> 0.0      state=open -> 1.0      state=half_open -> 0.0

$ GET /api/admin/audit?toolName=getOrder   (newest first, trimmed)
seq 22  ERROR  503  ToolAvailabilityStep   circuit-open   Tool 'getOrder' is temporarily unavailable ...
seq 21  ERROR  503  ToolAvailabilityStep   circuit-open
seq 20  ERROR  503  ToolAvailabilityStep   circuit-open
seq 19  ERROR  504  ResilientForwardStep   tool-timeout   latency 3021 ms   Tool 'getOrder' did not answer within 3000 ms.
seq 18  ERROR  504  ResilientForwardStep   tool-timeout   latency 3009 ms

$ GET /api/admin/audit/verify
{"status":"OK","records":25,"firstBrokenSeq":null,"problem":null}
```

## (d) Chaos off → HALF_OPEN → trial calls → CLOSED → tool is back

```
$ chaos {"delayMs":0}
$ immediately: call getOrder   HTTP 503 (still OPEN, fast)
... 17 s pass; nobody calls the hidden tool ...
$ GET /api/admin/tools/health
  getOrder     state=HALF_OPEN visible=True  ...            <- moved by itself (automatic OPEN -> HALF_OPEN)
$ GET /api/agent/tools
"name":"getCustomer" "name":"getOrder" "name":"issueRefund"   <- back in the list
$ trial call #1  HTTP 200 0.047s
$ trial call #2  HTTP 200 0.055s
$ trial call #3  HTTP 200 0.047s
$ GET /api/admin/tools/health
  getOrder     state=CLOSED    visible=True  calls=0 ...    <- 3 good trial calls closed the breaker (counters reset)
```

## Automated tests (`ToolHealthIntegrationTest`, 9 tests, part of `./mvnw verify`)

| Requirement | Test |
|---|---|
| slow tool → 504 | `aSlowToolAnswers504ProblemDetails` |
| per-tool timeout beats the default, no restart | `aToolsOwnTimeoutBeatsTheDefault` |
| failures → OPEN → fast 503 (tool not called) → hidden from list, health shows OPEN | `repeatedFailuresOpenTheBreakerThenCallsFailFastAndTheToolIsHidden` |
| wait → HALF_OPEN (by itself) → visible → 3 good calls → CLOSED | `afterTheWaitTheToolIsHalfOpenVisibleAgainAndTrialCallsCloseTheBreaker` |
| a failing trial call → OPEN again | `aFailingTrialCallOpensTheBreakerAgain` |
| write tool called **exactly once** on failure / on timeout | `aWriteToolIsCalledExactlyOnceWhenItFails`, `aWriteToolThatTimesOutIsAlsoCalledOnceAndTheAgentIsWarned` |
| 404 answers do not open the breaker | `clientMistakesAndNotFoundAnswersDoNotOpenTheBreaker` |
| tool list = policy ∧ kill switch ∧ breaker | `theToolListShowsOnlyWhatThePolicyAllowsAndHidesKilledTools` |

`./mvnw verify` → gateway 145 tests, mock-tools 19 tests, BUILD SUCCESS.

## Failed run and what changed (C9)

The first full `./mvnw verify` failed in an **old** test, `AuditIntegrationTest.fiftyParallelCallsGiveOneUnbrokenChain` (50 parallel calls → some answered `504`). Cause, found with a thread dump and `-Djdk.httpclient.HttpClient.log`: the JDK HTTP client had received `200` from the fake shop, but the test drove its 50 calls from `ForkJoinPool.commonPool()` threads that now block in `FutureTask.get` (the time limiter), and the HTTP client finishes requests on the common pool too, so it was starved. This does **not** happen in the gateway (Tomcat request threads are virtual threads, not common-pool threads). Fix: the test driver now uses its own executor. Earlier guesses (cold start, HTTP/2 upgrade, platform-vs-virtual threads) were wrong and were reverted.
