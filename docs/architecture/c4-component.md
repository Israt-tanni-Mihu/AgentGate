# C4 Level 3 — Components of the `gateway` container

Package names are Spring Modulith-style modules under `com.agentgate.gateway`. Class names are the real ones.

## 1. The call path (one tool call)

Steps are `CallStep` beans sorted by `@Order` (`pipeline.CallStepOrder`). `CallPipeline` logs the real order at startup (`Call pipeline: [...]`).

```mermaid
flowchart TB
    agent["Agent (JWT)"]

    subgraph identity["identity"]
        sec["SecurityConfig<br/>agentChain (order 4): JWT resource server"]
        conv["AgentJwtAuthenticationConverter<br/>+ AgentDirectory (cached agents)"]
        ctx["CallContextArgumentResolver<br/>→ CallContext(agentId, endUserId, clearance)"]
    end

    subgraph edges["edges"]
        rc["pipeline.CallController<br/>POST /api/agent/calls/{tool}"]
        mc["mcp.McpController → McpMessageHandler<br/>POST /mcp (tools/list, tools/call)"]
    end

    subgraph pipe["pipeline.CallPipeline.execute()"]
        lookup["0. tool lookup: ToolDirectory.findByName<br/>(unknown → 404, disabled → 403)"]
        s1["100 admin.KillSwitchStep"]
        s2["120 health.ToolAvailabilityStep"]
        s3["150 limits.LimitStep"]
        s4["200 guard.SchemaValidationStep"]
        s5["300 policy.PolicyStep"]
        s6["350 approval.ApprovalStep"]
        s7["400 guard.TruncationStep"]
        s8["500 guard.RedactionStep"]
        s9["600 guard.OwnershipCheckStep"]
        s10["10000 health.ResilientForwardStep<br/>(decorates pipeline.ForwardStep)"]
    end

    inv["ForwardStep → ToolInvoker<br/>RestToolInvoker | McpToolInvoker"]
    tools["Downstream tools<br/>(mock-tools REST / MCP)"]
    evt(["CallCompleted event<br/>(published in finally)"])
    aw["audit.AuditWriter<br/>queue + 1 writer thread"]
    db[("PostgreSQL<br/>audit_records")]

    agent --> sec --> conv --> ctx
    ctx --> rc
    ctx --> mc
    rc --> lookup
    mc --> lookup
    lookup --> s1 --> s2 --> s3 --> s4 --> s5 --> s6 --> s7 --> s8 --> s9 --> s10 --> inv --> tools
    s10 -. "result flows back:<br/>Ownership → Redaction → Truncation → agent" .-> s9
    pipe -. "one per call, also on deny/error" .-> evt --> aw --> db
```

### Step order and what each step does

| Order | Class | Request side (before `chain.proceed`) | Result side (after) | FR |
|---|---|---|---|---|
| — | `CallPipeline` | finds the tool in `ToolDirectory`; unknown/disabled tool stops here | publishes `CallCompleted`, records metrics | FR7, C4 |
| 100 | `KillSwitchStep` | disabled agent / tool / all writes → deny | — | FR8 |
| 120 | `ToolAvailabilityStep` | breaker OPEN → 503 fast | — | FR10 |
| 150 | `LimitStep` | rate, daily quota, write blast-radius cap → 429 + `LimitBreachedEvent` | — | FR6 |
| 200 | `SchemaValidationStep` | arguments vs. tool JSON schema → 400 | — | FR5 |
| 300 | `PolicyStep` | `PolicyEngine` on `ActivePolicyCache` snapshot; default deny; dry-run; QA4 fail-closed | — | FR3, QA4 |
| 350 | `ApprovalStep` | HIGH risk → PENDING request, call **waits**; edited args re-run schema + policy | — | FR4 |
| 400 | `TruncationStep` | — | cuts results > 8 KB, adds notice (runs **last**) | FR5 |
| 500 | `RedactionStep` | — | masks email / BD phone / NID by clearance (`DataRedactor` strategies) | FR5 |
| 600 | `OwnershipCheckStep` | — | withholds `getOrder`/`getCustomer` results of another customer (runs **first**) | FR3, QA1 |
| 10000 | `ResilientForwardStep` → `ForwardStep` | per-tool `TimeLimiter` + `CircuitBreaker`, **no retry**; picks invoker by `ToolKind` | — | FR10, FR1 |

**Not a step:** audit. It is an Observer of `CallCompleted` (see `docs/patterns.md`), so even calls stopped by an exception in any step are recorded.

## 2. Supporting components (admin side, state, events)

```mermaid
flowchart LR
    subgraph registry["registry"]
        tc["ToolController / ToolService"]
        imp["importer.ToolImportService<br/>+ OpenApiToolSpecAdapter"]
        mcps["mcp.McpServerService<br/>+ McpToolAdapter, McpDownstreamClient"]
        td["ToolDirectory<br/>(in-memory, refresh 5 s)"]
    end

    subgraph policy["policy"]
        pac["PolicyAdminController / PolicyAdminService<br/>(versions, activate, dry-run)"]
        cache["ActivePolicyCache<br/>(refresh 5 s, STALE on DB outage)"]
        eng["PolicyEngine<br/>+ ConditionOperator strategies"]
    end

    subgraph playground["playground"]
        pg["PolicyPlaygroundController / Service"]
    end

    subgraph approval["approval"]
        ac["ApprovalController + approvals.html"]
        as["ApprovalService<br/>(ApprovalRequest + State objects)"]
        aw["ApprovalWaiters<br/>(CompletableFuture per held call)"]
        wh["ApprovalWebhookNotifier"]
        akl["ApprovalKillSwitchListener"]
    end

    subgraph admin["admin"]
        ksc["KillSwitchController / KillSwitchService"]
        kss["KillSwitchState (in-memory set)"]
    end

    subgraph health["health"]
        tb["ToolBreakers<br/>(Resilience4j breaker + limiter per tool)"]
        th["ToolHealthController"]
    end

    subgraph limits["limits"]
        rcnt["RateCounters (fixed windows)"]
        al["AlertService"]
    end

    subgraph audit["audit"]
        aud["AuditWriter"]
        ver["AuditVerifier / AuditSearchService / AuditExporter"]
    end

    cat["catalog.ToolCatalogService<br/>(Facade: what may this agent see?)"]
    db[("PostgreSQL")]

    tc --> db
    imp --> tc
    mcps --> tc
    td --> db
    pac --> db
    pac -- "refresh now" --> cache
    cache --> db
    pg --> eng
    pg --> cache
    ac --> as
    as --> db
    as -- "ApprovalRequested" --> wh
    as -- "ApprovalDecided (after commit)" --> aw
    ksc --> kss
    ksc -- "KillSwitchActivated" --> akl --> as
    th --> tb
    rcnt -- "LimitBreachedEvent" --> al --> db
    aud --> db
    ver --> db
    cat --> td
    cat --> eng
    cat --> kss
    cat --> tb
```

- **In-memory state read on every call** (no DB round trip on the hot path): `ToolDirectory`, `AgentDirectory`, `ActivePolicyCache`, `KillSwitchState`, `RateCounters`, `ToolBreakers`. This is what keeps QA2 overhead at 8.81 ms p95.
- `ToolCatalogService` feeds both `GET /api/agent/tools` and MCP `tools/list`, so both lists agree (kill switch, policy, breaker all applied).

## Notes (code vs. description)

- **Audit is not a pipeline step** and the **kill switch is not only an admin service**: it is both `KillSwitchService` (admin) and `KillSwitchStep` (first pipeline step, order 100).
- **Limits come before the schema check**, and kill switch / tool availability come before limits. So "schema before policy" is true, but schema is the 4th step, not the 1st.
- The **circuit breaker appears twice**: `ToolAvailabilityStep` (120, fail fast before using quota or a human's time) and `ResilientForwardStep` (10000, the breaker that actually records failures).
- `ForwardStep` is **not a Spring bean**; `health.HealthConfig` creates it and wraps it in `ResilientForwardStep` (Decorator).
