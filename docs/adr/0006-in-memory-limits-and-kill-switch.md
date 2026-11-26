# ADR-0006: in-memory fixed-window limits and kill switch state

**Status:** Accepted (2026-11-26, after the QA2 and FR6/FR8 evidence runs; was a candidate in Phase 3).
**Requirements:** FR6 (limits), FR8 (kill switch), QA2 (gateway adds ≤ 30 ms p95 at 100 calls/s), QA4 (fail-safe).

## Context

FR6 needs counters for rate limits, daily quotas and the write cap. FR8 needs a switch that takes effect at once.
The course runs on one laptop with an 8 GB limit, and the whole system must start with one `docker compose up`.

## Decision

- Counters are **fixed-window counters in a `ConcurrentHashMap`** (`limits.RateCounters`). No library. A few lines of code, easy to explain in the defence.
- The kill switch state is a **set in memory** (`admin.KillSwitchState`), loaded from PostgreSQL at startup and updated by the admin API in the same request.
- Both live **inside one gateway instance**. Nothing is shared between instances.

## Alternatives considered

| Option | Why not now |
|---|---|
| **Bucket4j** (token bucket, library) | Good for smooth rate limiting, and it can use Redis or JCache as a shared store. It adds a dependency and a second concept (tokens, refill) for a test we can already meet with fixed windows. Can replace `RateCounters` later behind the same `LimitStep`. |
| **Redis** shared counters | The right answer for several instances: one count for all. Costs another container on an 8 GB laptop, a network hop per call (QA2 budget is 30 ms), and a new failure mode (Redis down → fail closed or open to decide). Not needed for one instance. |
| **Database counters** (PostgreSQL row per window, `UPDATE … RETURNING`) | Shared and durable, but one database round trip per call. That is too slow for QA2 at 100 calls/s on a laptop. |
| **Sliding-window log** | Exact, but stores every timestamp. Memory grows with traffic. Fixed windows are good enough for a course-scale demo, and the window edge effect is documented. |

## Consequences

- **Good:** no extra container; a call pays a map lookup; the code is small and readable in the defence.
- **Bad, per instance:** with N instances a limit is exceeded up to N times. A kill switch reaches other instances only after they restart. Counts reset on restart.
- **Bad, fixed windows:** a burst across a window boundary can pass up to 2× the limit.
- **Mitigation for the kill switch:** the database is the truth. A later version can reload the state every few seconds (like the policy cache) so a second instance catches up within seconds.

## What would change with a shared store (Redis)

1. `RateCounters` becomes a Redis `INCR` with `EXPIRE` per window key. `LimitStep` does not change.
2. `KillSwitchState` is refreshed from Redis pub/sub (or from the database every few seconds). Its interface does not change.
3. Limits become exact across instances, and a switch reaches every instance within a second.
4. The cost: one more container, one network hop per call, and a rule for what to do when Redis is down (QA4: medium/high-risk tools fail closed, low-risk tools use the last known state).

## Evidence

- **Cost on the hot path (upper bound):** [qa2-performance.md](../evidence/qa2-performance.md): the whole gateway adds
  **5.33 ms p95** at 100 calls/s, and `LimitStep` and `KillSwitchStep` run on every one of those calls (QA2 only raises the
  ceilings, the step still counts). So the in-memory limits cost at most part of 5.33 ms, far under the 30 ms budget.
- **Behaviour:** [fr-governance-run.md](../evidence/fr-governance-run.md): 61 calls in one minute → 60 × 200, 1 × 429
  `agent-rate-per-minute`, one alert; the kill switch blocks the very next call (agent, tool, all writes).
- **The fixed-window edge effect was seen in a real run:** in one run the 61 calls crossed a minute boundary and all
  61 passed (same file, "Failed / odd runs"). This is the "up to 2× at the window edge" consequence above.

**Not measured:** the cost of `LimitStep` on its own (a k6 run with and without the step would need a switch to turn the
step off), and two instances counting separately (it follows from the design; no second instance was started).
