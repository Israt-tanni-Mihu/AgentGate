# ADR-0001: Hold the agent's call synchronously while a human approves (virtual threads), not 202 + polling

- **Status:** Accepted (2026-11, Phase 4)
- **Linked:** FR4 (human approval, "agent always gets a clear result"), FR8 (kill switch cancels pending approvals), QA2 (approval waits excluded), QA1 (held = safe)

## Context

A HIGH-risk call (e.g. `issueRefund`) must not reach the tool until a human decides. The wait can be seconds or minutes (`agentgate.approval.timeout`, default 2 min). Agents call us over MCP `tools/call` and REST `POST /api/agent/calls/{tool}`; both are request/response. MCP `tools/call` has no standard "accepted, ask again later" answer.

## Decision

The HTTP call **stays open**. `approval.ApprovalStep` (pipeline order 350):

1. registers a `CompletableFuture` in `ApprovalWaiters` (before saving, so no decision is missed),
2. saves a PENDING `ApprovalRequest` (`ApprovalService.open`, short transaction; no DB connection is held while waiting),
3. waits on the future with the timeout; `ApprovalWaiters` completes it when `ApprovalDecided` is committed,
4. continues the chain (approve / edits re-checked) or returns 403 with `approval-rejected | -expired | -cancelled`.

The waiting thread is a **virtual thread** (`spring.threads.virtual.enabled: true`), so a held call costs a few KB of memory, not a platform thread.

## Alternatives considered

| Option | Why not |
|---|---|
| **202 Accepted + `Location`, agent polls** | Every agent needs polling code; MCP `tools/call` has no 202 semantics, so we would need an extra "check approval" tool and the LLM would have to remember to call it. More moving parts for the same result. |
| **Callback to the agent (webhook)** | Agents are clients, often behind NAT; they would need to run a server. Out of scope ("scripted MCP clients act as agents"). |
| **Hold on platform threads (Tomcat pool)** | 200 held refunds would use all 200 worker threads and block *every* other call, including low-risk reads. |

## Consequences

**Good**
- The agent gets **one** clear answer per call (result or 403 with reason and `approvalId`); agent code stays trivial.
- Same behaviour on REST and MCP, because both go through `CallPipeline`.
- Kill switch can cancel a held call: `ApprovalKillSwitchListener` → CANCELLED → the waiter wakes up at once.

**Bad**
- Every proxy / client in front of the gateway must allow an HTTP timeout **> approval timeout** (2 min).
- **Restart loses held calls.** The agent gets a connection error; the PENDING rows stay PENDING because expiry is done by the waiting thread, not a scheduler (see `docs/architecture/approval-state.md`). Needs a startup sweep.
- `ApprovalWaiters` is in memory, so a decision must reach the **same instance** that holds the call — one gateway instance only.

## Evidence

- `docs/evidence/fr7-audit.md` (a3): `issueRefund` held, approved via `/api/approvals/{id}/approve`, agent then got the result.
- `docs/evidence/c4-observability.md`: one held-and-approved call, `agentgate_pipeline_latency_seconds_sum{decision="held"} = 0.646 s` (agent-sim approves at once).
- `docs/evidence/qa1-redteam.md`: MAL-04/05/07 detected as **HELD** (still waiting after 600 ms, not forwarded), then rejected → 403.
- **Not measured yet:** memory/latency with many calls held at the same time (e.g. 500 held + QA2 load). Suggested k6 run before the defence.
