# ADR-0004: No automatic retry on write tools (timeout + circuit breaker only)

- **Status:** Accepted (2026-11, Phase 5)
- **Linked:** FR10 (timeouts, circuit breakers), FR6 (blast radius), FR4, QA1 (safety), QA4

## Context

Downstream tools fail and hang (FR10 demo: `delayMs 4000` > 3 s timeout). The usual fix is Resilience4j `Retry`. But `issueRefund` and `sendSms` are **not idempotent**: if the first request reached the tool and only the answer was lost, a retry refunds twice. The mock tools accept no idempotency key.

## Decision

`health.ResilientForwardStep` (Decorator around `pipeline.ForwardStep`, order 10000) runs each forward under the tool's `TimeLimiter` and `CircuitBreaker` from `ToolBreakers` and **never retries**:

- timeout → **504** `tool-timeout`; for a WRITE tool the message adds *"the action may or may not have happened, so do not repeat it blindly"*;
- breaker OPEN → **503** `circuit-open` in milliseconds (and `ToolAvailabilityStep` refuses it earlier, at order 120; the tool is hidden from tool lists);
- tool error → **502**. Only `ToolCallFailedException` and timeouts count as breaker failures (not 400s).

The agent (or its human) decides whether to try again.

**Code note:** the code applies "no retry" to **all** tools, reads included. The reason is the write tools; reads were kept the same for simplicity.

## Alternatives considered

| Option | Why not |
|---|---|
| **Retry everything** (Resilience4j `Retry`) | Double refunds / double SMS. A safety bug in a safety product. |
| **Retry reads only** (`AccessMode.READ`) | Safe, and a reasonable future change. Not done yet: adds latency on a dying tool and the breaker already gives a fast answer. |
| **Idempotency keys** (gateway sends `Idempotency-Key = callId`, tool de-duplicates) | The correct way to make write retries safe, but every downstream tool must support it; ours do not. |

## Consequences

**Good**
- A write is sent to the tool **at most once per agent call**.
- A dead tool costs 3 s at most per call, then ~30 ms while the breaker is open.

**Bad**
- A short network blip fails the call; the agent must handle 502/504.
- **Unknown outcome on write timeouts:** the refund may have happened while the audit row says `ERROR 504`. The audit trail cannot tell; a reviewer must check the tool. (Idempotency keys would fix this.)
- A retried call from the agent is a *new* call: it passes limits, policy and approval again (good for safety, slower for the agent).

## Evidence

- `docs/evidence/fr10-health.md`: 4 timeouts at ~3.04 s each (504), then 503 in **0.034 s** with the breaker OPEN; `getOrder` hidden from `/api/agent/tools`, back after recovery.
- Tests (in `ToolHealthIntegrationTest`, listed in `fr10-health.md`): `aWriteToolIsCalledExactlyOnceWhenItFails`, `aWriteToolThatTimesOutIsAlsoCalledOnceAndTheAgentIsWarned`.
