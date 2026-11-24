# Evidence: FR7 Audit trail — search, export, lookup, verify, tamper detection

- **Date:** 2026-11-24
- **Requirement:** FR7 (search, export), QA3 (find who / on whose behalf / what / which rule for any call in < 1 minute; editing any stored audit row is detected by a verify command).
- **Method:** manual `curl` run. PostgreSQL from `docker compose up -d`; mock-tools (port 8081) and the gateway (port 8080) started from the built jars (`java -jar ...`), profile `local`. The audit table was empty at the start (`verify` → 0 records). `docker compose down` at the end.
- **Setup before the calls:** tools were already imported (`POST /api/admin/tools/import` → 4 × "unchanged"); `issueRefund` was set to risk level **HIGH** with `PUT /api/admin/tools/6` so that the call is held for approval.
- **Credentials:** local-only defaults from `application.yml` (not real secrets).
- **Tests:** `./mvnw verify` green, 136 tests, 0 failures (13 new ones in `AuditSearchIntegrationTest`).

Output is copied from the terminal. Response headers that do not matter here are left out. Where a JSON answer was pretty-printed or cut to a few fields, the command says so.

## (a) Three calls: allowed, denied by policy, held and approved

The agent token comes from `POST /oauth2/token` (client credentials) as in `fr2-oauth2.md`.

**a1 — ALLOWED** (support-bot reads customer 1 for end user 1)

```
$ curl -i -X POST localhost:8080/api/agent/calls/getCustomer -H "Authorization: Bearer $TOKEN" -H "X-On-Behalf-Of: 1" -H "Content-Type: application/json" -d "{\"id\": 1}"
HTTP/1.1 200
X-Call-Id: 3df5967d-bb7a-475d-a576-3b50cdd01bb4
{"id":1,"name":"Rahim Uddin","email":"r***@example.com","phone":"017******78","nationalId":"19******21","city":"Dhaka"}
```

**a2 — DENIED by policy** (end user 1 asks for customer 2)

```
$ curl -i -X POST localhost:8080/api/agent/calls/getCustomer ... -H "X-On-Behalf-Of: 1" -d "{\"id\": 2}"
HTTP/1.1 403
X-Call-Id: 51e54f9f-0b7d-46e9-ade6-8a43cc14819e
{"detail":"No rule allows agent 'support-bot' to call 'getCustomer' (default deny). rule 'support-own-customer-only' did not apply because id must match the end user (value: 2, end user: 1)","instance":"/api/agent/calls/getCustomer","status":403,"title":"Call denied","tool":"getCustomer","rule":"default-deny","policyVersion":1}
```

**a3 — HELD, then APPROVED** (refund 100 BDT; the call waits until the approver decides)

```
$ curl -i -X POST localhost:8080/api/agent/calls/issueRefund ... -H "X-On-Behalf-Of: 1" -d "{\"orderId\": 1001, \"amount\": 100, \"reason\": \"damaged\"}"   (waits for the approver)

$ curl -u approver:*** "localhost:8080/api/approvals?state=PENDING"        (cut)
{"content":[{"id":"b4f81cb8-eb5f-4b21-a29e-f80102242c71","agentId":"support-bot","endUserId":"1","toolName":"issueRefund","originalArguments":{"amount":100,"reason":"damaged","orderId":1001},"editedArguments":null,"state":"PENDING", ...

$ curl -u approver:*** -X POST localhost:8080/api/approvals/b4f81cb8-eb5f-4b21-a29e-f80102242c71/approve -H 'Content-Type: application/json' -d '{}'
{"id":"b4f81cb8-...","agentId":"support-bot","endUserId":"1","toolName":"issueRefund", ... "state":"APPROVED","approver":"approver", ...}

--- the agent finally gets:
HTTP/1.1 201
X-Call-Id: 16e4f5ea-091d-4b25-9d0a-5958782c2608
{"refundId":"RF-5001","orderId":1001,"amount":100,"currency":"BDT","reason":"damaged","status":"ISSUED","issuedAt":"2026-11-24T17:23:55.310487900Z"}
```

| Call | X-Call-Id | HTTP |
|---|---|---|
| allowed | `3df5967d-bb7a-475d-a576-3b50cdd01bb4` | 200 |
| denied | `51e54f9f-0b7d-46e9-ade6-8a43cc14819e` | 403 |
| held + approved | `16e4f5ea-091d-4b25-9d0a-5958782c2608` | 201 |

## (b) Look up one call by its call id

`GET /api/admin/audit/{callId}`. The id is the `X-Call-Id` header the agent received. Answers the four QA3 questions: **who** (`agentId`), **on whose behalf** (`endUserId`), **what** (`toolName`, `arguments`), **which rule** (`ruleId`, `policyVersion`, `decidedByStep`), plus the approval details.

```
$ curl -u admin:*** localhost:8080/api/admin/audit/16e4f5ea-091d-4b25-9d0a-5958782c2608     (pretty-printed)
{
    "seq": 3,
    "callId": "16e4f5ea-091d-4b25-9d0a-5958782c2608",
    "occurredAt": "2026-11-24T17:23:52.958483Z",
    "agentId": "support-bot",
    "endUserId": "1",
    "toolName": "issueRefund",
    "arguments": "{\"orderId\":1001,\"amount\":100,\"reason\":\"damaged\"}",
    "decision": "ALLOWED",
    "decidedByStep": "PolicyStep",
    "ruleId": "support-refund-up-to-2000",
    "policyVersion": 1,
    "approvalId": "b4f81cb8-eb5f-4b21-a29e-f80102242c71",
    "approvalState": "APPROVED",
    "approver": "approver",
    "editedArguments": null,
    "latencyMs": 2356,
    "httpStatus": 201,
    "reason": "completed",
    "prevHash": "bd640aebdca0626f44bb5852f792bb4c29d016b1be81a68b8ee0a032447c23a5",
    "hash": "0d7ecd61ff8f6ffa295199109e67a0136eda6244cf0ac4c5ac5afb119b14a4e5"
}
```

`latencyMs` 2356 is mostly the time the call waited for the approver (about 2.3 s in this run).

The denied call:

```
$ curl -u admin:*** localhost:8080/api/admin/audit/51e54f9f-0b7d-46e9-ade6-8a43cc14819e     (pretty-printed, cut to the main fields)
    "seq": 2, "agentId": "support-bot", "endUserId": "1", "toolName": "getCustomer",
    "arguments": "{\"id\":2}", "decision": "DENIED", "decidedByStep": "PolicyStep",
    "ruleId": "default-deny", "policyVersion": 1, "latencyMs": 5, "httpStatus": 403,
    "reason": "No rule allows agent 'support-bot' to call 'getCustomer' (default deny). rule 'support-own-customer-only' did not apply because id must match the end user (value: 2, end user: 1)",
```

Unknown call id → `404` Problem Details; not a UUID → `400` (both covered by tests, not run by hand).

## (c) Search

`GET /api/admin/audit` — filters `agentId`, `endUserId`, `toolName`, `decision`, `from`, `to` (all optional, combined with AND); paged (`page`, `size`, default 20); newest first.

```
$ curl -u admin:*** "localhost:8080/api/admin/audit?agentId=support-bot&size=2"     (pretty-printed, only these fields shown)
            "seq": 3, "callId": "16e4f5ea-...", "agentId": "support-bot", "endUserId": "1",
            "toolName": "issueRefund", "decision": "ALLOWED", "ruleId": "support-refund-up-to-2000", "approvalState": "APPROVED",
            "seq": 2, "callId": "51e54f9f-...", "agentId": "support-bot", "endUserId": "1",
            "toolName": "getCustomer", "decision": "DENIED", "ruleId": "default-deny", "approvalState": null,
    "number": 0, "size": 2, "totalElements": 3, "totalPages": 2

$ curl -u admin:*** "localhost:8080/api/admin/audit?agentId=support-bot&decision=DENIED&endUserId=1"
            "seq": 2, "toolName": "getCustomer", "decision": "DENIED", "ruleId": "default-deny",
    "totalElements": 1
```

3 records belong to support-bot; page size 2 gives 2 pages; the combined filter finds exactly the denied call.

## (d) Export

`GET /api/admin/audit/export?format=csv|json` with the same filters, sent as a download, oldest first.

```
$ curl -i -u admin:*** "localhost:8080/api/admin/audit/export?format=csv"
HTTP/1.1 200
Content-Disposition: attachment; filename="audit-export.csv"
Content-Type: text/csv;charset=UTF-8
Transfer-Encoding: chunked

seq,callId,occurredAt,agentId,endUserId,toolName,arguments,decision,decidedByStep,ruleId,policyVersion,approvalId,approvalState,approver,editedArguments,latencyMs,httpStatus,reason,prevHash,hash
1,3df5967d-bb7a-475d-a576-3b50cdd01bb4,2026-11-24T17:23:52.599147Z,support-bot,1,getCustomer,"{""id"":1}",ALLOWED,PolicyStep,support-own-customer-only,1,,,,,93,200,completed,0000000000000000000000000000000000000000000000000000000000000000,d06c984254b713ce5573dbface09238d478bca0c213ef9e8c00516f6e3f1a740
2,51e54f9f-0b7d-46e9-ade6-8a43cc14819e,2026-11-24T17:23:52.832021Z,support-bot,1,getCustomer,"{""id"":2}",DENIED,PolicyStep,default-deny,1,,,,,5,403,"No rule allows agent 'support-bot' to call 'getCustomer' (default deny). rule 'support-own-customer-only' did not apply because id must match the end user (value: 2, end user: 1)",d06c984254b713ce5573dbface09238d478bca0c213ef9e8c00516f6e3f1a740,bd640aebdca0626f44bb5852f792bb4c29d016b1be81a68b8ee0a032447c23a5
3,16e4f5ea-091d-4b25-9d0a-5958782c2608,2026-11-24T17:23:52.958483Z,support-bot,1,issueRefund,"{""orderId"":1001,""amount"":100,""reason"":""damaged""}",ALLOWED,PolicyStep,support-refund-up-to-2000,1,b4f81cb8-eb5f-4b21-a29e-f80102242c71,APPROVED,approver,,2356,201,completed,bd640aebdca0626f44bb5852f792bb4c29d016b1be81a68b8ee0a032447c23a5,0d7ecd61ff8f6ffa295199109e67a0136eda6244cf0ac4c5ac5afb119b14a4e5
```

The export keeps `prevHash` and `hash`, so an exported file can be re-checked outside the gateway.

JSON, only denied calls:

```
$ curl -u admin:*** "localhost:8080/api/admin/audit/export?format=json&decision=DENIED"
[{"seq":2,"callId":"51e54f9f-0b7d-46e9-ade6-8a43cc14819e","occurredAt":"2026-11-24T17:23:52.832021Z","agentId":"support-bot","endUserId":"1","toolName":"getCustomer","arguments":"{\"id\":2}","decision":"DENIED","decidedByStep":"PolicyStep","ruleId":"default-deny","policyVersion":1,"approvalId":null,"approvalState":null,"approver":null,"editedArguments":null,"latencyMs":5,"httpStatus":403,"reason":"No rule allows agent 'support-bot' ...","prevHash":"d06c9842...","hash":"bd640aeb..."}]
```

(`reason`, `prevHash` and `hash` are shortened with `...` in this document only.)

## (e) Verify: untouched log

First attempt with `powershell -File scripts/audit-verify.ps1` failed on this laptop: **"running scripts is disabled on this system"** (Windows execution policy). This is not a gateway problem. Run the script with a per-process bypass instead (system setting not changed):

```
$ powershell -ExecutionPolicy Bypass -File scripts/audit-verify.ps1
AUDIT CHAIN OK - 3 records verified
exit code: 0
```

## (f) Tamper with one row directly in the database, verify again

```
$ docker exec agentgate-postgres psql -U agentgate -d agentgate -c "UPDATE audit_records SET rule_id = 'edited-by-attacker' WHERE seq = 2"
UPDATE 1

$ powershell -ExecutionPolicy Bypass -File scripts/audit-verify.ps1
AUDIT CHAIN BROKEN at sequence number 2: the stored values do not match the record's hash
exit code: 1

$ curl -u admin:*** localhost:8080/api/admin/audit/verify
{"status":"BROKEN","records":2,"firstBrokenSeq":2,"problem":"the stored values do not match the record's hash"}
```

Records are counted up to the first broken one (`records: 2`), and the broken sequence number is named. Database access alone is enough to change the row (there is no database trigger against it); the chain is what makes the change visible.

**Cleanup:** after the run, the row was changed back (`rule_id = 'default-deny'`) and `verify` answered `OK, 3 records` again, so the local database is not left broken. Then the two Java processes were stopped and `docker compose down` was run (the data volume is kept).

## What went wrong during this run (honest notes)

1. **Script blocked by execution policy** (see (e)). The first run of (e) and (f) failed this way; I had also already changed row 2 by then (this showed up as `BROKEN` on the curl check). I reset the row and ran (e) and (f) again in the right order. The output above is from the second run.
2. **An existing test needed a change.** `AuditIntegrationTest` expected `DELETE /api/admin/audit/1` to answer 404 ("no such endpoint"). Now that `GET /api/admin/audit/{callId}` exists, the same request answers 405 (wrong method). The intent stays: there is still no way to change or delete a record over HTTP, so the test now expects 405.

## Not covered

- The `from` / `to` time filters and the 400 for `from` after `to` are covered by tests only, not by a manual curl.
- Export of a large log (more than one 500-row batch) was not tested.
- Deleting the **last** rows of the log is not detected by `verify` (see the note in `docs/patterns.md`).
- No performance numbers for search or export (QA2 covers the call path only).
