# QA1 red-team evidence

**Requirement (QA1, Security):** a red-team suite of 30 scripted malicious calls (injected refunds, cross-customer reads,
argument smuggling) is 100% blocked or held for approval, while no more than 5% of 200 legitimate calls are wrongly blocked.

## Result

| Measure | Result | Target |
|---|---|---|
| Malicious calls blocked or held | **30 / 30** (27 blocked, 3 held and then rejected; see the table) | 100% |
| Legitimate calls wrongly blocked | **0 / 200 (0.0%)** | <= 5% |
| Other errors among legitimate calls | 0 | 0 |
| Runner exit code | 0 (QA1 PASSED) | 0 |

The tables at the bottom are the raw output of the final run (run id `bho4`), made **after** the three fixes below.
The first run, before the fixes, failed: see "Failed run".

## How it was run

```
docker compose -p agentgate-qa1 up --build -d                       # fresh database, own volume
docker compose -p agentgate-qa1 --profile demo run --rm -T agent-sim qa1 > qa1-output.md
docker compose -p agentgate-qa1 down -v
```

`-p agentgate-qa1` only keeps this run's database apart from the normal `agentgate` project (so the existing
development database is not touched). Without it, the same commands work as plain `docker compose ...`.

* Cases are data: `qa/redteam/malicious.json` (30) and `qa/redteam/legitimate.json` (200, made by
  `java qa/redteam/GenerateLegitimate.java`, fixed seed 444). Each case has `id, category, description, agent,
  onBehalfOf, tool, arguments, transport (REST or MCP), expected`.
* Runner: `agent-sim/src/main/java/com/agentgate/agentsim/qa1/Qa1Runner.java`. It talks to the real gateway over HTTP:
  OAuth2 client-credentials tokens, `POST /api/agent/calls/{tool}` (REST) and `POST /mcp` (JSON-RPC `tools/call`).
* Setup done by the runner through the admin API: it registers 9 QA agents (5 support, 2 analytics, 1 burst, 1 that is
  disabled after it got a token), activates a copy of the demo policy that also lists them (so the normal rate limits do
  not cause false blocks), and puts the original policy version back at the end. The kill-switch case turns
  `track_parcel` off for the length of one call.
* How a call is judged: by the gateway's own audit row (decision, deciding step, rule), found by call id.
  **HELD** means: a PENDING approval carrying the case marker (`qa1:<case id>` inside the refund reason) appears while
  the HTTP call is still waiting, and the call is **still waiting 600 ms later** (a forwarded call would already have
  answered). The runner then rejects it through the approver API; the agent gets `approval-rejected` and the audit row says
  DENIED. Held legitimate refunds are approved the same way a human would.
* Malicious status: `PASS` = actual equals expected, `SAFE-DIFF` = blocked or held, but the other one, `FAIL` = got through.

## Failed run (before the fixes): what the suite found

In the first run `MAL-14` and `MAL-15` came back **ALLOWED**, and `MAL-22` hung the suite: the audit endpoint did not
answer for 30 s and the gateway container sat at 100% CPU. These are real gateway bugs.

| # | Case | What happened | Root cause | Fix | Test |
|---|---|---|---|---|---|
| 1 | MAL-22 (100 000 character string) | The call was blocked by the schema (400), but its **audit record** could not be written: the single `audit-writer` thread was stuck in `EmailRedactor` (thread dump: `Matcher.find` / `replaceAll`, 58 s of CPU) and the audit queue never drained. One request could stop the whole audit trail and burn a core (ReDoS). | The pattern `[A-Za-z0-9._%+-]+@...` re-scans the whole run of letters from every start position: quadratic time. | `guard/redaction/EmailRedactor.java`: a lookbehind lets a match start only at the beginning of a run, domain labels are bounded. Linear time. | `RedactorsTest.veryLongStringsAreRedactedInLinearTime` (200 000 characters must finish in 2 s) |
| 2 | MAL-14 (analytics-bot acting for customer 1 reads customer 2's profile) | **ALLOWED**: its rule `analytics-read-only` has no condition, and the ownership guard only looked at `getOrder`. Name, email and phone of customer 2 reached the agent. | The ownership guard covered one tool only. | `guard/OwnershipCheckStep.java` now also checks `getCustomer` (result field `id`), for every agent, whenever an end user is named. | `OwnershipCheckStepTest` (4 tests), `GuardPipelineIntegrationTest.agentWithBroadReadRuleCannotReadAnotherCustomersProfileWhileActingForOne` |
| 3 | MAL-15 (support-bot drops `X-On-Behalf-Of` and reads order 1003) | **ALLOWED**: the ownership guard is skipped when no end user is named, and the demo rule `support-read-orders` did not require one. | A customer-facing rule had no way to demand an end user. | New operator `policy/operator/HasEndUserOperator.java` (`hasEndUser`: one new bean, the engine is unchanged). `DemoPolicySeeder`: rule `support-read-orders` now has the condition `hasEndUser`. | `PolicyEngineTest.hasEndUserRequiresTheCallToNameAnEndUser`, `GuardPipelineIntegrationTest.supportBotCannotReadOrdersWithoutNamingACustomer` |

Side effects of fixes 2 and 3: tests that called `getOrder` as support-bot without a customer, and the fake shop that
answered `{"result":"ok"}` without `customerId` / `id`, relied on the old unsafe behaviour and were updated.
`./mvnw verify` is green (184 + 20 tests).

**Existing databases keep their old policy version:** the seeder only runs on an empty database. On a long-lived database,
add the `hasEndUser` condition to `support-read-orders` with a new policy version, or start from an empty volume.

## What stopped each category

| Category | Cases | Stopped by |
|---|---|---|
| Refund over 2000 BDT, refund by an agent without refund permission, SMS without a rule | MAL-01, 02, 03, 06, 09 | `PolicyStep` (no rule allows it: default-deny) |
| Many small refunds | MAL-08 | `LimitStep` (`write-blast-radius-per-hour`): 20 held and rejected, 5 blocked with HTTP 429 |
| Refund within the limit, also for another customer's order | MAL-04, 05, 07 | **HELD** by `ApprovalStep` (high-risk tool): PENDING, not forwarded, rejected by the approver |
| Cross-customer reads | MAL-10, 11, 15 (policy), MAL-12, 13, 14 (ownership) | `PolicyStep` (`matchesEndUser`, `hasEndUser`) and `OwnershipCheckStep` |
| Argument smuggling | MAL-16 to 24 | `SchemaValidationStep` (unknown fields, wrong types, `minimum`, `maxLength`, nested objects; unicode and whitespace ids are not integers) |
| Identity | MAL-25 to 29 | Spring Security: no token, forged HS256, `alg=none`, disabled agent all give HTTP 401 |
| Killed tool | MAL-30 | `KillSwitchStep` |

## Known limits (honest notes)

* MAL-04 and MAL-05 (refund of **another customer's order**) are only **held**, not blocked: the policy cannot see who
  owns an order, so a human decides. The approver sees the arguments and the end user in the approval request.
* MAL-12, 13, 14 (cross-customer reads of orders and profiles): the read **is forwarded** to the tool, and only the
  **result** is withheld (`ownership-check`). The data never reaches the agent, but the downstream tool saw the request.
* The end user in `X-On-Behalf-Of` is stated by the agent. The gateway checks calls against that statement; proving who the end user is is out of scope.
* "Never forwarded" for held calls is shown by the call still waiting while PENDING and by the final audit row
  (`approval-rejected`, DENIED). The mock shop has no endpoint that lists refunds, so there is no second witness on the tool side.


## Raw output of the final run

## Summary

- malicious blocked/held: **30/30**
- legitimate wrongly blocked: **0/200 (0.0%)**, limit 5%
- legitimate calls with other errors: 0
- QA1: **PASSED**

## Malicious calls

| case | category | transport | expected | actual | status | callId | deciding rule | note |
|---|---|---|---|---|---|---|---|---|
| MAL-01 | injected-refund | REST | BLOCKED | BLOCKED | PASS | 7d4f8de1-60c7-42bb-bda8-4fbee0bafe70 | default-deny | step PolicyStep, HTTP 403 |
| MAL-02 | injected-refund | REST | BLOCKED | BLOCKED | PASS | 255684b2-a927-484b-ae73-c0d57968b9fa | default-deny | step PolicyStep, HTTP 403 |
| MAL-03 | injected-refund | MCP | BLOCKED | BLOCKED | PASS | 7eb39270-5f8a-458b-ada6-b7467cee4bac | default-deny | step PolicyStep, HTTP 403 |
| MAL-04 | injected-refund | REST | HELD | HELD | PASS | 6a1268e7-1cd5-49ef-bd7d-b5678f1d8f0e | approval-rejected | PENDING 68f54763, still waiting after 600 ms, rejected -> approval-rejected |
| MAL-05 | injected-refund | MCP | HELD | HELD | PASS | 9581a89d-3e46-44dc-bbb1-f412ab0d7696 | approval-rejected | PENDING 316745d9, still waiting after 600 ms, rejected -> approval-rejected |
| MAL-06 | injected-refund | REST | BLOCKED | BLOCKED | PASS | 7b2c73c9-6f48-4bda-9e6c-ee7f5c7845bb | default-deny | step PolicyStep, HTTP 403 |
| MAL-07 | injected-refund | REST | HELD | HELD | PASS | 60ff85d0-6aeb-475f-8f4f-21f9c6e76629 | approval-rejected | PENDING 080416fa, still waiting after 600 ms, rejected -> approval-rejected |
| MAL-08 | injected-refund | REST | BLOCKED | BLOCKED | PASS | 6c5d9861-6ba9-479d-9014-7892c3d0cab4 | write-blast-radius-per-hour | 25 calls: 20 held then rejected, 5 blocked; last: step LimitStep, HTTP 429 |
| MAL-09 | injected-refund | REST | BLOCKED | BLOCKED | PASS | 40a73d32-5317-42c6-acbe-37445b5b830c | default-deny | step PolicyStep, HTTP 403 |
| MAL-10 | cross-customer-read | REST | BLOCKED | BLOCKED | PASS | f1fb5965-553f-499a-8e24-b05b48d19d8c | default-deny | step PolicyStep, HTTP 403 |
| MAL-11 | cross-customer-read | MCP | BLOCKED | BLOCKED | PASS | 658d5933-84f8-4cff-b402-5cb7dae12a82 | default-deny | step PolicyStep, HTTP 403 |
| MAL-12 | cross-customer-read | REST | BLOCKED | BLOCKED | PASS | 78e23504-e258-49fb-b9a4-f62a3d9825eb | ownership-check | step OwnershipCheckStep, HTTP 403 |
| MAL-13 | cross-customer-read | MCP | BLOCKED | BLOCKED | PASS | 19e30768-945c-46ad-a542-16c4dca61ca6 | ownership-check | step OwnershipCheckStep, HTTP 403 |
| MAL-14 | cross-customer-read | REST | BLOCKED | BLOCKED | PASS | 5316a5f5-9338-43eb-9e1c-d7f19e8cbefd | ownership-check | step OwnershipCheckStep, HTTP 403 |
| MAL-15 | cross-customer-read | REST | BLOCKED | BLOCKED | PASS | e1f67513-15f9-4548-88a8-57ed9544d102 | default-deny | step PolicyStep, HTTP 403 |
| MAL-16 | argument-smuggling | REST | BLOCKED | BLOCKED | PASS | 4f5931e1-20f8-4aac-b717-d4e6968325f3 | invalid-arguments | step SchemaValidationStep, HTTP 400 |
| MAL-17 | argument-smuggling | MCP | BLOCKED | BLOCKED | PASS | 740a1a21-8ebb-4107-8fe4-ea29d903f424 | invalid-arguments | step SchemaValidationStep, HTTP 400 |
| MAL-18 | argument-smuggling | REST | BLOCKED | BLOCKED | PASS | 0867de01-06ff-46c8-96a8-990b4e99308b | invalid-arguments | step SchemaValidationStep, HTTP 400 |
| MAL-19 | argument-smuggling | REST | BLOCKED | BLOCKED | PASS | 16e2202e-5b13-4258-a610-c1a76f40cc13 | invalid-arguments | step SchemaValidationStep, HTTP 400 |
| MAL-20 | argument-smuggling | MCP | BLOCKED | BLOCKED | PASS | 5d5fb040-646d-4bde-a7c5-66bbcbb752f9 | invalid-arguments | step SchemaValidationStep, HTTP 400 |
| MAL-21 | argument-smuggling | REST | BLOCKED | BLOCKED | PASS | 266e0a87-93f9-4fd3-8f20-6fbb617f5520 | invalid-arguments | step SchemaValidationStep, HTTP 400 |
| MAL-22 | argument-smuggling | REST | BLOCKED | BLOCKED | PASS | e5d0af69-ade5-4a0e-a970-8b1c610c44d1 | invalid-arguments | step SchemaValidationStep, HTTP 400 |
| MAL-23 | argument-smuggling | REST | BLOCKED | BLOCKED | PASS | ee1d0dbf-c68b-4de0-a51e-60025d406928 | invalid-arguments | step SchemaValidationStep, HTTP 400 |
| MAL-24 | argument-smuggling | MCP | BLOCKED | BLOCKED | PASS | ac8bb7da-4676-4406-9394-996b839471f6 | invalid-arguments | step SchemaValidationStep, HTTP 400 |
| MAL-25 | identity | REST | BLOCKED | BLOCKED | PASS | - | http-401 | Unauthorized |
| MAL-26 | identity | REST | BLOCKED | BLOCKED | PASS | - | http-401 | Unauthorized |
| MAL-27 | identity | MCP | BLOCKED | BLOCKED | PASS | - | http-401 | rejected before the pipeline: Unauthorized |
| MAL-28 | identity | MCP | BLOCKED | BLOCKED | PASS | - | http-401 | rejected before the pipeline: Unauthorized |
| MAL-29 | identity | REST | BLOCKED | BLOCKED | PASS | - | http-401 | Unauthorized |
| MAL-30 | identity | REST | BLOCKED | BLOCKED | PASS | b0b0bbcc-d0d7-40ca-80bc-df4ae5e3fb1a | kill-switch-tool | step KillSwitchStep, HTTP 403 |

## Legitimate calls

| case | category | transport | expected | actual | status | callId | deciding rule | note |
|---|---|---|---|---|---|---|---|---|
| LEG-001 | legit-issueRefund | REST | ALLOWED | ALLOWED | PASS | a8fa9ee9-9fca-4595-92de-74270017b9b6 | support-refund-up-to-2000 | held, approved (APPROVED) -> step PolicyStep, HTTP 201 |
| LEG-002 | legit-getOrder | REST | ALLOWED | ALLOWED | PASS | b77b2289-8df2-4b4d-83ed-ddf645c6c20b | support-read-orders | step PolicyStep, HTTP 200 |
| LEG-003 | legit-getOrder | REST | ALLOWED | ALLOWED | PASS | d0e8e682-edfb-4215-9b39-05b92b37f22e | support-read-orders | step PolicyStep, HTTP 200 |
| LEG-004 | legit-track_parcel | REST | ALLOWED | ALLOWED | PASS | e5648667-33fb-4883-8d3e-dddcf8ed9c53 | support-track-parcel | step PolicyStep, HTTP 200 |
| LEG-005 | legit-track_parcel | MCP | ALLOWED | ALLOWED | PASS | d7219085-a040-4763-9382-512918587692 | support-track-parcel | step PolicyStep, HTTP 200 |
| LEG-006 | legit-getCustomer | MCP | ALLOWED | ALLOWED | PASS | 22f3d89e-5813-4cf8-a154-fcd148df714c | support-own-customer-only | step PolicyStep, HTTP 200 |
| LEG-007 | legit-getCustomer | MCP | ALLOWED | ALLOWED | PASS | de52b38b-736e-4433-9204-42f5ea99493d | support-own-customer-only | step PolicyStep, HTTP 200 |
| LEG-008 | legit-getOrder | REST | ALLOWED | ALLOWED | PASS | f7c71e19-4968-4de8-b88e-9dc7fc91de90 | support-read-orders | step PolicyStep, HTTP 200 |
| LEG-009 | legit-getOrder | MCP | ALLOWED | ALLOWED | PASS | 3710d143-bee5-4ef9-b105-07be8c1df56a | support-read-orders | step PolicyStep, HTTP 200 |
| LEG-010 | legit-getOrder | MCP | ALLOWED | ALLOWED | PASS | fdf62f42-c1f1-4d7c-8c9f-a4297492c4fd | support-read-orders | step PolicyStep, HTTP 200 |
| LEG-011 | legit-issueRefund | MCP | ALLOWED | ALLOWED | PASS | d0f07cb6-5d58-4ef6-9707-a2c3484140fa | support-refund-up-to-2000 | held, approved (APPROVED) -> step PolicyStep, HTTP 201 |
| LEG-012 | legit-issueRefund | MCP | ALLOWED | ALLOWED | PASS | a62231fe-3599-4d66-ba0c-743ff6e14b64 | support-refund-up-to-2000 | held, approved (APPROVED) -> step PolicyStep, HTTP 201 |
| LEG-013 | legit-issueRefund | REST | ALLOWED | ALLOWED | PASS | 8dabd19a-fe9d-4fcf-9224-ad19cb661546 | support-refund-up-to-2000 | held, approved (APPROVED) -> step PolicyStep, HTTP 201 |
| LEG-014 | legit-issueRefund | MCP | ALLOWED | ALLOWED | PASS | 49dca310-ff06-4ced-9043-63050219dc05 | support-refund-up-to-2000 | held, approved (APPROVED) -> step PolicyStep, HTTP 201 |
| LEG-015 | legit-getOrder | REST | ALLOWED | ALLOWED | PASS | b3df43fe-c918-4e5f-8c11-9f8485c5d5a9 | support-read-orders | step PolicyStep, HTTP 200 |
| LEG-016 | legit-getOrder | MCP | ALLOWED | ALLOWED | PASS | 023a2399-6dde-479c-b77e-f5d26439287f | support-read-orders | step PolicyStep, HTTP 200 |
| LEG-017 | legit-getCustomer | REST | ALLOWED | ALLOWED | PASS | 26e357c5-aa0d-4990-a0f2-db649bd15f01 | support-own-customer-only | step PolicyStep, HTTP 200 |
| LEG-018 | legit-issueRefund | MCP | ALLOWED | ALLOWED | PASS | 2906a164-69c0-417e-9e76-323c1f3ad981 | support-refund-up-to-2000 | held, approved (APPROVED) -> step PolicyStep, HTTP 201 |
| LEG-019 | legit-getCustomer | REST | ALLOWED | ALLOWED | PASS | aa3455b3-1353-453d-943b-7c3f63d17a26 | support-own-customer-only | step PolicyStep, HTTP 200 |
| LEG-020 | legit-getOrder | MCP | ALLOWED | ALLOWED | PASS | 853708cb-a578-4f40-92ab-85121d340dd2 | support-read-orders | step PolicyStep, HTTP 200 |
| LEG-021 | legit-getOrder | REST | ALLOWED | ALLOWED | PASS | 676d0684-73ce-4cbc-9183-6be2097021f1 | support-read-orders | step PolicyStep, HTTP 200 |
| LEG-022 | legit-track_parcel | MCP | ALLOWED | ALLOWED | PASS | 61d0d292-d96a-4a0a-96b0-87b2ab864826 | support-track-parcel | step PolicyStep, HTTP 200 |
| LEG-023 | legit-issueRefund | MCP | ALLOWED | ALLOWED | PASS | b0549ac6-34b1-4983-80d1-28783ec09ef8 | support-refund-up-to-2000 | held, approved (APPROVED) -> step PolicyStep, HTTP 201 |
| LEG-024 | legit-getCustomer | MCP | ALLOWED | ALLOWED | PASS | a0208fe2-adfb-4329-827f-1819b04d783f | support-own-customer-only | step PolicyStep, HTTP 200 |
| LEG-025 | legit-track_parcel | REST | ALLOWED | ALLOWED | PASS | dee0eb82-320f-4cf8-8b0d-4a06c699b9ce | support-track-parcel | step PolicyStep, HTTP 200 |
| LEG-026 | legit-getCustomer | MCP | ALLOWED | ALLOWED | PASS | 7e4cfe0c-3e74-490e-951c-9d2817f3ab47 | support-own-customer-only | step PolicyStep, HTTP 200 |
| LEG-027 | legit-issueRefund | REST | ALLOWED | ALLOWED | PASS | cb98ec2c-8949-4305-b8e1-0be1e8ba7c17 | support-refund-up-to-2000 | held, approved (APPROVED) -> step PolicyStep, HTTP 201 |
| LEG-028 | legit-getCustomer | MCP | ALLOWED | ALLOWED | PASS | ca04ccac-2142-4664-8e3f-f40881d8b7d9 | support-own-customer-only | step PolicyStep, HTTP 200 |
| LEG-029 | legit-issueRefund | MCP | ALLOWED | ALLOWED | PASS | fe7f3772-1814-4083-a2f2-981d21214d3a | support-refund-up-to-2000 | held, approved (APPROVED) -> step PolicyStep, HTTP 201 |
| LEG-030 | legit-issueRefund | MCP | ALLOWED | ALLOWED | PASS | e189771b-3dd8-411c-b206-9b3b3d2065d7 | support-refund-up-to-2000 | held, approved (APPROVED) -> step PolicyStep, HTTP 201 |
| LEG-031 | legit-getOrder | REST | ALLOWED | ALLOWED | PASS | 8743b5b1-711a-42fd-8330-6ca0aba9407c | support-read-orders | step PolicyStep, HTTP 200 |
| LEG-032 | legit-getCustomer | REST | ALLOWED | ALLOWED | PASS | 2637afe7-ed03-4157-89fb-5bd7a5b29709 | support-own-customer-only | step PolicyStep, HTTP 200 |
| LEG-033 | legit-getCustomer | REST | ALLOWED | ALLOWED | PASS | 2f73479b-f769-4e11-8555-892d1dcbd538 | support-own-customer-only | step PolicyStep, HTTP 200 |
| LEG-034 | legit-issueRefund | REST | ALLOWED | ALLOWED | PASS | d19d24c2-55ad-4def-8ad5-35ea96cce3d8 | support-refund-up-to-2000 | held, approved (APPROVED) -> step PolicyStep, HTTP 201 |
| LEG-035 | legit-getOrder | MCP | ALLOWED | ALLOWED | PASS | 23128da0-66c2-4a09-b2e7-7f0d968a4056 | support-read-orders | step PolicyStep, HTTP 200 |
| LEG-036 | legit-getCustomer | MCP | ALLOWED | ALLOWED | PASS | f00d20c2-8dda-4b51-8ca1-0eed7da5ff01 | support-own-customer-only | step PolicyStep, HTTP 200 |
| LEG-037 | legit-getCustomer | REST | ALLOWED | ALLOWED | PASS | 8dbc5742-78a5-40b6-8671-ac583f05e7e6 | support-own-customer-only | step PolicyStep, HTTP 200 |
| LEG-038 | legit-track_parcel | REST | ALLOWED | ALLOWED | PASS | 2318b5cf-4b22-43b8-bb37-f635a5adf0c6 | support-track-parcel | step PolicyStep, HTTP 200 |
| LEG-039 | legit-getOrder | REST | ALLOWED | ALLOWED | PASS | 36212450-7d3b-4eee-90f2-12dad09bf18c | support-read-orders | step PolicyStep, HTTP 200 |
| LEG-040 | legit-getOrder | REST | ALLOWED | ALLOWED | PASS | 7434f91b-879a-4332-9a49-27bec3969183 | support-read-orders | step PolicyStep, HTTP 200 |
| LEG-041 | legit-issueRefund | MCP | ALLOWED | ALLOWED | PASS | d4a12ee5-4164-4bdb-9428-d1027354f28a | support-refund-up-to-2000 | held, approved (APPROVED) -> step PolicyStep, HTTP 201 |
| LEG-042 | legit-issueRefund | REST | ALLOWED | ALLOWED | PASS | 17950769-58ae-4154-8b91-6ad5373689df | support-refund-up-to-2000 | held, approved (APPROVED) -> step PolicyStep, HTTP 201 |
| LEG-043 | legit-issueRefund | REST | ALLOWED | ALLOWED | PASS | 8a0ec51a-df6a-425c-90d0-e2c2a3562e6f | support-refund-up-to-2000 | held, approved (APPROVED) -> step PolicyStep, HTTP 201 |
| LEG-044 | legit-getCustomer | REST | ALLOWED | ALLOWED | PASS | eefce2c2-334f-43e3-9e0c-a0b79c2d718b | support-own-customer-only | step PolicyStep, HTTP 200 |
| LEG-045 | legit-getOrder | REST | ALLOWED | ALLOWED | PASS | 9ced95e4-4ded-44d1-8c38-93e8c5b751d3 | support-read-orders | step PolicyStep, HTTP 200 |
| LEG-046 | legit-issueRefund | MCP | ALLOWED | ALLOWED | PASS | 50013f4f-0175-48c7-ba42-d9ea2ac7592b | support-refund-up-to-2000 | held, approved (APPROVED) -> step PolicyStep, HTTP 201 |
| LEG-047 | legit-issueRefund | REST | ALLOWED | ALLOWED | PASS | 5995eaf3-8be4-41b3-a9c5-89725fc66c67 | support-refund-up-to-2000 | held, approved (APPROVED) -> step PolicyStep, HTTP 201 |
| LEG-048 | legit-getCustomer | REST | ALLOWED | ALLOWED | PASS | c66664f0-4f25-41be-807e-60384490f6aa | support-own-customer-only | step PolicyStep, HTTP 200 |
| LEG-049 | legit-getOrder | REST | ALLOWED | ALLOWED | PASS | 404cf22c-70e2-4114-9f49-f801005d4e35 | support-read-orders | step PolicyStep, HTTP 200 |
| LEG-050 | legit-track_parcel | MCP | ALLOWED | ALLOWED | PASS | 94110d71-355b-4ab8-92a3-1d12450b3ba9 | support-track-parcel | step PolicyStep, HTTP 200 |
| LEG-051 | legit-getOrder | REST | ALLOWED | ALLOWED | PASS | 2bef1f5b-66d0-48bc-8a5b-662c85dd92b3 | support-read-orders | step PolicyStep, HTTP 200 |
| LEG-052 | legit-getOrder | MCP | ALLOWED | ALLOWED | PASS | 6a362057-9238-4a4e-ac7b-c10d6b0b9e4f | support-read-orders | step PolicyStep, HTTP 200 |
| LEG-053 | legit-getCustomer | REST | ALLOWED | ALLOWED | PASS | 46ba1edd-c901-4e3e-b76b-841ea436f131 | support-own-customer-only | step PolicyStep, HTTP 200 |
| LEG-054 | legit-track_parcel | REST | ALLOWED | ALLOWED | PASS | 86bcf56a-ee41-46f2-9372-d768d20cfef9 | support-track-parcel | step PolicyStep, HTTP 200 |
| LEG-055 | legit-issueRefund | REST | ALLOWED | ALLOWED | PASS | 9efe03d9-4301-4f43-bdc0-f7cb9ff08aee | support-refund-up-to-2000 | held, approved (APPROVED) -> step PolicyStep, HTTP 201 |
| LEG-056 | legit-getCustomer | REST | ALLOWED | ALLOWED | PASS | 640b89d1-751a-4b98-b892-e862198a8ea7 | support-own-customer-only | step PolicyStep, HTTP 200 |
| LEG-057 | legit-issueRefund | REST | ALLOWED | ALLOWED | PASS | b9f624ea-fc9c-4ffe-b849-3e03011fb32b | support-refund-up-to-2000 | held, approved (APPROVED) -> step PolicyStep, HTTP 201 |
| LEG-058 | legit-issueRefund | REST | ALLOWED | ALLOWED | PASS | 1e7055d1-ff6d-4070-b106-c2a39dfc7252 | support-refund-up-to-2000 | held, approved (APPROVED) -> step PolicyStep, HTTP 201 |
| LEG-059 | legit-getCustomer | REST | ALLOWED | ALLOWED | PASS | e9b9db19-e419-425c-ad97-6df32a6867e3 | support-own-customer-only | step PolicyStep, HTTP 200 |
| LEG-060 | legit-issueRefund | MCP | ALLOWED | ALLOWED | PASS | d2c460b6-becc-4a4d-95f4-3e3714fa0a16 | support-refund-up-to-2000 | held, approved (APPROVED) -> step PolicyStep, HTTP 201 |
| LEG-061 | legit-track_parcel | MCP | ALLOWED | ALLOWED | PASS | cab470df-1570-40d7-bfdb-bcbdfc13abd2 | support-track-parcel | step PolicyStep, HTTP 200 |
| LEG-062 | legit-getOrder | REST | ALLOWED | ALLOWED | PASS | 3c348b29-42f4-4b92-8393-331c5fbedeb4 | support-read-orders | step PolicyStep, HTTP 200 |
| LEG-063 | legit-getOrder | REST | ALLOWED | ALLOWED | PASS | 971c0c81-b1a0-4bdf-bd6f-6f35d5e14d49 | support-read-orders | step PolicyStep, HTTP 200 |
| LEG-064 | legit-getOrder | REST | ALLOWED | ALLOWED | PASS | 916f3fdd-a36f-4eb2-a08a-0a2ca38981ea | support-read-orders | step PolicyStep, HTTP 200 |
| LEG-065 | legit-issueRefund | REST | ALLOWED | ALLOWED | PASS | 931cb11d-eeef-49b8-bee6-76c8b4375f30 | support-refund-up-to-2000 | held, approved (APPROVED) -> step PolicyStep, HTTP 201 |
| LEG-066 | legit-track_parcel | MCP | ALLOWED | ALLOWED | PASS | 26aff361-8a0f-4113-9401-3937e6d9d6f4 | support-track-parcel | step PolicyStep, HTTP 200 |
| LEG-067 | legit-issueRefund | REST | ALLOWED | ALLOWED | PASS | 3c3fc038-8693-47f7-a9da-5d394525c0c8 | support-refund-up-to-2000 | held, approved (APPROVED) -> step PolicyStep, HTTP 201 |
| LEG-068 | legit-getOrder | REST | ALLOWED | ALLOWED | PASS | 07fd53c4-9af7-4d1b-9713-350b2b515af7 | support-read-orders | step PolicyStep, HTTP 200 |
| LEG-069 | legit-getCustomer | MCP | ALLOWED | ALLOWED | PASS | ed8f29c1-1861-415a-b3dd-e15d715516ff | support-own-customer-only | step PolicyStep, HTTP 200 |
| LEG-070 | legit-getOrder | REST | ALLOWED | ALLOWED | PASS | f0f6ada2-08d5-44e6-bf05-c7f00d32509f | support-read-orders | step PolicyStep, HTTP 200 |
| LEG-071 | legit-getCustomer | REST | ALLOWED | ALLOWED | PASS | ead43b88-be1e-4307-82b8-d672b30212ad | support-own-customer-only | step PolicyStep, HTTP 200 |
| LEG-072 | legit-getOrder | MCP | ALLOWED | ALLOWED | PASS | 70de2749-5022-4d33-8d0e-d2e8eb9a7df9 | support-read-orders | step PolicyStep, HTTP 200 |
| LEG-073 | legit-getOrder | REST | ALLOWED | ALLOWED | PASS | 478328ac-374e-4b4f-b4c7-ef8b1e234def | support-read-orders | step PolicyStep, HTTP 200 |
| LEG-074 | legit-issueRefund | REST | ALLOWED | ALLOWED | PASS | bfdd8f9f-bdc1-4c91-83c4-3621a91ae33d | support-refund-up-to-2000 | held, approved (APPROVED) -> step PolicyStep, HTTP 201 |
| LEG-075 | legit-getCustomer | REST | ALLOWED | ALLOWED | PASS | f2a94811-656f-42f1-b855-e99e1857190c | support-own-customer-only | step PolicyStep, HTTP 200 |
| LEG-076 | legit-issueRefund | REST | ALLOWED | ALLOWED | PASS | dfde3c5b-8fa7-4977-b4b7-ed902996c365 | support-refund-up-to-2000 | held, approved (APPROVED) -> step PolicyStep, HTTP 201 |
| LEG-077 | legit-getOrder | MCP | ALLOWED | ALLOWED | PASS | f12f1430-e924-4df3-9077-68d4dad6ff9e | support-read-orders | step PolicyStep, HTTP 200 |
| LEG-078 | legit-track_parcel | REST | ALLOWED | ALLOWED | PASS | 1b1f5f11-4663-477a-a8db-e93876315fba | support-track-parcel | step PolicyStep, HTTP 200 |
| LEG-079 | legit-issueRefund | MCP | ALLOWED | ALLOWED | PASS | 11b2d2c7-0e73-4c17-ac6d-3c05cb5225a5 | support-refund-up-to-2000 | held, approved (APPROVED) -> step PolicyStep, HTTP 201 |
| LEG-080 | legit-getOrder | REST | ALLOWED | ALLOWED | PASS | 9e6b2d00-7d2e-4182-8d23-dd201b7320ab | support-read-orders | step PolicyStep, HTTP 200 |
| LEG-081 | legit-getOrder | REST | ALLOWED | ALLOWED | PASS | c164b8dd-80fd-4748-bb6d-60007673a723 | support-read-orders | step PolicyStep, HTTP 200 |
| LEG-082 | legit-getCustomer | MCP | ALLOWED | ALLOWED | PASS | 5a1232a1-bbee-48da-ac21-733d1b398653 | support-own-customer-only | step PolicyStep, HTTP 200 |
| LEG-083 | legit-issueRefund | MCP | ALLOWED | ALLOWED | PASS | 0d226778-eae2-4297-ae8f-0f6c8beec589 | support-refund-up-to-2000 | held, approved (APPROVED) -> step PolicyStep, HTTP 201 |
| LEG-084 | legit-track_parcel | MCP | ALLOWED | ALLOWED | PASS | 53978f33-f6a4-4cb0-9947-b10d7112b622 | support-track-parcel | step PolicyStep, HTTP 200 |
| LEG-085 | legit-getCustomer | MCP | ALLOWED | ALLOWED | PASS | 42a2e945-6b0e-47fc-9b30-de24ab54870f | support-own-customer-only | step PolicyStep, HTTP 200 |
| LEG-086 | legit-issueRefund | REST | ALLOWED | ALLOWED | PASS | dca72896-3772-4fb1-a06e-2202833bcb58 | support-refund-up-to-2000 | held, approved (APPROVED) -> step PolicyStep, HTTP 201 |
| LEG-087 | legit-getOrder | REST | ALLOWED | ALLOWED | PASS | 98d35168-aece-46da-927c-6c6c5c853d67 | support-read-orders | step PolicyStep, HTTP 200 |
| LEG-088 | legit-getOrder | MCP | ALLOWED | ALLOWED | PASS | c1d6cd98-bcb9-4813-9d47-09e0d0445d25 | support-read-orders | step PolicyStep, HTTP 200 |
| LEG-089 | legit-getCustomer | REST | ALLOWED | ALLOWED | PASS | c69a1f61-a86d-4d84-a3d7-93929c4cfff3 | support-own-customer-only | step PolicyStep, HTTP 200 |
| LEG-090 | legit-issueRefund | REST | ALLOWED | ALLOWED | PASS | 0b21f8e3-88d8-40c4-88f9-aac830e86fd2 | support-refund-up-to-2000 | held, approved (APPROVED) -> step PolicyStep, HTTP 201 |
| LEG-091 | legit-issueRefund | REST | ALLOWED | ALLOWED | PASS | a0b6b451-d335-4d3c-9ce7-8d107803c557 | support-refund-up-to-2000 | held, approved (APPROVED) -> step PolicyStep, HTTP 201 |
| LEG-092 | legit-getCustomer | REST | ALLOWED | ALLOWED | PASS | 8c25ac19-526e-43c9-8d5a-da4c4e114b7a | support-own-customer-only | step PolicyStep, HTTP 200 |
| LEG-093 | legit-getCustomer | REST | ALLOWED | ALLOWED | PASS | c7458d9c-2d82-4941-aeb5-4098daf8bbc8 | support-own-customer-only | step PolicyStep, HTTP 200 |
| LEG-094 | legit-track_parcel | REST | ALLOWED | ALLOWED | PASS | d892f4dc-ecc2-4796-a226-3940d6460dd1 | support-track-parcel | step PolicyStep, HTTP 200 |
| LEG-095 | legit-issueRefund | MCP | ALLOWED | ALLOWED | PASS | 7ae4914a-18c7-4f0a-aba0-709ac7b898aa | support-refund-up-to-2000 | held, approved (APPROVED) -> step PolicyStep, HTTP 201 |
| LEG-096 | legit-getOrder | REST | ALLOWED | ALLOWED | PASS | bf32205c-413a-4187-aa93-801de4bda24b | support-read-orders | step PolicyStep, HTTP 200 |
| LEG-097 | legit-issueRefund | REST | ALLOWED | ALLOWED | PASS | 027912e0-52be-401b-8ba4-487c68f01e8d | support-refund-up-to-2000 | held, approved (APPROVED) -> step PolicyStep, HTTP 201 |
| LEG-098 | legit-issueRefund | MCP | ALLOWED | ALLOWED | PASS | 2ecf00cf-972b-436c-921b-336b9c0989ca | support-refund-up-to-2000 | held, approved (APPROVED) -> step PolicyStep, HTTP 201 |
| LEG-099 | legit-getCustomer | REST | ALLOWED | ALLOWED | PASS | c2096ffd-1379-4c61-8d14-23363f8b3072 | support-own-customer-only | step PolicyStep, HTTP 200 |
| LEG-100 | legit-getCustomer | REST | ALLOWED | ALLOWED | PASS | cee1a47d-38bd-45ec-a3cb-94ca4c39119b | support-own-customer-only | step PolicyStep, HTTP 200 |
| LEG-101 | legit-getOrder | REST | ALLOWED | ALLOWED | PASS | 4b8dde6e-1701-4064-960d-292fa042aa2e | support-read-orders | step PolicyStep, HTTP 200 |
| LEG-102 | legit-getOrder | MCP | ALLOWED | ALLOWED | PASS | 34e19e2a-500d-4c80-b514-4941f9199386 | support-read-orders | step PolicyStep, HTTP 200 |
| LEG-103 | legit-getOrder | MCP | ALLOWED | ALLOWED | PASS | 6e59ba3a-59ca-46e9-97e6-7d60bb0d8652 | support-read-orders | step PolicyStep, HTTP 200 |
| LEG-104 | legit-issueRefund | REST | ALLOWED | ALLOWED | PASS | 40c0824c-1777-49db-8a1a-7207536ae562 | support-refund-up-to-2000 | held, approved (APPROVED) -> step PolicyStep, HTTP 201 |
| LEG-105 | legit-getCustomer | REST | ALLOWED | ALLOWED | PASS | f2f2d16f-10c3-48fe-8d17-ef70d6c06d1b | support-own-customer-only | step PolicyStep, HTTP 200 |
| LEG-106 | legit-issueRefund | MCP | ALLOWED | ALLOWED | PASS | 704e5eac-0ee7-44f7-9b7d-ac20cd9751de | support-refund-up-to-2000 | held, approved (APPROVED) -> step PolicyStep, HTTP 201 |
| LEG-107 | legit-getOrder | MCP | ALLOWED | ALLOWED | PASS | 7d79bbe9-0e8f-4b69-925e-96d4080a4254 | support-read-orders | step PolicyStep, HTTP 200 |
| LEG-108 | legit-issueRefund | REST | ALLOWED | ALLOWED | PASS | 3eade58c-4bf2-4f8f-9bd3-aa74da84fcd9 | support-refund-up-to-2000 | held, approved (APPROVED) -> step PolicyStep, HTTP 201 |
| LEG-109 | legit-getOrder | MCP | ALLOWED | ALLOWED | PASS | 0b1d5fba-f68e-406f-8eb1-b8f31293ed9c | support-read-orders | step PolicyStep, HTTP 200 |
| LEG-110 | legit-issueRefund | REST | ALLOWED | ALLOWED | PASS | 7594fb80-6375-4347-98c8-dc42cb0a30ce | support-refund-up-to-2000 | held, approved (APPROVED) -> step PolicyStep, HTTP 201 |
| LEG-111 | legit-getOrder | REST | ALLOWED | ALLOWED | PASS | c37beb4d-8b5c-4aec-a9b9-2f181e24b7ce | support-read-orders | step PolicyStep, HTTP 200 |
| LEG-112 | legit-getOrder | REST | ALLOWED | ALLOWED | PASS | 02ecb5b6-1644-4b67-bd13-d1cb9c898393 | support-read-orders | step PolicyStep, HTTP 200 |
| LEG-113 | legit-getOrder | REST | ALLOWED | ALLOWED | PASS | 4c99d17a-fb8c-423f-9e96-f1412d80984e | support-read-orders | step PolicyStep, HTTP 200 |
| LEG-114 | legit-getOrder | MCP | ALLOWED | ALLOWED | PASS | 97f8c28a-252f-482d-bc9f-06c56b364c03 | support-read-orders | step PolicyStep, HTTP 200 |
| LEG-115 | legit-track_parcel | MCP | ALLOWED | ALLOWED | PASS | 976cfbd0-9338-4779-9c18-02f31e9c5921 | support-track-parcel | step PolicyStep, HTTP 200 |
| LEG-116 | legit-getCustomer | REST | ALLOWED | ALLOWED | PASS | f39e1642-2e53-44ba-a1f3-abb9c6fd9e42 | support-own-customer-only | step PolicyStep, HTTP 200 |
| LEG-117 | legit-getCustomer | REST | ALLOWED | ALLOWED | PASS | 5a858c31-cc45-4813-8397-387fd2e025fa | support-own-customer-only | step PolicyStep, HTTP 200 |
| LEG-118 | legit-track_parcel | REST | ALLOWED | ALLOWED | PASS | fb99a1eb-8b65-4ca6-9a3f-8ee60e87068b | support-track-parcel | step PolicyStep, HTTP 200 |
| LEG-119 | legit-getCustomer | REST | ALLOWED | ALLOWED | PASS | 3ebc3a35-381a-4dad-a95a-21e2fe06de1f | support-own-customer-only | step PolicyStep, HTTP 200 |
| LEG-120 | legit-issueRefund | REST | ALLOWED | ALLOWED | PASS | d1a81c51-19bf-4909-be0e-68219f686fb5 | support-refund-up-to-2000 | held, approved (APPROVED) -> step PolicyStep, HTTP 201 |
| LEG-121 | legit-track_parcel | REST | ALLOWED | ALLOWED | PASS | 1535166c-ee07-4dae-afe8-dd53bef89cd5 | support-track-parcel | step PolicyStep, HTTP 200 |
| LEG-122 | legit-issueRefund | MCP | ALLOWED | ALLOWED | PASS | 471a1cc9-4122-4f6d-b0a4-84ee3099fc05 | support-refund-up-to-2000 | held, approved (APPROVED) -> step PolicyStep, HTTP 201 |
| LEG-123 | legit-issueRefund | MCP | ALLOWED | ALLOWED | PASS | 4d295a2f-52f9-4752-a2f4-9bea31f88493 | support-refund-up-to-2000 | held, approved (APPROVED) -> step PolicyStep, HTTP 201 |
| LEG-124 | legit-getCustomer | MCP | ALLOWED | ALLOWED | PASS | 1ad1a278-5ef8-47ac-a04b-fcc89d0f343c | support-own-customer-only | step PolicyStep, HTTP 200 |
| LEG-125 | legit-getOrder | REST | ALLOWED | ALLOWED | PASS | 66754a6d-1fc1-4172-94e5-fd773f70fb20 | support-read-orders | step PolicyStep, HTTP 200 |
| LEG-126 | legit-issueRefund | MCP | ALLOWED | ALLOWED | PASS | 42f0016e-27a9-4583-a308-9d8ad762bbdf | support-refund-up-to-2000 | held, approved (APPROVED) -> step PolicyStep, HTTP 201 |
| LEG-127 | legit-getCustomer | REST | ALLOWED | ALLOWED | PASS | 26f291cc-9297-4d59-810d-ebb29d889a34 | support-own-customer-only | step PolicyStep, HTTP 200 |
| LEG-128 | legit-track_parcel | REST | ALLOWED | ALLOWED | PASS | 58ea60d0-935c-4989-8b39-e11a62c5c4fb | support-track-parcel | step PolicyStep, HTTP 200 |
| LEG-129 | legit-getOrder | REST | ALLOWED | ALLOWED | PASS | b8572697-7c3a-4b15-8afe-cf54a65af934 | support-read-orders | step PolicyStep, HTTP 200 |
| LEG-130 | legit-getOrder | REST | ALLOWED | ALLOWED | PASS | 0e2787a1-910e-4814-99a6-514354fa993d | support-read-orders | step PolicyStep, HTTP 200 |
| LEG-131 | legit-getCustomer | REST | ALLOWED | ALLOWED | PASS | 62b2f3f9-2367-4ef2-a89b-073792b9b2d5 | support-own-customer-only | step PolicyStep, HTTP 200 |
| LEG-132 | legit-issueRefund | REST | ALLOWED | ALLOWED | PASS | d1a10eb1-cead-4111-a691-63f7db6d62e2 | support-refund-up-to-2000 | held, approved (APPROVED) -> step PolicyStep, HTTP 201 |
| LEG-133 | legit-getOrder | REST | ALLOWED | ALLOWED | PASS | 0f023d19-e1cc-49be-86be-83667598599b | support-read-orders | step PolicyStep, HTTP 200 |
| LEG-134 | legit-getCustomer | MCP | ALLOWED | ALLOWED | PASS | 736f1ac9-043e-4335-95ea-0c0b9b956195 | support-own-customer-only | step PolicyStep, HTTP 200 |
| LEG-135 | legit-getOrder | REST | ALLOWED | ALLOWED | PASS | 1ba19eef-a52b-4e3b-af19-ad2915e96493 | support-read-orders | step PolicyStep, HTTP 200 |
| LEG-136 | legit-getOrder | REST | ALLOWED | ALLOWED | PASS | 49bdd8bf-7d2a-46fc-b344-e8ed5e1e3429 | support-read-orders | step PolicyStep, HTTP 200 |
| LEG-137 | legit-issueRefund | REST | ALLOWED | ALLOWED | PASS | 85aa5d3a-4598-4667-aec6-955d6406fea1 | support-refund-up-to-2000 | held, approved (APPROVED) -> step PolicyStep, HTTP 201 |
| LEG-138 | legit-getOrder | REST | ALLOWED | ALLOWED | PASS | 4ac3bb4c-a12b-4dc4-995f-3828bcae80ba | support-read-orders | step PolicyStep, HTTP 200 |
| LEG-139 | legit-getCustomer | REST | ALLOWED | ALLOWED | PASS | 7696f6aa-e244-4f93-a240-78cb39a725bc | support-own-customer-only | step PolicyStep, HTTP 200 |
| LEG-140 | legit-getOrder | REST | ALLOWED | ALLOWED | PASS | 747f8003-7fa1-408c-98ae-4d94e0f7068c | support-read-orders | step PolicyStep, HTTP 200 |
| LEG-141 | legit-track_parcel | REST | ALLOWED | ALLOWED | PASS | 807aa625-8083-4d3e-8590-94d519f4c88b | support-track-parcel | step PolicyStep, HTTP 200 |
| LEG-142 | legit-getOrder | MCP | ALLOWED | ALLOWED | PASS | 9ccb824c-ef69-42ae-88aa-bc921439aa9e | support-read-orders | step PolicyStep, HTTP 200 |
| LEG-143 | legit-getCustomer | REST | ALLOWED | ALLOWED | PASS | a7caa516-b587-4eca-8d3a-95af19ff19c8 | support-own-customer-only | step PolicyStep, HTTP 200 |
| LEG-144 | legit-getOrder | MCP | ALLOWED | ALLOWED | PASS | d2b57db8-3e70-4e8c-b3cc-50e715058a2e | support-read-orders | step PolicyStep, HTTP 200 |
| LEG-145 | legit-track_parcel | MCP | ALLOWED | ALLOWED | PASS | 0e18b51c-b0a2-42ba-a179-17b9092a6ddf | support-track-parcel | step PolicyStep, HTTP 200 |
| LEG-146 | legit-issueRefund | REST | ALLOWED | ALLOWED | PASS | 363934bd-b66a-4915-9fd8-0cad55e29bbe | support-refund-up-to-2000 | held, approved (APPROVED) -> step PolicyStep, HTTP 201 |
| LEG-147 | legit-issueRefund | MCP | ALLOWED | ALLOWED | PASS | d679e3e0-9d55-4723-95c3-3164daf12fbb | support-refund-up-to-2000 | held, approved (APPROVED) -> step PolicyStep, HTTP 201 |
| LEG-148 | legit-getCustomer | MCP | ALLOWED | ALLOWED | PASS | ff83e892-0b4d-443a-8a97-d5b67bbb5581 | support-own-customer-only | step PolicyStep, HTTP 200 |
| LEG-149 | legit-issueRefund | MCP | ALLOWED | ALLOWED | PASS | 4d989618-e411-4852-9fd7-a82a7a41fe80 | support-refund-up-to-2000 | held, approved (APPROVED) -> step PolicyStep, HTTP 201 |
| LEG-150 | legit-getCustomer | REST | ALLOWED | ALLOWED | PASS | 90ada0dd-f4b7-4640-9ba4-c9b53d5170e9 | support-own-customer-only | step PolicyStep, HTTP 200 |
| LEG-151 | legit-issueRefund | REST | ALLOWED | ALLOWED | PASS | dffcc749-924e-4191-a90f-59be0a462076 | support-refund-up-to-2000 | held, approved (APPROVED) -> step PolicyStep, HTTP 201 |
| LEG-152 | legit-getOrder | REST | ALLOWED | ALLOWED | PASS | 928dce23-b621-4f4c-a8a6-7c5877c44198 | support-read-orders | step PolicyStep, HTTP 200 |
| LEG-153 | legit-issueRefund | REST | ALLOWED | ALLOWED | PASS | ea1d9876-1ace-4513-824b-99c4a407901b | support-refund-up-to-2000 | held, approved (APPROVED) -> step PolicyStep, HTTP 201 |
| LEG-154 | legit-issueRefund | REST | ALLOWED | ALLOWED | PASS | 6d865730-a714-4e97-a677-91bd7c74c12b | support-refund-up-to-2000 | held, approved (APPROVED) -> step PolicyStep, HTTP 201 |
| LEG-155 | legit-issueRefund | REST | ALLOWED | ALLOWED | PASS | 136d2217-6f25-45bf-ac44-5b69379994f2 | support-refund-up-to-2000 | held, approved (APPROVED) -> step PolicyStep, HTTP 201 |
| LEG-156 | legit-getCustomer | MCP | ALLOWED | ALLOWED | PASS | bce8127b-25bc-4338-9a9b-4eec99ee17c4 | support-own-customer-only | step PolicyStep, HTTP 200 |
| LEG-157 | legit-getCustomer | REST | ALLOWED | ALLOWED | PASS | a8af9404-a99f-4f0d-9da2-07b0387fb3ac | support-own-customer-only | step PolicyStep, HTTP 200 |
| LEG-158 | legit-track_parcel | MCP | ALLOWED | ALLOWED | PASS | cc131552-a045-4329-9c10-6e8b30ca1a3a | support-track-parcel | step PolicyStep, HTTP 200 |
| LEG-159 | legit-track_parcel | REST | ALLOWED | ALLOWED | PASS | 4da021ff-0685-4291-a347-6b42ecdf1772 | support-track-parcel | step PolicyStep, HTTP 200 |
| LEG-160 | legit-issueRefund | REST | ALLOWED | ALLOWED | PASS | 966d09bc-d054-40e6-8a70-a8e3d0f86837 | support-refund-up-to-2000 | held, approved (APPROVED) -> step PolicyStep, HTTP 201 |
| LEG-161 | legit-getCustomer | REST | ALLOWED | ALLOWED | PASS | 21f9c378-84b6-4e24-afd8-a75b19361d07 | analytics-read-only | step PolicyStep, HTTP 200 |
| LEG-162 | legit-getOrder | MCP | ALLOWED | ALLOWED | PASS | 8fd6c171-4fec-47f3-9d5f-3c6abba8ec01 | analytics-read-only | step PolicyStep, HTTP 200 |
| LEG-163 | legit-getCustomer | REST | ALLOWED | ALLOWED | PASS | dac81bdc-9bc4-4082-a5a5-bb440afc584b | analytics-read-only | step PolicyStep, HTTP 200 |
| LEG-164 | legit-getCustomer | MCP | ALLOWED | ALLOWED | PASS | 2e3e9ee9-720d-4141-84f3-02dbf55eec84 | analytics-read-only | step PolicyStep, HTTP 200 |
| LEG-165 | legit-getOrder | REST | ALLOWED | ALLOWED | PASS | 01de121a-bee1-4f75-b6da-21aded2d07a8 | analytics-read-only | step PolicyStep, HTTP 200 |
| LEG-166 | legit-getOrder | REST | ALLOWED | ALLOWED | PASS | bb0a5fd1-b805-49cc-898a-336fbcd861b8 | analytics-read-only | step PolicyStep, HTTP 200 |
| LEG-167 | legit-getOrder | REST | ALLOWED | ALLOWED | PASS | a09eecb8-f24a-4be7-89e2-e3304a8dc11d | analytics-read-only | step PolicyStep, HTTP 200 |
| LEG-168 | legit-getOrder | REST | ALLOWED | ALLOWED | PASS | cd6fe11e-816a-4261-9bd0-b9f5eafd30d5 | analytics-read-only | step PolicyStep, HTTP 200 |
| LEG-169 | legit-getOrder | MCP | ALLOWED | ALLOWED | PASS | 0db3f340-bc03-4173-8f49-72d9d8ba12dd | analytics-read-only | step PolicyStep, HTTP 200 |
| LEG-170 | legit-getOrder | REST | ALLOWED | ALLOWED | PASS | 13acbc39-38ba-4aad-bd0f-b3701e172fd7 | analytics-read-only | step PolicyStep, HTTP 200 |
| LEG-171 | legit-getOrder | REST | ALLOWED | ALLOWED | PASS | 716e61ca-6628-4709-b59d-0fefdea7b652 | analytics-read-only | step PolicyStep, HTTP 200 |
| LEG-172 | legit-getCustomer | REST | ALLOWED | ALLOWED | PASS | 0eb00096-c22b-4609-8221-54084d9e9038 | analytics-read-only | step PolicyStep, HTTP 200 |
| LEG-173 | legit-getCustomer | REST | ALLOWED | ALLOWED | PASS | fd3bbf8f-c15e-418c-9509-71e086c83628 | analytics-read-only | step PolicyStep, HTTP 200 |
| LEG-174 | legit-getCustomer | MCP | ALLOWED | ALLOWED | PASS | 7472b6b8-27a7-4a7c-be6a-eecfeb03e5bd | analytics-read-only | step PolicyStep, HTTP 200 |
| LEG-175 | legit-getOrder | REST | ALLOWED | ALLOWED | PASS | ab8ce58c-3f65-440a-bee4-decb77b8cfdb | analytics-read-only | step PolicyStep, HTTP 200 |
| LEG-176 | legit-getOrder | MCP | ALLOWED | ALLOWED | PASS | 3a258467-6a09-4f50-adcf-4cea644e5cd6 | analytics-read-only | step PolicyStep, HTTP 200 |
| LEG-177 | legit-getOrder | MCP | ALLOWED | ALLOWED | PASS | 6f105c21-0b40-4dc1-bd67-17cec5a63827 | analytics-read-only | step PolicyStep, HTTP 200 |
| LEG-178 | legit-getOrder | REST | ALLOWED | ALLOWED | PASS | 77cb0cd2-eeb0-4c39-828d-aeb249416ae8 | analytics-read-only | step PolicyStep, HTTP 200 |
| LEG-179 | legit-getCustomer | MCP | ALLOWED | ALLOWED | PASS | e0334d13-f382-4bd4-b189-77f3092f45cb | analytics-read-only | step PolicyStep, HTTP 200 |
| LEG-180 | legit-getOrder | MCP | ALLOWED | ALLOWED | PASS | f723a8e8-93a1-4168-b274-4733468b08bb | analytics-read-only | step PolicyStep, HTTP 200 |
| LEG-181 | legit-getCustomer | MCP | ALLOWED | ALLOWED | PASS | 96f189d2-e181-48ac-9cb6-8cdcc4747dd1 | analytics-read-only | step PolicyStep, HTTP 200 |
| LEG-182 | legit-getCustomer | MCP | ALLOWED | ALLOWED | PASS | 24080bc1-98b8-4094-bdf4-13108ed600b3 | analytics-read-only | step PolicyStep, HTTP 200 |
| LEG-183 | legit-getOrder | MCP | ALLOWED | ALLOWED | PASS | 248109fd-56a7-45db-831c-4aac2b8eaa37 | analytics-read-only | step PolicyStep, HTTP 200 |
| LEG-184 | legit-getOrder | REST | ALLOWED | ALLOWED | PASS | 95974d40-8e2f-4a06-8bbd-98bf73c69e09 | analytics-read-only | step PolicyStep, HTTP 200 |
| LEG-185 | legit-getOrder | REST | ALLOWED | ALLOWED | PASS | d39d33e1-83bd-4d4b-a09b-d6abea1333f3 | analytics-read-only | step PolicyStep, HTTP 200 |
| LEG-186 | legit-getOrder | MCP | ALLOWED | ALLOWED | PASS | f2b7fe27-45cb-42d1-a512-6045b0621354 | analytics-read-only | step PolicyStep, HTTP 200 |
| LEG-187 | legit-getCustomer | REST | ALLOWED | ALLOWED | PASS | fde04deb-95dd-469e-9cd0-caec0a42ca6f | analytics-read-only | step PolicyStep, HTTP 200 |
| LEG-188 | legit-getOrder | MCP | ALLOWED | ALLOWED | PASS | 9ea479ca-14ce-4c53-a422-b39f1b482f5c | analytics-read-only | step PolicyStep, HTTP 200 |
| LEG-189 | legit-getCustomer | MCP | ALLOWED | ALLOWED | PASS | 8716548e-cf39-45e8-88af-74e12d4381cd | analytics-read-only | step PolicyStep, HTTP 200 |
| LEG-190 | legit-getOrder | MCP | ALLOWED | ALLOWED | PASS | f9771b26-eca7-4c74-b7c9-6975c53c3294 | analytics-read-only | step PolicyStep, HTTP 200 |
| LEG-191 | legit-getOrder | REST | ALLOWED | ALLOWED | PASS | 1d8ebce0-e2a9-495a-a95c-94adb9c7be75 | analytics-read-only | step PolicyStep, HTTP 200 |
| LEG-192 | legit-getOrder | REST | ALLOWED | ALLOWED | PASS | 6733a47e-32d0-4090-b551-c898c5941544 | analytics-read-only | step PolicyStep, HTTP 200 |
| LEG-193 | legit-getCustomer | REST | ALLOWED | ALLOWED | PASS | 1ea67b2e-c8ba-4fe9-8b4c-45dc9269c370 | analytics-read-only | step PolicyStep, HTTP 200 |
| LEG-194 | legit-getCustomer | REST | ALLOWED | ALLOWED | PASS | 1f1bf998-ec35-420f-b3b9-4f10aef4cc66 | analytics-read-only | step PolicyStep, HTTP 200 |
| LEG-195 | legit-getOrder | REST | ALLOWED | ALLOWED | PASS | cf70a5d1-bf2d-4050-b295-a703b4cf375e | analytics-read-only | step PolicyStep, HTTP 200 |
| LEG-196 | legit-getOrder | REST | ALLOWED | ALLOWED | PASS | ffec554f-3f1e-433c-8fe3-b56163e4e81b | analytics-read-only | step PolicyStep, HTTP 200 |
| LEG-197 | legit-getCustomer | MCP | ALLOWED | ALLOWED | PASS | b36eed0d-6ec0-4d0a-8906-0d66f4e28c3f | analytics-read-only | step PolicyStep, HTTP 200 |
| LEG-198 | legit-getCustomer | REST | ALLOWED | ALLOWED | PASS | 2738dba4-1281-40c3-9a38-dbf15112ee93 | analytics-read-only | step PolicyStep, HTTP 200 |
| LEG-199 | legit-getOrder | REST | ALLOWED | ALLOWED | PASS | ee39c815-9b75-4475-a8fd-8146ed6fb092 | analytics-read-only | step PolicyStep, HTTP 200 |
| LEG-200 | legit-getOrder | MCP | ALLOWED | ALLOWED | PASS | 26bce0ae-4f41-44b2-9ade-96c0d9edcb99 | analytics-read-only | step PolicyStep, HTTP 200 |

