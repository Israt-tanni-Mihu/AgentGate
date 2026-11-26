# ADR-0003: Default deny, and validate arguments against the schema before the policy runs

- **Status:** Accepted (2026-11, Phases 2–3)
- **Linked:** FR3 (default deny, argument conditions), FR5 (schema validation), FR4 (edited arguments), QA1 (red team), QA5 (new tool needs only data)

## Context

Policies compare argument values: `amount ≤ 2000`, `id` matches the end user. An attacker controls the arguments. If the policy sees `"amount": "1500abc"`, `{"amount": {"$lt": 1}}`, a unicode digit or an extra field the tool would accept, each operator would have to defend against every trick. And a tool nobody wrote a rule for must not be callable.

## Decision

1. **Default deny** (`policy.PolicyEngine`): a call is allowed only if some ALLOW rule matches and no DENY rule matches (deny beats allow). No active policy (`PolicySnapshot.NONE`) = everything denied. The deny answer names the rule `default-deny` and why each candidate rule did not apply.
2. **Schema before policy** (`CallStepOrder.SCHEMA = 200` < `POLICY = 300`): `guard.SchemaValidationStep` checks the arguments against the tool's JSON Schema (draft 2020-12) and **rejects any field not declared under `properties`**, even if the schema forgot `additionalProperties: false`. The policy therefore only sees well-typed, known arguments.
3. The same order is reused for an approver's edited arguments: `ApprovalStep.proceedWithEdits` runs `schemaStep.handle(...)` → `policyStep.handle(...)`.

Steps before the schema (kill switch 100, tool availability 120, limits 150) do not read argument values, so they are safe on raw input.

## Alternatives considered

| Option | Why not |
|---|---|
| **Default allow + deny list** | Every new tool (QA5 import) would be open until someone remembers a deny rule. One forgotten rule = breach. |
| **Policy first, schema after** | Operators must handle wrong types themselves; a policy could "allow" on a misread value. Also denial messages would describe garbage input. |
| **Schema check only inside the downstream tool** | The tool sees smuggled input first; and MCP/REST tools differ in how strict they are. |

## Consequences

**Good**
- Operators (`ConditionOperator` strategies) stay small: they can trust types.
- Malformed calls are cheap 400s with a list of problems and never reach policy, a human approver, or the tool.
- Bad arguments do not count as tool failures, so they cannot trip a circuit breaker (`fr10-health.md` note).

**Bad**
- Every new tool needs a policy rule before anyone can use it (one extra step in QA5).
- A wrong or too-strict imported schema blocks legitimate calls (risk for QA1-b; measured 0%).
- Schema validation runs on every call, even ones the policy would deny (small CPU cost; schemas are compiled once and cached).

## Evidence

- `docs/evidence/qa1-redteam.md`: MAL-01, 02, 03, 06, 09 stopped by `PolicyStep` with `default-deny`; MAL-16 to 24 (argument smuggling) stopped by `SchemaValidationStep`; **0 / 200** legitimate calls wrongly blocked.
- `docs/evidence/mcp-endpoint.md`: wrong argument type → 400 "do not match the schema" over MCP.
- Tests: `PolicyEngineTest`, `GuardPipelineIntegrationTest`.
