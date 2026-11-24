# Policies (FR3)

Every tool call is checked against the **active policy version**. If no rule allows a call, it is **denied by default**.

## Rule format

A policy version is a list of rules (JSON). Example:

```json
{
  "id": "support-refund-up-to-2000",
  "description": "Support bot may refund at most 2000 BDT",
  "effect": "ALLOW",
  "agents": ["support-bot"],
  "tools": ["issueRefund"],
  "conditions": [ { "argument": "amount", "operator": "lessThanOrEqual", "value": 2000 } ]
}
```

- `agents`, `tools`: lists of names; `"*"` means any.
- `conditions`: all must be true (AND). `argument` is a top-level argument name of the tool call.
- `effect`: `ALLOW` or `DENY`.

### Operators

| Operator | Meaning | `value` |
|---|---|---|
| `equals` | argument == value (numbers by value: `1`, `1.0`, `"1"` are equal) | simple value |
| `notEquals` | argument != value; a **missing** argument gives false | simple value |
| `lessThanOrEqual` | argument ≤ value (numbers; numeric strings like `"5000"` count as numbers) | number |
| `greaterThanOrEqual` | argument ≥ value | number |
| `in` | argument is one of the list | non-empty list |
| `matchesEndUser` | argument == the end user in `X-On-Behalf-Of`; no header → false | none |

A missing argument or a wrong type makes a condition **false**, so an ALLOW rule does not apply (fail closed).

## How a decision is made

1. Only rules whose `agents` and `tools` match the call are looked at.
2. If a matching rule with all conditions true is `DENY` → **denied by that rule** (deny beats allow, order does not matter).
3. Otherwise, the first such `ALLOW` rule → **allowed by that rule**.
4. Otherwise → **denied by `default-deny`**. The reason lists the ALLOW rules that almost applied and which condition failed,
   e.g. `rule 'support-refund-up-to-2000' did not apply because amount lessThanOrEqual 2000 is false (value: 5000)`.

A denied call returns `403` Problem Details with `rule`, `policyVersion` and the reason in `detail`.

## Versions, activation, dry-run

- `POST /api/admin/policies` creates a new **DRAFT** version with a complete rule set. Rules of a version never change.
- `POST /api/admin/policies/{version}/activate` makes it **ACTIVE**; the previous active one becomes **RETIRED**.
  Activating a RETIRED version is a rollback.
- Takes effect **without restart**: the in-memory cache is refreshed right after activation, and every 5 s
  (`agentgate.policy.refresh-interval`) to pick up changes made by another instance. If the database is unreachable,
  the last known policy is kept.
- `PUT /api/admin/policies/{version}/dry-run` with `{"enabled": true}`: calls the version would deny are **logged**
  (`DRY-RUN would DENY ...`) and let through. Use it to try a stricter policy safely.

## Demo policies (local profile, version 1)

**Convention:** for ShopBD, the `X-On-Behalf-Of` header carries the **customer id** as a plain number,
e.g. `X-On-Behalf-Of: 1` = customer 1 (Rahim Uddin).

| Rule id | Effect | Agent | Tools | Condition |
|---|---|---|---|---|
| `support-own-customer-only` | ALLOW | support-bot | getCustomer | `id` matchesEndUser |
| `support-read-orders` | ALLOW | support-bot | getOrder | – |
| `support-refund-up-to-2000` | ALLOW | support-bot | issueRefund | `amount` ≤ 2000 |
| `analytics-read-only` | ALLOW | analytics-bot | getOrder, getCustomer | – |
| *(none)* | – | – | sendSms | falls to **default-deny** |

**Known limitation:** "support-bot may read only its customer's **own** orders" cannot be decided from the arguments:
`getOrder` takes only an order id, and which customer owns the order is only known from the **result**.
This needs a result check, which comes with the guards (FR5). Until then `support-read-orders` allows any order id.

## Policy playground (FR9)

`POST /api/admin/policies/playground` (admin login) answers "what would the gateway decide for this call?"
**without** calling the tool and **without** changing anything.

```json
{
  "agentId": "support-bot",
  "toolName": "issueRefund",
  "arguments": { "orderId": 1001, "amount": 5000, "reason": "damaged" },
  "endUser": "1",
  "policyVersion": null
}
```

- `policyVersion` empty → the **ACTIVE** version (the same in-memory copy live calls use). A number → that version,
  e.g. a **DRAFT** you want to try before activating it. Testing a draft never activates it.
- The answer has `decision` (ALLOW/DENY), `ruleId` (or `default-deny`), `reason`, `policyVersion`, `policyStatus`,
  `dryRun`, a `note`, and `checks`: every rule in order with its result —
  `NOT_FOR_THIS_CALL` (other agent/tool), `CONDITION_FAILED` (with the failing condition), `MATCHED`,
  or `NOT_CHECKED` (a DENY rule had already decided).
- It calls the same `PolicyEngine.explain(...)` method that the live `PolicyStep` uses (`evaluate()` is just
  `explain().decision()`), so the playground and real traffic cannot disagree.
- Unknown agent / tool / version → `404` Problem Details (`Agent not found`, `Unknown tool`, `Policy version not found`).
