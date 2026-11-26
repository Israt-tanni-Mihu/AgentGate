# C4 Level 1 — System context

AgentGate sits between AI agents and the tools they call. Every tool call goes through it: authenticate → kill switch → limits → schema → policy → (human approval) → forward → redact/trim → audit.

```mermaid
flowchart TB
    agent["<b>AI agent</b><br/>[Person / external system]<br/>Scripted MCP or REST client acting for an end user<br/>(in this project: agent-sim, QA scripts)"]
    admin["<b>Admin</b><br/>[Person]<br/>Registers tools and agents, writes policies,<br/>uses the playground and the kill switch"]
    approver["<b>Approver</b><br/>[Person]<br/>Approves, rejects or edits HIGH-risk calls"]
    reviewer["<b>Security reviewer / auditor</b><br/>[Person]<br/>Searches, exports and verifies the audit log"]

    gate["<b>AgentGate</b><br/>[Software system]<br/>Governance gateway: identity, policy, guards,<br/>limits, human approval, hash-chained audit"]

    rest["<b>Downstream REST tools</b><br/>[External system]<br/>e.g. ShopBD: getOrder, getCustomer,<br/>issueRefund, sendSms"]
    mcp["<b>Downstream MCP servers</b><br/>[External system]<br/>e.g. shop-parcels: track_parcel"]
    hook["<b>Approval webhook receiver</b><br/>[External system]<br/>Chat / ticket system that pings approvers"]

    agent -- "Gets a token (OAuth2 client credentials),<br/>lists and calls tools (MCP or REST + JWT)" --> gate
    admin -- "Admin API, Swagger UI<br/>(HTTP Basic, role ADMIN)" --> gate
    approver -- "Approvals web page / API<br/>(HTTP Basic, role APPROVER)" --> gate
    reviewer -- "Audit search, export, verify<br/>(admin API)" --> gate
    gate -- "Forwards allowed calls<br/>(HTTP/JSON)" --> rest
    gate -- "Forwards allowed calls<br/>(MCP tools/call over HTTP)" --> mcp
    gate -- "New pending approval<br/>(webhook, HTTP POST)" --> hook

    classDef person fill:#08427b,color:#fff,stroke:#052e56
    classDef system fill:#1168bd,color:#fff,stroke:#0b4884
    classDef external fill:#999,color:#fff,stroke:#6b6b6b
    class agent,admin,approver,reviewer person
    class gate system
    class rest,mcp,hook external
```

## Notes (code vs. description)

- The **security reviewer has no role of its own** in the code. Audit endpoints are under `/api/admin/audit/**`, so the reviewer uses an **ADMIN** login (`SecurityConfig.adminChain`). A read-only `AUDITOR` role would be a small, separate change.
- The approver is notified on **two channels** (FR4): the web page `approvals.html` and the webhook. In the demo the webhook receiver is `mock-tools` (`POST /hooks/approvals`, it only logs).
- Agents never talk to the tools directly; the tools are only reachable through AgentGate in the intended deployment (in `docker-compose.yml` mock-tools also publishes port 8081 for the QA2 baseline measurement).
