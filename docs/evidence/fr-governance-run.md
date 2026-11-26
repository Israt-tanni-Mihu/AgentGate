# Evidence: FR3 hot reload + dry-run, FR4, FR5 truncation, FR6, FR8, FR9, QA3 lookup, C1 memory

- **Date:** 2026-11-26. Laptop: Windows 11, Docker Desktop (VM: 8 CPUs, 7.6 GiB).
- **Script:** [`scripts/fr-evidence-demo.sh`](../../scripts/fr-evidence-demo.sh) (curl against the real gateway; header lists the exact commands).
- **Stack:** `docker compose -p agentgate-ev -f docker-compose.yml -f scripts/docker-compose.fr-evidence.yml up --build -d --wait`,
  i.e. the normal one-command stack on a **fresh database** (own project name, so the dev database was not touched), plus an
  override with two demo-only settings: approval timeout **20 s** instead of 2 min (so expiry is quick to show) and, for
  the truncation step only, `AGENTGATE_GUARDS_TRUNCATION_MAXBYTES=100` instead of 8192 (the demo tools have no result
  larger than 8 KB). For the two extra FR6 phases the gateway was restarted with lower limits (agent daily quota 3,
  tool daily quota 5; then tool rate 3/min). All are normal configuration values, no code was changed for them.
- **Code/config changed for this run:** `docker-compose.yml` now sets `APPROVAL_WEBHOOK_URL` for the gateway (default
  `http://mock-tools:8081/hooks/approvals`). Before, the one-command stack sent **no** FR4 webhook.
- **End:** `docker compose -p agentgate-ev ... down -v` (no containers left).

## Result

| Requirement | What was shown | Result |
|---|---|---|
| FR9 playground | refund 1500 / 5000 on the active policy; sendSms on a DRAFT | ALLOW `support-refund-up-to-2000`; DENY `default-deny` with the failed condition (`value: 5000`); DRAFT → ALLOW `support-sms` while the live call is still **403** |
| FR3 hot reload (admin API) | activate v2, then call sendSms | **201 after 523 ms**, no restart |
| FR3 hot reload (DB change, as another instance would) | switch back to v1 with SQL, poll every 250 ms | **403 after 5127 ms** (≤ 10 s; refresh interval 5 s). Other runs the same day: 946 ms, 5611 ms |
| FR3 dry-run | v1 in dry-run, sendSms (would be denied) | **201** and log line `DRY-RUN would DENY ... rule=default-deny`; dry-run off → 403 again |
| FR4 web page | `GET /approvals.html` as approver | **200**, `<title>AgentGate approvals</title>` |
| FR4 edit | refund 1500 held, approver edits to 500 | `APPROVED_WITH_EDITS`, agent got **201, amount 500** |
| FR4 edit re-checked | refund 100 held, approver edits to 5000 | agent got **403** "the approver's edited arguments were blocked ... (value: 5000)" |
| FR4 + FR8 cancel | refund held, admin kills tool `issueRefund` | agent answered **231 ms** after the switch: **403 `approval-cancelled`**; approval state CANCELLED |
| FR4 expiry | refund held, nobody answers (timeout 20 s) | agent got **403 `approval-expired` after 20 147 ms**; state EXPIRED |
| FR4 webhook | mock-tools log | **8 webhooks** for 4 approvals: one "new request" + one "closed: <state>" each |
| FR8 agent kill | support-bot killed | support-bot **403 `kill-switch-agent`**, analytics-bot **200**; released → 200 |
| FR8 all writes | all-writes switch on | issueRefund **403 `kill-switch-all-writes`**, getOrder (READ) **200** |
| FR6 rate limit + alert | analytics-bot, 61 getOrder calls starting at a new minute (limit 60/min) | **60 × 200, 1 × 429** `agent-rate-per-minute`, `Retry-After: 48`; **one alert** `LIMIT_BREACH` stored |
| FR6 agent daily quota | quota lowered to 3/day; support-bot calls getOrder 4 times | 3 × 200, 4th **429 `agent-daily-quota`**, "Try again after 2026-11-27T00:00:00Z" |
| FR6 tool daily quota | getOrder lowered to 5/day (3 used); analytics-bot calls 3 times | 2 × 200, 3rd **429 `tool-daily-quota`**; **2 alerts** stored (one per breach) |
| FR6 tool rate | getOrder lowered to 3/min, shared by two agents | support-bot 2 × 200, analytics-bot 1 × 200 then **429 `tool-rate-per-minute`** |
| FR5 truncation | getCustomer with a 100-byte limit | **200** `truncated: true`, notice "original result was 119 bytes and the limit is 100 bytes", redacted partial result |
| QA3 (machine part) | look up a denied call by its `X-Call-Id` | **HTTP 200 in 0.083 s**, gives who / on whose behalf / what / rule / version; `verify` OK, 25 records |
| C1 memory | `docker stats --no-stream` after the run | gateway 438.2 MiB + mock-tools 231 MiB + postgres 54.7 MiB = **≈ 724 MiB** of the 7.6 GiB Docker VM (idle after the demo, not under load) |

## Failed / odd runs (kept on purpose)

1. **First run:** the QA3 step crashed in the script itself (Node could not read Git Bash's `/tmp` path) and the webhook
   lines were cut at the first escaped quote. Both fixed in the script; no gateway change.
2. **Second (clean) run, FR6:** all **61 × 200**, no 429, no alert. With a limit of 60 per minute this can only happen if
   the calls crossed a minute boundary, so the fixed window reset in the middle (the start second was not logged). This is the documented fixed-window edge effect
   ([limits-and-kill-switch.md](../limits-and-kill-switch.md), section 2), seen in a real run. The script now waits for
   the start of a new minute; the third run (below) gives the expected 60 + 1.
3. **First run, FR6:** 59 × 200 + 2 × 429. Correct: analytics-bot had made one call in the same minute in the FR8 step
   just before (1 + 59 = 60 allowed).

## What this does not show

- QA3's "< 1 minute" is about a **person**. The API answers in 0.083 s, but the time for a reviewer to go from a
  complaint to the answer was not measured with a stopwatch.
- Memory is idle-after-demo, not peak under the QA2 load.
- The daily quotas and the tool rate were shown with lowered limits (3, 5, 3/min), not the production defaults (1000, 5000, 120/min); the code path is the same.
- The write blast-radius cap is shown in [qa1-redteam.md](qa1-redteam.md) (MAL-08: 20 held, then 429).

## Raw output, final run (`main`, then `truncation`)

```text

=== FR9 playground: active policy, refund 1500 (expect ALLOW)
{"decision":"ALLOW","ruleId":"support-refund-up-to-2000","policyVersion":1,"policyStatus":"ACTIVE"}

=== FR9 playground: active policy, refund 5000 (expect DENY with the reason)
{"decision":"DENY","ruleId":"default-deny","reason":"No rule allows agent 'support-bot' to call 'issueRefund' (default deny). rule 'support-refund-up-to-2000' did not apply because amount lessThanOrEqual 2000 is false (value: 5000)"}

=== FR3/FR9: create DRAFT version 2 = version 1 + rule 'support-sms' (support-bot may call sendSms)
version 2 status: DRAFT

=== FR9 playground on the DRAFT (policyVersion 2): sendSms (expect ALLOW, draft not activated)
{"decision":"ALLOW","ruleId":"support-sms","policyVersion":2,"policyStatus":"DRAFT"}
live sendSms while v2 is only a DRAFT (expect 403):
HTTP 403 {"detail":"No rule allows agent 'support-bot' to call 'sendSms' (default deny)","instance":"/api/agent/calls/sendSms","status":403,"title":"Call denied","tool":"sendSms","rule":"default-deny","policyVersion":1}

=== FR3 hot reload through the admin API: activate v2, time until sendSms is allowed
version 2 status: ACTIVE
HTTP 201 {"smsId":"SMS-9001","customerId":1,"to":"017******78","message":"hi","status":"SENT","sentAt":"2026-11-26T09:46:22.783713033Z"}
time from activate request to the allowed call: 523 ms (no restart)

=== FR3 hot reload from the database (as another gateway instance would): back to v1 by SQL, poll every 250 ms
sendSms answered 403 after 5127 ms (10 polls); refresh interval 5 s, requirement 10 s
HTTP 403 {"detail":"No rule allows agent 'support-bot' to call 'sendSms' (default deny)","instance":"/api/agent/calls/sendSms","status":403,"title":"Call denied","tool":"sendSms","rule":"default-deny","policyVersion":1}

=== FR3 dry-run: version 1 in dry-run mode, sendSms (default-deny) is let through and logged
dryRun: true
HTTP 201 {"smsId":"SMS-9011","customerId":1,"to":"017******78","message":"dry run","status":"SENT","sentAt":"2026-11-26T09:46:28.763178575Z"}
DRY-RUN would DENY agent=support-bot endUser=1 tool=sendSms rule=default-deny policyVersion=1 reason=No rule allows agent 'support-bot' to call 'sendSms' (default deny)
dryRun: false
HTTP 403 {"detail":"No rule allows agent 'support-bot' to call 'sendSms' (default deny)","instance":"/api/agent/calls/se

=== FR4 approval web page
GET /approvals.html (approver login) -> HTTP 200
<title>AgentGate approvals</title>

=== FR4 edit: refund 1500 held, approver edits the amount to 500
PENDING approval 0184f797-e1d3-41d3-91d1-759d28adff87
state: APPROVED_WITH_EDITS
agent got: HTTP 201 {"refundId":"RF-5001","orderId":1001,"amount":500,"currency":"BDT","reason":"damaged","status":"ISSUED","issuedAt":"2026-11-26T09:46:31.195972549Z"}

=== FR4 edit that breaks the policy: refund 100 held, approver edits to 5000 (expect 403)
PENDING approval 8a6cd8b7-2ef0-40c6-84d5-2234eb914b86
state: APPROVED_WITH_EDITS
agent got: HTTP 403 {"detail":"the approver's edited arguments were blocked: No rule allows agent 'support-bot' to call 'issueRefund' (default deny). rule 'support-refund-up-to-2000' did not apply because amount lessThanOrEqual 2000 is false (value: 5000)","instance":"/api/agent/calls/issueRefund","status":403,"title":

=== FR4 + FR8: refund held, admin kills tool issueRefund -> pending approval cancelled
PENDING approval 58784a76-9911-4ba8-ad51-6d6a3c01dadc
agent got (after 248 ms): HTTP 403 {"detail":"the call was cancelled by the kill switch (cancelled by kill switch (TOOL: issueRefund))","instance":"/api/agent/calls/issueRefund","status":403,"title":"Call denied","tool":"issueRefund","rule":"approval-cancelled","approvalId":"58784a76-9911-4ba8-ad51-6d6a3c01dadc","state":"CANCELLED","
approval state: CANCELLED

=== FR4 expiry: refund held, nobody answers (gateway started with AGENTGATE_APPROVAL_TIMEOUT=20s)
PENDING approval 6ec36965-aea3-4356-a70a-fc74b6e62d70
agent got after 20138 ms: HTTP 403 {"detail":"nobody approved the call in time (no answer before the timeout)","instance":"/api/agent/calls/issueRefund","status":403,"title":"Call denied","tool":"issueRefund","rule":"approval-expired","approvalId":"6ec36965-aea3-4356-a70a-fc74b6e62d70","state":"EXPIRED","reason":"no answer before the
approval state: EXPIRED

=== FR4 webhook: what mock-tools received
{"approvalId":"0184f797","event":"new request","amount":1500}
{"approvalId":"0184f797","event":"closed: APPROVED_WITH_EDITS","amount":500}
{"approvalId":"8a6cd8b7","event":"new request","amount":100}
{"approvalId":"8a6cd8b7","event":"closed: APPROVED_WITH_EDITS","amount":5000}
{"approvalId":"58784a76","event":"new request","amount":400}
{"approvalId":"58784a76","event":"closed: CANCELLED"}
{"approvalId":"6ec36965","event":"new request","amount":300}
{"approvalId":"6ec36965","event":"closed: EXPIRED"}

=== FR8 kill switch: agent support-bot
support-bot getOrder:   HTTP 403 {"detail":"agent 'support-bot' is disabled by an administrator","instance":"/api/agent/calls/getOrder","status":403,"title":"Call denied","tool":"getOrder","rule":"kill-switch-agent"}
analytics-bot getOrder: HTTP 200 {"id":1001,"customerId":1,"items":["Walton smartpho
released, support-bot getOrder: HTTP 200 {"id":1001,"customerId":1,"items":["Walton smartpho

=== FR8 kill switch: all write tools
issueRefund (WRITE): HTTP 403 {"detail":"all write tools are disabled by an administrator","instance":"/api/agent/calls/issueRefund","status":403,"title":"Call denied","tool":"issueRefund","rule":"kill-switch-all-writes"}
getOrder (READ):     HTTP 200 {"id":1001,"customerId":1,"items":["Walton smartpho

=== QA3 (machine part): look up one call by its X-Call-Id
denied call, X-Call-Id: 7fba898c-394c-4e46-a7b4-364043f14ca4
GET /api/admin/audit/{callId} -> HTTP 200 in 0.082952 s
{"who":"support-bot","onBehalfOf":"1","what":"getCustomer","arguments":"{\"id\":2}","rule":"default-deny","policyVersion":1,"decision":"DENIED"}
{"status":"OK","records":25,"firstBrokenSeq":null,"problem":null}

=== FR6 rate limit: analytics-bot sends 61 getOrder calls in one minute (limit 60/min per agent)
       60 200
        1 429
61st call: HTTP 429 {"detail":"Limit 'agent-rate-per-minute' reached (max 60 calls). Try again after 2026-11-26T09:48:00Z","instance":"/api/agent/calls/getOrder","status":429,"title":"Too many calls","limit":"agent-rate-per-minute","max":60,"tool":"getOrder","resetsAt":"2026-11-26T09:48:00Z","retryAfterSeconds":48}
Retry-After: 48
alerts (GET /api/admin/alerts):
[{"id":1,"alertType":"LIMIT_BREACH","agentId":"analytics-bot","toolName":"getOrder","message":"Limit 'agent-rate-per-minute' (max 60) breached by agent 'analytics-bot' on tool 'getOrder'","createdAt":"2026-11-26T09:47:11.702360Z"}]

=== C1 memory of the running stack (docker stats --no-stream)
NAME                   MEM USAGE / LIMIT     CPU %
agentgate-postgres     54.69MiB / 7.632GiB   0.04%
agentgate-mock-tools   231MiB / 7.632GiB     0.23%
agentgate-gateway      438.2MiB / 7.632GiB   3.31%
Docker VM memory: 8195305472 bytes, CPUs: 8

=== FR5 truncation (gateway started with AGENTGATE_GUARDS_TRUNCATION_MAXBYTES=100)
getCustomer 1 as support-bot for customer 1:
HTTP 200 {"truncated":true,"notice":"Result truncated: the original result was 119 bytes and the limit is 100 bytes. Only the beginning is shown.","originalSizeBytes":119,"limitBytes":100,"partialResult":"{\"id\":1,\"name\":\"Rahim Uddin\",\"email\":\"r***@example.com\",\"phone\":\"017******78\",\"nationalId\":\"19******"}
```

## Raw output, FR6 extra phases (`daily-quotas`, then `tool-rate`)

```text

=== FR6 agent daily quota (3/day): support-bot calls getOrder 4 times
  call 1: HTTP 200 {"id":1001,"customerId":1,"items":["Walton smartphone"],"total":18500.0,"currency":"BDT","status":"DELIVERED","placedOn":"2026-10-02","deliveryPhone":"017******78"}
  call 2: HTTP 200 {"id":1001,"customerId":1,"items":["Walton smartphone"],"total":18500.0,"currency":"BDT","status":"DELIVERED","placedOn":"2026-10-02","deliveryPhone":"017******78"}
  call 3: HTTP 200 {"id":1001,"customerId":1,"items":["Walton smartphone"],"total":18500.0,"currency":"BDT","status":"DELIVERED","placedOn":"2026-10-02","deliveryPhone":"017******78"}
  call 4: HTTP 429 {"detail":"Limit 'agent-daily-quota' reached (max 3 calls). Try again after 2026-11-27T00:00:00Z","instance":"/api/agent/calls/getOrder","status":429,"title":"Too many calls","limit":"agent-daily-quota","max":3,"tool":"ge

=== FR6 tool daily quota (5/day for getOrder, 3 used): analytics-bot calls getOrder 3 times
  call 1: HTTP 200 {"id":1001,"customerId":1,"items":["Walton smartphone"],"total":18500.0,"currency":"BDT","status":"DELIVERED","placedOn":"2026-10-02","deliveryPhone":"01712345678"}
  call 2: HTTP 200 {"id":1001,"customerId":1,"items":["Walton smartphone"],"total":18500.0,"currency":"BDT","status":"DELIVERED","placedOn":"2026-10-02","deliveryPhone":"01712345678"}
  call 3: HTTP 429 {"detail":"Limit 'tool-daily-quota' reached (max 5 calls). Try again after 2026-11-27T00:00:00Z","instance":"/api/agent/calls/getOrder","status":429,"title":"Too many calls","limit":"tool-daily-quota","max":5,"tool":"getO
alerts:
["Limit 'tool-daily-quota' (max 5) breached by agent 'analytics-bot' on tool 'getOrder'","Limit 'agent-daily-quota' (max 3) breached by agent 'support-bot' on tool 'getOrder'"]

=== FR6 tool rate (3/min for getOrder): support-bot 2 calls, then analytics-bot 2 calls
  support-bot   1: HTTP 200 {"id":1001,"customerId":1,"items":["Walton smartpho
  support-bot   2: HTTP 200 {"id":1001,"customerId":1,"items":["Walton smartpho
  analytics-bot 1: HTTP 200 {"id":1001,"customerId":1,"items":["Walton smartphone"],"total":18500.0,"currency":"BDT","status":"DELIVERED","placedOn":"2026-10-02","deliveryPhone":"01712345678"}
  analytics-bot 2: HTTP 429 {"detail":"Limit 'tool-rate-per-minute' reached (max 3 calls). Try again after 2026-11-26T09:53:00Z","instance":"/api/agent/calls/getOrder","status":429,"title":"Too many calls","limit":"tool-rate-per-minute","max":3,"too
```
