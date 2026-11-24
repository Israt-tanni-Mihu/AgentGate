# MCP endpoint evidence (FR1: one MCP endpoint, per-agent tool list)

- **Date:** 2026-11-25. PostgreSQL from `docker compose up -d`; mock-tools (8081) and gateway (8080) from `./mvnw -q -pl <module> spring-boot:run`, profile `local`; then `docker compose down`.
- **Script:** `scripts/mcp-demo.sh` (curl only). Endpoint: `POST /mcp`, MCP Streamable HTTP with plain JSON answers, protocol 2025-06-18, same agent JWT as `/api/agent/**`, end user from `X-On-Behalf-Of`.
- **What to look at:** (1) no token → 401; (2) `tools/list` differs per agent (`analytics-bot` has no `issueRefund`); (3) a blocked call is a normal result with `isError:true`, the reason, the rule and the `callId` (also in `_meta`), not an HTTP error; (4) the audit log has a row for each call (see end).

## Raw output

```

$ no token
{"type":"about:blank","title":"Unauthorized","status":401,"detail":"Missing or invalid credentials","instance":"/mcp"}
HTTP 401

$ initialize (support-bot)
{"jsonrpc":"2.0","id":1,"result":{"protocolVersion":"2025-06-18","capabilities":{"tools":{"listChanged":false}},"serverInfo":{"name":"agentgate","version":"0.1.0"},"instructions":"AgentGate: every tool call is checked by policy, guarded, audited and may wait for human approval. Your tool list only contains tools you may use right now."}}
HTTP 200

$ notifications/initialized

HTTP 202

# tools/list names per agent
support-bot: "name":"getCustomer" "name":"getOrder" "name":"issueRefund" 
analytics-bot: "name":"getCustomer" "name":"getOrder" 

$ tools/list (support-bot), first 700 chars
{"jsonrpc":"2.0","id":2,"result":{"tools":[{"name":"getCustomer","description":"Read-only. Returns the customer's profile including name, email, phone number and national ID number (personal data).","inputSchema":{"type":"object","required":["id"],"properties":{"id":{"type":"integer","format":"int64","exclusiveMinimum":0}},"additionalProperties":false},"annotations":{"readOnlyHint":true}},{"name":"getOrder","description":"Read-only. Returns the order's items, total in BDT, status and the customer it belongs to.","inputSchema":{"type":"object","required":["id"],"properties":{"id":{"type":"integer","format":"int64","exclusiveMinimum":0}},"additionalProperties":false},"annotations":{"readOnlyHi

$ tools/call getCustomer 1 for customer 1 (ALLOWED, result redacted by guard)
{"jsonrpc":"2.0","id":3,"result":{"content":[{"type":"text","text":"{\"id\":1,\"name\":\"Rahim Uddin\",\"email\":\"r***@example.com\",\"phone\":\"017******78\",\"nationalId\":\"19******21\",\"city\":\"Dhaka\"}"}],"isError":false,"_meta":{"agentgate/callId":"729a04bd-cc1e-46e6-9ffd-00253c3f19cd"}}}
HTTP 200

$ tools/call getCustomer 2 for customer 1 (DENIED: other customer)
{"jsonrpc":"2.0","id":4,"result":{"content":[{"type":"text","text":"Call 3ce4c9b4-2cfb-483f-9710-74c3f5fa8121 was blocked: No rule allows agent 'support-bot' to call 'getCustomer' (default deny). rule 'support-own-customer-only' did not apply because id must match the end user (value: 2, end user: 1) (rule: default-deny)"}],"isError":true,"_meta":{"agentgate/callId":"3ce4c9b4-2cfb-483f-9710-74c3f5fa8121"}}}
HTTP 200

$ analytics-bot calls issueRefund (DENIED: not allowed for this agent)
{"jsonrpc":"2.0","id":5,"result":{"content":[{"type":"text","text":"Call b6e238d1-95a2-4add-a8c8-9ae31d67cab2 was blocked: No rule allows agent 'analytics-bot' to call 'issueRefund' (default deny) (rule: default-deny)"}],"isError":true,"_meta":{"agentgate/callId":"b6e238d1-95a2-4add-a8c8-9ae31d67cab2"}}}
HTTP 200

$ wrong argument type (blocked by schema guard)
{"jsonrpc":"2.0","id":6,"result":{"content":[{"type":"text","text":"Call fbc5087c-2cf5-432c-9730-31a3aaa60353 failed (HTTP 400): The arguments do not match the schema of tool 'getCustomer'"}],"isError":true,"_meta":{"agentgate/callId":"fbc5087c-2cf5-432c-9730-31a3aaa60353"}}}
HTTP 200
```

## Audit records of these calls (newest first, `GET /api/admin/audit`)

```
support-bot   getCustomer  DENIED   invalid-arguments            (schema guard, over MCP)
analytics-bot issueRefund  DENIED   default-deny                 (other agent: no rule allows it)
support-bot   getCustomer  DENIED   default-deny                 (customer 2 asked while acting for customer 1)
support-bot   getCustomer  ALLOWED  support-own-customer-only
$ GET /api/admin/audit/verify  ->  {"status":"OK","records":33,"firstBrokenSeq":null,"problem":null}
```

(The demo script was run twice while preparing; the first run had a quoting error in the script itself, fixed before this run.)

## Notes

- **Why a custom handler instead of the Spring AI MCP starter:** the starter's server registers one tool list at startup for every client. We need a list per agent that changes at runtime (policy, kill switch, breaker). I did **not** compile against the starter to prove this; it follows from its design. The gateway has no Spring AI dependency, so there is no version risk with Spring Boot 4.1.
- **Not implemented (on purpose):** server-initiated streams (GET /mcp → 405), sessions (`Mcp-Session-Id`), `listChanged` notifications (clients call `tools/list` again), JSON-RPC batches (removed in the 2025-06-18 spec).
- **Tests:** `McpEndpointIntegrationTest` (13 tests, Testcontainers): per-agent list, killed tool, OPEN breaker, allowed call, policy denial, guard/kill switch/unknown tool, approval wait + expiry, 401, audit row per call.
