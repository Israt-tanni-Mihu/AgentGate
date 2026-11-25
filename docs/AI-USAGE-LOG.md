# AI usage log (C11)

## Tools used

| Tool | Used for |
|---|---|
| Claude Code (Claude Pro) | Writing and running code, tests, debugging |
| Claude (chat) | Planning, explanations, documentation review |

## Key prompts

| Date | Task | Prompt (short) |
|---|---|---|
| | | |

## Cases where the AI was wrong or incomplete (need at least 3)

### Case 1
- - **Date:** 2026-11-23
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
