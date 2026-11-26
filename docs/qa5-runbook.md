# QA5 runbook — put a new REST tool behind AgentGate

**Goal:** the ShopBD logistics team has a new REST API, the *Shipping API*. Make its operation
`getShippingRate` available to the AI agent **support-bot** through the gateway, safely.
You only use the admin web UI (Swagger). No Java, no IDE, no code changes.

**The rule you must enforce:** support-bot may get shipping rates **only for parcels up to 20 kg**
(`weightKg` ≤ 20). Heavier parcels must be denied. The tool only reads data, so its risk level is **LOW**.

The observer has already started the system and gives you the admin username and password.
Tell the observer **"start"** when you begin step 1 and **"done"** after step 7.

---

### 1. Open the admin UI and log in
Open <http://localhost:8080/swagger-ui.html>. Click **Authorize** (top right), enter the admin username and
password under `adminBasic`, click **Authorize**, then **Close**.

**You see:** a page of API groups, e.g. *Tool registry (admin)*, *Policies (admin)*.

> How to call an endpoint in Swagger: click the endpoint → **Try it out** → fill in the fields/body → **Execute**.
> The answer is under **Server response**. A request body box starts with an example: replace it completely.

### 2. Look at the new API (optional, 1 minute)
Open <http://localhost:8081/openapi/shipping-rate.json> in a new tab.

**You see:** an OpenAPI file with one operation, `getShippingRate`, with arguments `weightKg` (number) and `zone`.

### 3. Import the tool
Section **Tool registry (admin)** → `POST /api/admin/tools/import`. Body:

```json
{
  "specUrl": "http://mock-tools:8081/openapi/shipping-rate.json",
  "ownerTeam": "logistics-team",
  "baseUrl": "http://mock-tools:8081"
}
```

Use `mock-tools`, **not** `localhost`, in both URLs: the gateway runs inside Docker and reaches the
Shipping API by its container name.

**You see:** `200` with `"created": 1` and an item `{"name": "getShippingRate", "outcome": "CREATED"}`.

### 4. Set the risk level to LOW
The import guessed risk level **MEDIUM** (the spec has no risk hint). Change it:

1. `GET /api/admin/tools` → Execute. In the answer find `getShippingRate` and note its `"id"`.
2. `GET /api/admin/tools/{id}` with that id → Execute → **copy the whole response body**.
3. `PUT /api/admin/tools/{id}` with the same id. Paste the copied body and change only
   `"riskLevel": "MEDIUM"` to `"riskLevel": "LOW"` → Execute.

**You see:** `200` and `"riskLevel": "LOW"` in the answer.

### 5. Write a new policy version
Policies are **versioned**: a new version is a **complete** rule set that replaces the old one when activated.
So you copy the current rules and add one.

1. `GET /api/admin/policies` → Execute. Note the `version` whose `status` is `ACTIVE` (on a fresh start: `1`).
2. `GET /api/admin/policies/{version}` with that number → **copy the whole response body**.
3. `POST /api/admin/policies` → Try it out → **delete the example body**, paste the copied body.
   Extra fields such as `version` or `status` are ignored, so you can leave them.
   Change `"description"` to say what you changed, and **add one new rule** at the end of the `"rules"` list
   (put a comma after the last existing rule's `}`):

```json
  "rules": [
    { ...existing rule... },
    { ...existing rule... },
    { your new rule }
  ],
```

Look at the existing rule `support-refund-up-to-2000`: it has the same shape you need
(one agent, one tool, one `lessThanOrEqual` condition). Give your rule its own `id`.
Full rule format: [docs/policies.md](policies.md).

**You see:** `201` with a new `version` (e.g. `2`) and `"status": "DRAFT"`. A draft is **not** used yet.

If you get `400`: the JSON is not valid (a missing comma between rules is the usual cause) — read `detail`.

### 6. Test the draft in the policy playground (before it goes live)
Section **Policy playground** → `POST /api/admin/policies/playground`. Use your draft's version number:

```json
{ "agentId": "support-bot", "toolName": "getShippingRate",
  "arguments": { "weightKg": 5, "zone": "inside_dhaka" }, "policyVersion": 2 }
```

**You see:** `"decision": "ALLOW"` and `"ruleId"` = your rule's id.
Then change `weightKg` to `50` → Execute. **You see:** `"decision": "DENY"` with `"ruleId": "default-deny"`.
That is correct: no rule allowed the call, so the default (deny) decided. The `reason` names your rule and
the condition that was false, e.g. `weightKg lessThanOrEqual 20 is false (value: 50)`.
If the result is not what you expect, go back to step 5 and create another draft (versions never change).

### 7. Activate the draft
`POST /api/admin/policies/{version}/activate` with your draft's number.

**You see:** `200` and `"status": "ACTIVE"`. It is live within a few seconds, no restart.

Tell the observer **"done"**. The observer runs the check script with a real agent token.

---

## For the observer only

```powershell
.\scripts\qa5-start.ps1     # before: fresh stack, prints admin login + URLs
.\scripts\qa5-check.ps1     # after "done": 6 checks, PASS/FAIL each
```

Write the result in a copy of [docs/evidence/qa5-template.md](evidence/qa5-template.md).
