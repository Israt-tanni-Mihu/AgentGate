# Evidence: downstream MCP servers (FR1) — import, forward, audit

- **Date:** 2026-11-25. PostgreSQL from `docker compose up -d`; mock-tools (8081, now also an MCP server on `/mcp`) and gateway (8080) from `./mvnw -q -pl <module> spring-boot:run`, profile `local`.
- **Steps:** `scripts/mcp-downstream-setup.sh` (admin: register server, import tools, activate a policy with a rule for `track_parcel`), then `./mvnw -q -pl agent-sim exec:java` (the scripted agent, Spring AI MCP client → gateway `/mcp`, OAuth2 token).
- **Honest note:** the database volume still held data from earlier runs, so the setup output shows "already exists" and "unchanged" (this also shows the import is idempotent). The first run of the same demo (before this one) created the tool: `{"created":1,"items":[{"name":"track_parcel","outcome":"CREATED"}]}`; the policy was version 2 then, version 3 now.
- **Tests:** `./mvnw verify` green: gateway 171 tests (10 new in `McpDownstreamIntegrationTest`, 3 in `McpToolAdapterTest`), mock-tools 20 tests (1 new, a real MCP client against the mock server), 0 failures.

## 1. Admin setup (raw)

```
$ register the MCP server
{"detail":"An MCP server named 'shop-parcels' already exists","instance":"/api/admin/mcp-servers","status":409,"title":"Duplicate MCP server name"}

$ import its tools (MCP tools/list), server id 1
{"format":"mcp-tools-list","created":0,"updated":0,"skipped":1,"items":[{"name":"track_parcel","outcome":"SKIPPED","reason":"unchanged"}]}

$ import the REST tools too (OpenAPI), so the demo has issueRefund, getOrder, getCustomer
{"format":"openapi-3","created":0,"updated":0,"skipped":4,"items":[{"name":"sendSms","outcome":"SKIPPED","reason":"unchanged"},{"name":"issueRefund","outcome":"SKIPPED","reason":"unchanged"},{"name":"getOrder","outcome":"SKIPPED","reason":"unchanged"},{"name":"getCustomer","outcome":"SKIPPED","reaso

$ new policy version with a rule for track_parcel, then activate it
{"version":3,"status":"ACTIVE","dryRun":false,"description":"Demo policies for ShopBD + support-bot may track parcels (MCP tool)","rules":[{"id":"support-own-customer-only","description":"Support bot 
```

## 2. agent-sim output (raw)

```
== agent 'support-bot' got an OAuth2 access token (client credentials)
== connected to MCP server 'agentgate' (protocol 2025-06-18)

-- 1. tools/list: which tools may this agent use?
   - getCustomer: Read-only. Returns the customer's profile including name, email, phone...
   - getOrder: Read-only. Returns the order's items, total in BDT, status and the cus...
   - issueRefund: Write. Moves real money back to the customer. The total refunded for a...
   - track_parcel: Read-only. Returns the delivery status of a parcel by its tracking num...

-- 2. getOrder 1001 (policy: allowed)
   result: OK
   {"id":1001,"customerId":1,"items":["Walton smartphone"],"total":18500.0,"currency":"BDT","status":"DELIVERED","placedOn":"2026-10-02","deliveryPhone":"017******78"}
   callId: d0421512-7620-4699-9373-e7d4ae9fa8ec

-- 3. getCustomer 2 while acting for customer 1 (policy: denied)
   result: ERROR/BLOCKED
   Call 7a73be08-ff3b-4e12-83ee-3af4442b2bc4 was blocked: No rule allows agent 'support-bot' to call 'getCustomer' (default deny). rule 'support-own-customer-only' did not apply because id must match the end user (value: 2, end user: 1) (rule: default-deny)
   callId: 7a73be08-ff3b-4e12-83ee-3af4442b2bc4

-- 4. track_parcel BD1001 (a tool imported from a downstream MCP server)
   result: OK
   {"trackingNumber":"BD1001","status":"Delivered to Dhaka on 2026-11-20"}
   callId: aee59456-1816-4648-a491-aabb145e0fd0

-- 5. issueRefund 500 BDT (HIGH risk: held for human approval)
   the call is HELD. Pending approval e0b22fe0-a465-4577-a5a6-81688e14fb56 (the agent is still waiting)
   approver 'approver' approves -> state APPROVED
   result: OK
   {"refundId":"RF-5001","orderId":1002,"amount":500,"currency":"BDT","reason":"damaged item","status":"ISSUED","issuedAt":"2026-11-24T19:32:43.272416900Z"}
   callId: 356ae56f-3a79-44b1-adc8-eb16ae51c412
```

## 3. Matching audit records (`GET /api/admin/audit`, newest first, fields cut)

```
{'seq': 45, 'toolName': 'issueRefund', 'agentId': 'support-bot', 'endUserId': '1', 'decision': 'ALLOWED', 'ruleId': 'support-refund-up-to-2000', 'approvalState': 'APPROVED', 'latencyMs': 1084}
{'seq': 44, 'toolName': 'track_parcel', 'agentId': 'support-bot', 'endUserId': '1', 'decision': 'ALLOWED', 'ruleId': 'support-track-parcel', 'approvalState': None, 'latencyMs': 138}
{'seq': 43, 'toolName': 'getCustomer', 'agentId': 'support-bot', 'endUserId': '1', 'decision': 'DENIED', 'ruleId': 'default-deny', 'approvalState': None, 'latencyMs': 11}
{'seq': 42, 'toolName': 'getOrder', 'agentId': 'support-bot', 'endUserId': '1', 'decision': 'ALLOWED', 'ruleId': 'support-read-orders', 'approvalState': None, 'latencyMs': 302}
$ GET /api/admin/audit/verify -> {"status":"OK","records":45,"firstBrokenSeq":null,"problem":null}
```

The four newest rows are this run's calls 2-5 (`getOrder`, denied `getCustomer`, MCP-imported `track_parcel`, approved `issueRefund`). Same table, same hash chain: the MCP tool is audited like a REST tool.

## Notes

- **Library check:** Spring AI 2.1.0 (`spring-ai-mcp`, MCP Java SDK 2.2.0, Jackson 3) compiled and ran with Spring Boot 4.1.3 on Java 25. The gateway uses only the client classes (no auto-configuration); the gateway's own `/mcp` endpoint stays the custom handler from Part A.
- **Both breaker and time limit apply to MCP tools** (tests `theBreakerOpensForAnMcpToolThenFailsFastAndHidesTheTool`, `aSlowMcpToolAnswers504WithinItsOwnTimeLimit`): `ResilientForwardStep` wraps `ForwardStep`, which now picks `RestToolInvoker` or `McpToolInvoker`.
- **Tool result mapping:** an MCP result with `isError: true` becomes status 422 and does not count against the breaker; an unreachable or failing server becomes 502 and does.
- **Not done:** MCP servers that need authentication (no header/credential setting yet); `listChanged` notifications from downstream servers are ignored (re-run the import).
