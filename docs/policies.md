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
