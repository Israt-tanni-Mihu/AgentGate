#!/usr/bin/env bash
# Evidence run for FR3 (hot reload, dry-run), FR4 (edit, expiry, kill-switch cancel, webhook, web page), FR5 truncation,
# FR6 (agent rate, daily quotas, tool rate, alerts), FR8 (agent / all-writes kill switch), FR9 (playground), QA3 lookup time, C1 memory.
#
# Needs the docker stack on a FRESH database, with a 20 s approval timeout (override file), so expiry is quick to show:
#   cp .env.example .env            # if you have no .env yet
#   C="docker compose -p agentgate-ev -f docker-compose.yml -f scripts/docker-compose.fr-evidence.yml"
#   $C up --build -d --wait
#   bash scripts/fr-evidence-demo.sh main
#   # truncation needs a small limit, so only the gateway is restarted with one:
#   AGENTGATE_GUARDS_TRUNCATION_MAXBYTES=100 $C up -d --wait gateway
#   bash scripts/fr-evidence-demo.sh truncation
#   # FR6 quotas and per-tool rate (lowered limits, gateway restarted each time):
#   AGENTGATE_LIMITS_AGENTDAILYQUOTA=3 AGENTGATE_LIMITS_TOOLDAILYQUOTA=5 $C up -d --wait gateway && bash scripts/fr-evidence-demo.sh daily-quotas
#   AGENTGATE_LIMITS_TOOLPERMINUTE=3 $C up -d --wait gateway && bash scripts/fr-evidence-demo.sh tool-rate
#   $C down -v
# Needs: curl, node (only to read JSON), docker. Credentials come from .env.
set -u
cd "$(dirname "$0")/.."
set -a; . ./.env; set +a
GW=http://localhost:8080
ADMIN="$ADMIN_USERNAME:$ADMIN_PASSWORD"
APPROVER="$APPROVER_USERNAME:$APPROVER_PASSWORD"
TMP=$(mktemp -d)

# read one value from JSON on stdin, e.g.  echo "$body" | j '.content[0].id'
j() { node -e "let s='';process.stdin.on('data',d=>s+=d).on('end',()=>{const o=JSON.parse(s);const v=eval('o'+process.argv[1]);console.log(typeof v==='object'?JSON.stringify(v):v)})" "$1"; }
now_ms() { date +%s%3N; }
title() { echo; echo "=== $*"; }
token() { curl -s -u "$1:$2" -d grant_type=client_credentials "$GW/oauth2/token" | j '.access_token'; }
# call <token> <endUser or -> <tool> <json>   -> prints "HTTP <code> <body>"
call() {
  local hdr=(); [ "$2" != "-" ] && hdr=(-H "X-On-Behalf-Of: $2")
  curl -s -o "$TMP/body" -D "$TMP/head" -w '%{http_code}' -X POST "$GW/api/agent/calls/$3" \
    -H "Authorization: Bearer $1" "${hdr[@]}" -H 'Content-Type: application/json' -d "$4" > "$TMP/code"
  echo "HTTP $(cat "$TMP/code") $(cat "$TMP/body")"
}
last_call_id() { grep -i '^x-call-id' "$TMP/head" | tr -d '\r' | awk '{print $2}'; }
admin_json() { curl -s -u "$ADMIN" -H 'Content-Type: application/json' "$@"; }
# start a held call in the background; its answer lands in $TMP/<name>.code / .body / .ms
held_call() {
  local name=$1 t0; t0=$(now_ms)
  ( curl -s -o "$TMP/$name.body" -w '%{http_code}' -X POST "$GW/api/agent/calls/issueRefund" \
      -H "Authorization: Bearer $SB" -H 'X-On-Behalf-Of: 1' -H 'Content-Type: application/json' -d "$2" > "$TMP/$name.code"
    echo $(( $(now_ms) - t0 )) > "$TMP/$name.ms" ) &
}
newest_pending() {
  for _ in $(seq 1 40); do
    id=$(curl -s -u "$APPROVER" "$GW/api/approvals?state=PENDING&size=1" | j '.content[0]?.id')
    [ -n "$id" ] && [ "$id" != "undefined" ] && { echo "$id"; return; }
    sleep 0.25
  done
}

SB=$(token support-bot "$SUPPORT_BOT_SECRET")
AB=$(token analytics-bot "$ANALYTICS_BOT_SECRET")

if [ "${1:-main}" = "truncation" ]; then
  title "FR5 truncation (gateway started with AGENTGATE_GUARDS_TRUNCATION_MAXBYTES=100)"
  echo "getCustomer 1 as support-bot for customer 1:"
  call "$SB" 1 getCustomer '{"id":1}'
  exit 0
fi

# FR6 daily quotas: gateway restarted with AGENTGATE_LIMITS_AGENTDAILYQUOTA=3 AGENTGATE_LIMITS_TOOLDAILYQUOTA=5
if [ "${1:-main}" = "daily-quotas" ]; then
  title "FR6 agent daily quota (3/day): support-bot calls getOrder 4 times"
  for i in 1 2 3 4; do echo "  call $i: $(call "$SB" 1 getOrder '{"id":1001}' | cut -c1-230)"; done
  # The 4th call stopped at the agent quota, so it was not counted for the tool (first breach stops the call).
  title "FR6 tool daily quota (5/day for getOrder, 3 used): analytics-bot calls getOrder 3 times"
  for i in 1 2 3; do echo "  call $i: $(call "$AB" - getOrder '{"id":1001}' | cut -c1-230)"; done
  echo "alerts:"; curl -s -u "$ADMIN" "$GW/api/admin/alerts?size=5" | j '.content.map(a=>a.message)'
  exit 0
fi

# FR6 per-tool rate: gateway restarted with AGENTGATE_LIMITS_TOOLPERMINUTE=3 (two agents share the tool's budget)
if [ "${1:-main}" = "tool-rate" ]; then
  sleep $(( 61 - 10#$(date +%S) ))   # start at a new minute (fixed windows)
  title "FR6 tool rate (3/min for getOrder): support-bot 2 calls, then analytics-bot 2 calls"
  for i in 1 2; do echo "  support-bot   $i: $(call "$SB" 1 getOrder '{"id":1001}' | cut -c1-60)"; done
  for i in 1 2; do echo "  analytics-bot $i: $(call "$AB" - getOrder '{"id":1001}' | cut -c1-230)"; done
  exit 0
fi

title "FR9 playground: active policy, refund 1500 (expect ALLOW)"
admin_json -X POST "$GW/api/admin/policies/playground" \
  -d '{"agentId":"support-bot","toolName":"issueRefund","arguments":{"orderId":1001,"amount":1500,"reason":"damaged"},"endUser":"1"}' \
  | j '' | node -e "let s='';process.stdin.on('data',d=>s+=d).on('end',()=>{const o=JSON.parse(s);console.log(JSON.stringify({decision:o.decision,ruleId:o.ruleId,policyVersion:o.policyVersion,policyStatus:o.policyStatus}))})"

title "FR9 playground: active policy, refund 5000 (expect DENY with the reason)"
admin_json -X POST "$GW/api/admin/policies/playground" \
  -d '{"agentId":"support-bot","toolName":"issueRefund","arguments":{"orderId":1001,"amount":5000,"reason":"damaged"},"endUser":"1"}' \
  | node -e "let s='';process.stdin.on('data',d=>s+=d).on('end',()=>{const o=JSON.parse(s);console.log(JSON.stringify({decision:o.decision,ruleId:o.ruleId,reason:o.reason}))})"

title "FR3/FR9: create DRAFT version 2 = version 1 + rule 'support-sms' (support-bot may call sendSms)"
V1=$(admin_json "$GW/api/admin/policies/1")
echo "$V1" | node -e "let s='';process.stdin.on('data',d=>s+=d).on('end',()=>{const o=JSON.parse(s);
  o.rules.push({id:'support-sms',description:'Support bot may send SMS',effect:'ALLOW',agents:['support-bot'],tools:['sendSms'],conditions:[]});
  console.log(JSON.stringify({description:'v1 + support-sms (evidence run)',rules:o.rules}))})" > "$TMP/v2.json"
admin_json -X POST "$GW/api/admin/policies" --data-binary "@$TMP/v2.json" | j '.status' | sed 's/^/version 2 status: /'

title "FR9 playground on the DRAFT (policyVersion 2): sendSms (expect ALLOW, draft not activated)"
admin_json -X POST "$GW/api/admin/policies/playground" \
  -d '{"agentId":"support-bot","toolName":"sendSms","arguments":{"customerId":1,"message":"hi"},"endUser":"1","policyVersion":2}' \
  | node -e "let s='';process.stdin.on('data',d=>s+=d).on('end',()=>{const o=JSON.parse(s);console.log(JSON.stringify({decision:o.decision,ruleId:o.ruleId,policyVersion:o.policyVersion,policyStatus:o.policyStatus}))})"
echo "live sendSms while v2 is only a DRAFT (expect 403):"
call "$SB" 1 sendSms '{"customerId":1,"message":"hi"}'

title "FR3 hot reload through the admin API: activate v2, time until sendSms is allowed"
T0=$(now_ms)
admin_json -X POST "$GW/api/admin/policies/2/activate" -d '{}' | j '.status' | sed 's/^/version 2 status: /'
call "$SB" 1 sendSms '{"customerId":1,"message":"hi"}'
echo "time from activate request to the allowed call: $(( $(now_ms) - T0 )) ms (no restart)"

title "FR3 hot reload from the database (as another gateway instance would): back to v1 by SQL, poll every 250 ms"
docker exec agentgate-postgres psql -q -U "$DB_USERNAME" -d "$DB_NAME" \
  -c "BEGIN; UPDATE policy_versions SET status='RETIRED' WHERE version=2; UPDATE policy_versions SET status='ACTIVE' WHERE version=1; COMMIT;"
T0=$(now_ms); CODE=201; N=0
while [ "$CODE" != "403" ] && [ $(( $(now_ms) - T0 )) -lt 15000 ]; do
  sleep 0.25; call "$SB" 1 sendSms '{"customerId":1,"message":"hi"}' > /dev/null; CODE=$(cat "$TMP/code"); N=$((N+1))
done
echo "sendSms answered $CODE after $(( $(now_ms) - T0 )) ms ($N polls); refresh interval 5 s, requirement 10 s"
call "$SB" 1 sendSms '{"customerId":1,"message":"hi"}' | cut -c1-220

title "FR3 dry-run: version 1 in dry-run mode, sendSms (default-deny) is let through and logged"
admin_json -X PUT "$GW/api/admin/policies/1/dry-run" -d '{"enabled":true}' | j '.dryRun' | sed 's/^/dryRun: /'
call "$SB" 1 sendSms '{"customerId":1,"message":"dry run"}'
sleep 1
docker logs agentgate-gateway 2>&1 | grep -o 'DRY-RUN would DENY[^"]*' | tail -1
admin_json -X PUT "$GW/api/admin/policies/1/dry-run" -d '{"enabled":false}' | j '.dryRun' | sed 's/^/dryRun: /'
call "$SB" 1 sendSms '{"customerId":1,"message":"hi"}' | cut -c1-120

title "FR4 approval web page"
curl -s -o "$TMP/page" -w 'GET /approvals.html (approver login) -> HTTP %{http_code}\n' -u "$APPROVER" "$GW/approvals.html"
grep -o '<title>[^<]*</title>' "$TMP/page"

title "FR4 edit: refund 1500 held, approver edits the amount to 500"
held_call edit '{"orderId":1001,"amount":1500,"reason":"damaged"}'
ID=$(newest_pending); echo "PENDING approval $ID"
curl -s -u "$APPROVER" -H 'Content-Type: application/json' -X POST "$GW/api/approvals/$ID/edit" \
  -d '{"arguments":{"orderId":1001,"amount":500,"reason":"damaged"}}' | j '.state' | sed 's/^/state: /'
wait; echo "agent got: HTTP $(cat "$TMP/edit.code") $(cat "$TMP/edit.body")"

title "FR4 edit that breaks the policy: refund 100 held, approver edits to 5000 (expect 403)"
held_call edit2 '{"orderId":1001,"amount":100,"reason":"damaged"}'
ID=$(newest_pending); echo "PENDING approval $ID"
curl -s -u "$APPROVER" -H 'Content-Type: application/json' -X POST "$GW/api/approvals/$ID/edit" \
  -d '{"arguments":{"orderId":1001,"amount":5000,"reason":"damaged"}}' | j '.state' | sed 's/^/state: /'
wait; echo "agent got: HTTP $(cat "$TMP/edit2.code") $(cat "$TMP/edit2.body" | cut -c1-300)"

title "FR4 + FR8: refund held, admin kills tool issueRefund -> pending approval cancelled"
held_call cancel '{"orderId":1001,"amount":400,"reason":"damaged"}'
ID=$(newest_pending); echo "PENDING approval $ID"
T0=$(now_ms)
admin_json -X PUT "$GW/api/admin/kill-switch/tools/issueRefund" -d '{"engaged":true}' > /dev/null
wait; echo "agent got (after $(( $(now_ms) - T0 )) ms): HTTP $(cat "$TMP/cancel.code") $(cat "$TMP/cancel.body" | cut -c1-300)"
curl -s -u "$APPROVER" "$GW/api/approvals/$ID" | j '.state' | sed 's/^/approval state: /'
admin_json -X PUT "$GW/api/admin/kill-switch/tools/issueRefund" -d '{"engaged":false}' > /dev/null

title "FR4 expiry: refund held, nobody answers (gateway started with AGENTGATE_APPROVAL_TIMEOUT=20s)"
held_call expire '{"orderId":1001,"amount":300,"reason":"damaged"}'
ID=$(newest_pending); echo "PENDING approval $ID"
wait; echo "agent got after $(cat "$TMP/expire.ms") ms: HTTP $(cat "$TMP/expire.code") $(cat "$TMP/expire.body" | cut -c1-300)"
curl -s -u "$APPROVER" "$GW/api/approvals/$ID" | j '.state' | sed 's/^/approval state: /'

title "FR4 webhook: what mock-tools received"
sleep 1
# one line per webhook: a new request has expiresAt and no state; a closed one has its final state
docker logs agentgate-mock-tools 2>&1 | grep 'WEBHOOK received' | node -e "require('readline').createInterface({input:process.stdin}).on('line',l=>{const m=JSON.parse(l).message;const e=JSON.parse(m.slice(m.indexOf('{')));console.log(JSON.stringify({approvalId:e.approvalId.slice(0,8),event:e.state?'closed: '+e.state:'new request',amount:(e.editedArguments||e.arguments||{}).amount}))})"

title "FR8 kill switch: agent support-bot"
admin_json -X PUT "$GW/api/admin/kill-switch/agents/support-bot" -d '{"engaged":true}' > /dev/null
echo "support-bot getOrder:   $(call "$SB" 1 getOrder '{"id":1001}' | cut -c1-200)"
echo "analytics-bot getOrder: $(call "$AB" - getOrder '{"id":1001}' | cut -c1-60)"
admin_json -X PUT "$GW/api/admin/kill-switch/agents/support-bot" -d '{"engaged":false}' > /dev/null
echo "released, support-bot getOrder: $(call "$SB" 1 getOrder '{"id":1001}' | cut -c1-60)"

title "FR8 kill switch: all write tools"
admin_json -X PUT "$GW/api/admin/kill-switch/all-writes" -d '{"engaged":true}' > /dev/null
echo "issueRefund (WRITE): $(call "$SB" 1 issueRefund '{"orderId":1001,"amount":100,"reason":"x"}' | cut -c1-220)"
echo "getOrder (READ):     $(call "$SB" 1 getOrder '{"id":1001}' | cut -c1-60)"
admin_json -X PUT "$GW/api/admin/kill-switch/all-writes" -d '{"engaged":false}' > /dev/null

title "QA3 (machine part): look up one call by its X-Call-Id"
call "$SB" 1 getCustomer '{"id":2}' > /dev/null; CID=$(last_call_id); echo "denied call, X-Call-Id: $CID"
sleep 1
curl -s -o "$TMP/audit" -w 'GET /api/admin/audit/{callId} -> HTTP %{http_code} in %{time_total} s\n' -u "$ADMIN" "$GW/api/admin/audit/$CID"
node -e "let s='';process.stdin.on('data',d=>s+=d).on('end',()=>{const o=JSON.parse(s);console.log(JSON.stringify({who:o.agentId,onBehalfOf:o.endUserId,what:o.toolName,arguments:o.arguments,rule:o.ruleId,policyVersion:o.policyVersion,decision:o.decision}))})" < "$TMP/audit"
curl -s -u "$ADMIN" "$GW/api/admin/audit/verify"; echo

title "FR6 rate limit: analytics-bot sends 61 getOrder calls in one minute (limit 60/min per agent)"
# Limits use fixed windows aligned to the clock. Start at the beginning of a new minute, otherwise the 61 calls can
# cross the window edge and all pass (seen in a real run; it is the documented fixed-window edge effect).
sleep $(( 61 - 10#$(date +%S) ))
: > "$TMP/codes"
for i in $(seq 1 61); do call "$AB" - getOrder '{"id":1001}' > /dev/null; cat "$TMP/code" >> "$TMP/codes"; echo >> "$TMP/codes"; done
sort "$TMP/codes" | uniq -c | sed 's/^/  /'
echo "61st call: HTTP $(cat "$TMP/code") $(cat "$TMP/body" | cut -c1-300)"
grep -i '^retry-after' "$TMP/head" | tr -d '\r'
echo "alerts (GET /api/admin/alerts):"
curl -s -u "$ADMIN" "$GW/api/admin/alerts?size=5" | j '.content'

title "C1 memory of the running stack (docker stats --no-stream)"
docker stats --no-stream --format 'table {{.Name}}\t{{.MemUsage}}\t{{.CPUPerc}}' agentgate-postgres agentgate-mock-tools agentgate-gateway
docker info --format 'Docker VM memory: {{.MemTotal}} bytes, CPUs: {{.NCPU}}'
rm -rf "$TMP"
