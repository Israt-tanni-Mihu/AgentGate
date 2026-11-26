# ADR-0002: One asynchronous audit writer thread, and the crash-loss risk it accepts

- **Status:** Accepted (2026-11, Phase 4; extended for QA4)
- **Linked:** FR7 (hash-chained audit), QA3 (tamper detection), QA2 (≤ 30 ms overhead), QA4 (keep low-risk calls alive when the DB is down)

## Context

Each audit row stores `hash = SHA-256(prevHash + canonical row)`. If two threads append at the same time, both can read the same "last" row and the chain **forks**. Appending synchronously on the agent's thread would need a lock and a DB round trip per call. During a DB outage (QA4), synchronous auditing would also block every call.

## Decision

- `CallPipeline` publishes exactly one `CallCompleted` event per call (in `finally`, so denied and failed calls are included).
- `audit.AuditWriter` puts it in a bounded in-memory `ArrayBlockingQueue` (`agentgate.audit.queue-capacity: 10000`) and returns at once.
- **One** daemon thread (`audit-writer`) takes the oldest record, redacts arguments, computes the hash from the last stored row, inserts, and only then removes it from the queue (`peek` → save → `poll`).
- DB unreachable → keep the record and retry every `retry-delay` (1 s). Other DB errors → 3 attempts, then `AUDIT RECORD LOST` (loud log).
- Queue full → **drop the new record**, log `AUDIT QUEUE FULL`, increment `agentgate.audit.dropped`. Calls are not blocked.

## Alternatives considered

| Option | Why not |
|---|---|
| **Synchronous insert with a DB lock** (read last hash + insert in the call's transaction) | Correct and durable, but every call waits in line for the previous insert; a DB outage would stop all calls (breaks QA4-b). |
| **Transactional outbox table + background hasher** | Durable (no loss on crash) and still a linear chain, but adds one insert per call on the hot path and a second table/job. Best upgrade path if loss is unacceptable. |
| **Kafka / separate audit service** | Extra containers on an 8 GB laptop for one JVM. |
| **No chain, sign each row** | Cannot detect a *deleted* row in the middle. |

## Consequences

**Good**
- Audit adds ~0 ms to the call (queue offer only). The chain can never fork inside one instance.
- During a DB outage, records wait **in order** and are written after recovery; `verify` stays OK.

**Bad (accepted risks)**
- **Crash loss:** records still in the queue are lost if the JVM dies (kill -9, OOM, power). On normal shutdown the writer gets 10 s to drain, then logs `AUDIT RECORDS LOST at shutdown`. During a long DB outage, up to 10 000 records are at risk.
- **Full queue drops records** (counted, not silent) instead of blocking agents.
- **Single point of slowness:** one slow record stalls the whole audit trail (see evidence, MAL-22).
- **One gateway instance only:** a second writer would race; the primary key on `seq` makes it fail loudly, not fork silently.
- Records appear a few ms after the call ends (`AuditVerifier` flushes first, so `verify` is exact).

## Evidence

- `docs/evidence/qa2-performance.md`: gateway overhead **8.81 ms p95** at 100 calls/s with audit on for every call.
- `docs/evidence/qa4-failsafe.md`: `agentgate.audit.queue.size = 2` during the outage; after `docker start`, records written in order, `verify` → `OK, 50 records`.
- `docs/evidence/qa1-redteam.md`, failed run fix #1: a 100 000-char argument made `EmailRedactor` (ReDoS) block the **single** writer for 58 s CPU — the audit queue stopped draining. This is the "single point of slowness" risk observed for real; fixed with a linear-time regex + test.
- Test: `AuditQueueFullTest` (full queue drops loudly and counts).
