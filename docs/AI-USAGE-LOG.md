# AI usage log (C11)

## Tools used

| Tool | Used for |
|---|---|
| Claude Code (Claude Pro) | Writing and running code, tests, debugging |
| Claude (chat) | Planning, explanations, documentation review |

## Key prompts

Opening prompt of each Claude Code task, shortened (quoted from the session transcripts; dates are UTC). Every prompt
started with "Read CLAUDE.md" and/or "First read ..." and ended with the working rules (one task, tests, Bangla explanation).

| Date | Task | Prompt (short) |
|---|---|---|
| 2026-11-22 | Phase 1 skeleton | "Today's task is ONLY the Phase 1 skeleton — do not implement any FR yet. Create a Maven multi-module project with the Maven wrapper (Java 25, latest stable Spring Boot 4.1.x) ..." |
| 2026-11-23 | mock-tools | "Today's task is ONLY the mock-tools module (the fake ShopBD tool server) ... In-memory seed data: ~5 customers and ~10 orders ..." |
| 2026-11-23 | FR1 registry | "Today's task is ONLY FR1 Tool registry in the gateway module. No security, no policies, no MCP endpoint yet ..." |
| 2026-11-24 | FR2 identity | "Today's task is ONLY FR2 Agent identity ... Use Spring Authorization Server embedded in the gateway so agents authenticate with OAuth2 client credentials ..." |
| 2026-11-24 | Pipeline + FR3 | "Today's task: the core call pipeline + FR3 Policies ... design the pipeline so they plug in later. Call endpoint: POST /api/agent/calls/{toolName} ..." |
| 2026-11-24 | FR9 playground | "Today's task is ONLY FR9 Policy playground. Admin endpoint POST /api/admin/policies/playground ..." |
| 2026-11-24 | FR5 guards | "Today's task is ONLY FR5 Guards ... Add guards as new CallStep beans in the existing pipeline (no edits to existing steps except ordering) ..." |
| 2026-11-24 | FR6 + FR8 | "Today's task is FR6 Limits and FR8 Kill switch. No approvals or audit yet ..." |
| 2026-11-24 | FR2 check | "Before we start FR4, check the existing code ... Do agents authenticate with real OAuth2 client credentials ... or with a simple API key?" then "prove FR2 works with curl" |
| 2026-11-24 | FR4 approval | "Implement FR4 (Human approval) ... First read the existing pipeline, entities and kill-switch code ..." (two parts) |
| 2026-11-24 | FR7 audit | "Implement FR7 (Audit trail) part 1 ... First read the existing pipeline, the ... events, and the redaction code" (two parts) |
| 2026-11-24 | FR10 health | "Implement FR10 (Downstream health) ... First read the forwarding step that calls ShopBD, the tool registry, the kill switch ..." |
| 2026-11-24 | MCP endpoint | "Implement the MCP endpoint ... Check our Spring Boot version and choose a compatible Spring AI ..." then "Continue the MCP work ... (downstream MCP servers)" |
| 2026-11-24 | QA4 fail-safe | "Implement QA4 (fail-safe availability) ... read ... every place in the call path that reads from Postgres ..." |
| 2026-11-25 | C1 one command | "Make the whole AgentGate system start with one command (C1) ... A Dockerfile for gateway, mock-tools and agent-sim ..." |
| 2026-11-25 | QA1 red team | "Build the QA1 red-team suite ... 30 scripted malicious calls (injected refunds, cross-customer reads, ...)" |
| 2026-11-25 | QA2 load test | "Build the QA2 performance test for AgentGate using k6. First read the pipeline, especially the slowest-looking steps ..." |
| 2026-11-25 | C5 + C6 | "Add ArchUnit tests (C6) and a CI pipeline (C5) ..." |
| 2026-11-25 | C4 observability | "Add structured JSON logging and metrics (C4) ..." |
| 2026-11-26 | C7 docs | "Create the C7 architecture documentation ... First read the whole repo structure ..." |
| 2026-11-26 | FR4 restart bug | "Fix the bug you documented in ADR-0001: approvals left in PENDING after a gateway restart stay stuck forever ..." |
| 2026-11-26 | QA2 re-capture | "Re-capture the QA2 performance evidence ... currently only says 'Binary file ... matches' instead of real numbers ..." |
| 2026-11-26 | C8 patterns | "Finish C8 (pattern table) for AgentGate, plus one AI log entry ..." |
| 2026-11-26 | QA5 prep | "Get QA5 (adoption) ready for a real classmate test ..." |
| 2026-11-26 | C9 report | "Write the C9 evidence report ... Every link must point to a file that really exists. If a claim has no evidence file, mark it PARTIAL ..." |
| 2026-11-26 | C9 gaps | "Go through the full 'weak or missing' list ... Fix every item that can be fixed now ... without inventing any number." |

## Cases where the AI was wrong or incomplete (need at least 3)

### Case 1
- **Date:** 2026-11-23
- **What I asked:** Build mock-tools endpoints and give me commands to test them
- **What the AI produced:** A PowerShell error-check command using $_.ErrorDetails.Message
- **What was wrong / missing:** In Windows PowerShell 5.1 it returns empty, so the error body was not shown
- **How I found it:** Running the command against the real running server
- **How it was fixed:** Read the HTTP response stream with a StreamReader instead
- **What I learned:** AI-written commands must be tested on my own system before trusting them

### Case 2
- **Date:** 2026-11-25
- **What I asked:** Build the FR5 redaction guard (mask emails, phones, national IDs)
- **What the AI produced:** An email regex `[A-Za-z0-9._%+-]+@...` with open-ended `+`
- **What was wrong / missing:** The regex is quadratic on long input. One 100 000 character string kept the single audit-writer thread busy for minutes, so the audit queue never drained (ReDoS, one request could stall the audit trail)
- **How I found it:** QA1 red-team case MAL-22 (very long string). The suite hung, the gateway sat at 100% CPU, and a thread dump showed `EmailRedactor`
- **How it was fixed:** Lookbehind so a match starts only at the beginning of a run, bounded domain labels; test `RedactorsTest.veryLongStringsAreRedactedInLinearTime`
- **What I learned:** Regexes on user-controlled text need a long-input test, and work done on the single audit thread must never depend on input size

### Case 3
- **Date:** 2026-11-25
- **What I asked:** Block cross-customer reads when an agent acts for a customer (FR3/FR5)
- **What the AI produced:** `OwnershipCheckStep` that only checked `getOrder` results
- **What was wrong / missing:** `getCustomer` was not covered. An agent with a broad read rule (analytics-bot) acting for customer 1 could read customer 2's profile (name, email, phone)
- **How I found it:** QA1 red-team case MAL-14 came back ALLOWED
- **How it was fixed:** The step now checks `getCustomer` too (owner field `id`) for every agent; tests `OwnershipCheckStepTest` and `GuardPipelineIntegrationTest`
- **What I learned:** When a guard protects "customer data", list every tool that returns it and test each one, not only the example tool

### Case 4
- **Date:** 2026-11-25
- **What I asked:** Demo policies for ShopBD, including "own customer's data only"
- **What the AI produced:** Rule `support-read-orders` with no condition, relying on the ownership check
- **What was wrong / missing:** The ownership check only runs when an end user is named. An agent that omits the `X-On-Behalf-Of` header skipped it and read any order
- **How I found it:** QA1 red-team case MAL-15 (header dropped) came back ALLOWED
- **How it was fixed:** New operator `hasEndUser` (one new bean), added as a condition to `support-read-orders`; active dev policy moved to a new version (v2); tests `PolicyEngineTest` and `GuardPipelineIntegrationTest`
- **What I learned:** A check that depends on optional input must fail closed when the input is missing; ask "what if the agent leaves it out?"

### Case 5
- **Date:** 2026-11-26
- **What I asked:** FR4 approval flow (held call, timeout, kill switch cancel)
- **What the AI produced:** Expiry done only by the waiting agent thread; no startup clean-up
- **What was wrong / missing:** After a gateway restart, held requests stayed PENDING forever (no waiter, no timer) and had no audit record
- **How I found it:** The AI noticed it itself while writing the C4/ADR docs from the code (ADR-0001)
- **How it was fixed:** `ApprovalRestartSweep` cancels orphaned PENDING requests on startup through the State pattern, with audit record and webhook; `ApprovalRestartSweepIntegrationTest`
- **What I learned:** For every "wait in memory" design, ask "what happens to the stored state if the process dies while waiting?"

### Case 6
- **Date:** 2026-11-26
- **What I asked:** Re-capture the QA2 (k6 load) evidence with real numbers
- **What the AI produced:** Ran the evidence runner script with `*>` redirection to save all output to a file
- **What was wrong / missing:** In Windows PowerShell 5.1, `*>` wraps a native program's stderr lines as errors. Docker writes its normal progress output to stderr, so those lines became errors and stopped the script before k6 ran
- **How I found it:** The run failed: the script stopped on "errors" that were really docker's progress output
- **How it was fixed:** Ran the runner without redirection; the script itself now writes its outputs to files with UTF-8 encoding
- **What I learned:** On PowerShell 5.1, never redirect a native tool's stderr (`*>`, `2>&1`); "progress on stderr" is normal for docker, not a failure

### Case 7
- **Date:** 2026-11-26
- **What I asked:** Write the ADRs and C4 docs with measured evidence (C7), and later re-capture the QA2 numbers
- **What the AI produced:** ADR-0002, ADR-0005, `c4-component.md` and `utility-tree.md` quoted the QA2 overhead as **8.81 ms**. When QA2 was re-captured (new result **5.33 ms**), only `qa2-performance.md` was updated
- **What was wrong / missing:** Four documents kept a stale AI-written number that no longer matched the evidence file. The AI did not search for other places that quoted the old number
- **How I found it:** The C9 evidence-report review compared every number in the docs with the evidence files
- **How it was fixed:** All four now say 5.33 ms (7.16 − 1.83) and link `docs/evidence/qa2-performance.md` and `qa2/overhead.json`. The same review also found a stale "TODO, not built yet" note in `limits-and-kill-switch.md` for a feature that was already built
- **What I learned:** A number in a doc should link to its evidence file. When evidence is re-captured, search the whole repo for the old number (`grep -r "8.81" docs`)

### Case 8
- **Date:** 2026-11-26
- **What I asked:** Update the status rows of `docs/evidence-report.md` with the new evidence (C9 gaps)
- **What the AI produced:** A Node.js script that replaced each table row with a regex `String.replace`
- **What was wrong / missing:** The script overwrote the first line (the title) of the report with one 4214-character line, and the rows it was meant to change were left unchanged. The exact cause was not fully diagnosed (most likely the regex/replacement-string handling)
- **How I found it:** The AI's own check after the edit (listing each row's status) showed PARTIAL rows unchanged and a stray C5 row at the top of the file
- **How it was fixed:** Restored the file from the last commit (`git checkout -- docs/evidence-report.md`), re-applied the one earlier change, then edited line by line, matching each row by its ID and failing if a row was not found; checked the result again
- **What I learned:** After a scripted edit, always check the result. A small, checked, line-by-line edit is safer than a clever regex over the whole file

### Case 9
- **Date:** 2026-11-26
- **What I asked:** A QA3 script to verify the audit hash chain (`scripts/audit-verify.ps1`)
- **What the AI produced:** A script that took the admin login only from the shell variables `ADMIN_USERNAME` / `ADMIN_PASSWORD` and otherwise used the `local` profile default password
- **What was wrong / missing:** The docker compose stack reads the admin password from `.env`, not from the shell. With no shell variables set, the script sent the local default password and got `401 Unauthorized`
- **How I found it:** Running `audit-verify.ps1` against the docker stack after `qa5-start.ps1`
- **How it was fixed:** The script now looks for the login in the shell variables first, then in the repo's `.env` file (found through `$PSScriptRoot`, so it works from any folder), then falls back to the local default. The README and QA5 runbook now show the `powershell -ExecutionPolicy Bypass -File ...` form
- **What I learned:** A helper script must read its config from the same place the system it talks to does. Test scripts against the real docker stack, not only the dev profile
