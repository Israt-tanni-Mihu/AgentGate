# C4 Level 2 — Containers

What `docker compose up` starts (`docker-compose.yml`), plus `agent-sim` (compose profile `demo`, one-shot).

```mermaid
flowchart TB
    approver["<b>Approver</b><br/>[Person]"]
    admin["<b>Admin / security reviewer</b><br/>[Person]"]

    subgraph compose["docker compose (one laptop, 8 GB)"]
        sim["<b>agent-sim</b><br/>[Container: Java 25, Spring AI MCP client]<br/>Scripted agents: demo run + QA1 red-team suite<br/>(profile <i>demo</i>, not started by <i>up</i>)"]

        gw["<b>gateway</b> :8080<br/>[Container: Java 25, Spring Boot 4, Spring MVC + virtual threads]<br/>Embedded Spring Authorization Server, call pipeline,<br/>MCP endpoint /mcp, REST /api/agent/calls/{tool},<br/>admin + approval APIs, approvals.html"]

        db[("<b>postgres</b> :5432<br/>[Container: PostgreSQL 16]<br/>tools, agents, policy_versions, kill switches,<br/>alerts, approval_requests, audit_records, mcp_servers<br/>(Flyway V1–V8)")]

        subgraph mt["<b>mock-tools</b> :8081  [Container: Java 25, Spring Boot]"]
            shop["ShopBD REST API<br/>/api/orders, /api/customers, refunds, SMS<br/>+ OpenAPI spec /v3/api-docs"]
            parcels["Downstream MCP server /mcp<br/>tool <i>track_parcel</i> (Spring AI MCP server)"]
            hooks["Webhook receiver<br/>POST /hooks/approvals"]
            chaos["Chaos switch<br/>POST /admin/chaos (FR10 demo)"]
        end
    end

    sim -- "POST /oauth2/token<br/>(OAuth2 client credentials) → JWT" --> gw
    sim -- "MCP JSON-RPC over HTTP (POST /mcp)<br/>+ REST POST /api/agent/calls/{tool}<br/>Authorization: Bearer JWT, X-On-Behalf-Of" --> gw
    sim -. "approves held calls in the demo / QA1<br/>(REST, HTTP Basic)" .-> gw
    approver -- "approvals.html, /api/approvals/**<br/>(HTTPS/REST, HTTP Basic)" --> gw
    admin -- "/api/admin/**, Swagger UI<br/>(REST, HTTP Basic)" --> gw

    gw -- "JDBC (HikariCP, Spring Data JPA)" --> db
    gw -- "REST/JSON (RestClient)<br/>forward calls; import OpenAPI spec" --> shop
    gw -- "MCP tools/list, tools/call<br/>over Streamable HTTP (Spring AI MCP client)" --> parcels
    gw -- "REST POST (webhook,<br/>ApprovalRequested)" --> hooks

    classDef person fill:#08427b,color:#fff,stroke:#052e56
    classDef container fill:#438dd5,color:#fff,stroke:#2e6295
    classDef store fill:#438dd5,color:#fff,stroke:#2e6295
    class approver,admin person
    class sim,gw,shop,parcels,hooks,chaos container
    class db store
```

## Notes (code vs. description)

- **There is no separate "downstream MCP server" container.** The MCP server (`ParcelMcpTools`, tool `track_parcel`) runs **inside `mock-tools`** on `/mcp`, next to the REST API. The gateway registers it as MCP server `shop-parcels` (`DemoToolSeeder` in profile `docker`). It is a separate *endpoint* and *protocol*, not a separate process. Drawn as one container with four parts.
- The agent's REST endpoint is **`/api/agent/calls/{tool}`** (not `/api/calls/{tool}` as in early notes).
- **No Keycloak**: the OAuth2 token endpoint is inside the gateway (embedded Spring Authorization Server, `SecurityConfig` chain 1). Tokens are JWTs signed by the gateway and checked by the gateway's own resource-server chain (chain 4).
- **One database** holds both the policy store and the audit log. That is why QA4 ("policy store unreachable") is tested by stopping PostgreSQL, and why audit records are queued in memory during that outage (see ADR-0002).
- Ports 5432 and 8081 are published to the host only for development and the QA2 baseline.
