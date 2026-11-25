#!/usr/bin/env bash
# QA4 demo: stop PostgreSQL while the gateway runs and watch the fail-safe behaviour.
#
# Needs: gateway on :8080 started with a SHORT max-stale, e.g.  POLICY_MAX_STALE=60s java -jar gateway/target/gateway-*.jar
#        mock-tools on :8081, PostgreSQL in the docker container "agentgate-postgres" (docker compose up -d).
# Usage: scripts/qa4-failsafe-demo.sh [max-stale-seconds, default 60]
# Exit code 0 = every expectation held, 1 = at least one failed.
set -u
BASE=${BASE:-http://localhost:8080}
PG=${PG_CONTAINER:-agentgate-postgres}
MAX_STALE=${1:-60}
ADMIN="admin:${ADMIN_PASSWORD:-admin-local-only}"
FAILED=0

say()  { printf '\n=== %s\n' "$*"; }
check() { # check "label" expected actual
  if [ "$2" = "$3" ]; then printf 'PASS  %s (got %s)\n' "$1" "$3"; else printf 'FAIL  %s (expected %s, got %s)\n' "$1" "$2" "$3"; FAILED=1; fi
}
status() { curl -s --max-time 8 -o /tmp/qa4-body.json -w '%{http_code}' "$@"; }

say "0. Get a token while the database is up (new tokens need the database)"
TOKEN=$(curl -s -u support-bot:support-bot-local-secret -d grant_type=client_credentials "$BASE/oauth2/token" | sed 's/.*"access_token":"\([^"]*\)".*/\1/')
echo "token: ${TOKEN:0:20}..."
READ=(-X POST "$BASE/api/agent/calls/getCustomer" -H "Authorization: Bearer $TOKEN" -H "X-On-Behalf-Of: 1" -H "Content-Type: application/json" -d '{"id": 1}')
REFUND=(-X POST "$BASE/api/agent/calls/issueRefund" -H "Authorization: Bearer $TOKEN" -H "X-On-Behalf-Of: 1" -H "Content-Type: application/json" -d '{"orderId": 1001, "amount": 100, "reason": "damaged"}')
check "low-risk read works while healthy" 200 "$(status "${READ[@]}")"
echo "health: $(curl -s $BASE/actuator/health | grep -o '"policyStore":{[^}]*}')"

say "1. docker stop $PG"
docker stop "$PG" >/dev/null
sleep 8   # the policy refresh runs every 5 s and notices the outage

say "(a) low-risk read on last known policies"
check "getCustomer" 200 "$(status "${READ[@]}")"; cat /tmp/qa4-body.json; echo

say "(b) refund fails closed"
check "issueRefund" 503 "$(status "${REFUND[@]}")"; cat /tmp/qa4-body.json; echo

say "(c) health"
curl -s $BASE/actuator/health; echo
check "policyStore status" '"STALE"' "$(curl -s $BASE/actuator/health | sed 's/.*"policyStore":{"status":\("[A-Z]*"\).*/\1/')"
curl -s -u "$ADMIN" "$BASE/actuator/metrics/agentgate.policy.store.stale" | grep -o '"measurements":\[[^]]*\]'
curl -s -u "$ADMIN" "$BASE/actuator/metrics/agentgate.audit.queue.size" | grep -o '"measurements":\[[^]]*\]'

say "(d) wait until the last known policies are older than max-stale (${MAX_STALE}s)"
sleep $((MAX_STALE + 5))
check "getCustomer after max-stale" 503 "$(status "${READ[@]}")"; cat /tmp/qa4-body.json; echo

say "(e) docker start $PG"
docker start "$PG" >/dev/null
for i in $(seq 1 40); do
  [ "$(curl -s $BASE/actuator/health | grep -o '"policyStore":{"status":"[A-Z]*"' | grep -c UP)" = 1 ] && break; sleep 2
done
curl -s $BASE/actuator/health | grep -o '"policyStore":{[^}]*}'
check "getCustomer after recovery" 200 "$(status "${READ[@]}")"
check "issueRefund after recovery (not 503)" true "$([ "$(status "${REFUND[@]}")" != 503 ] && echo true || echo false)"

say "audit chain after recovery"
VERIFY=$(curl -s -u "$ADMIN" "$BASE/api/admin/audit/verify"); echo "$VERIFY"
check "audit verify" OK "$(echo "$VERIFY" | sed 's/.*"status":"\([A-Z]*\)".*/\1/')"
echo "fail-safe rows in the audit log:"
curl -s -u "$ADMIN" "$BASE/api/admin/audit?size=8&sort=seq,desc" | grep -o '"toolName":"[A-Za-z]*"\|"ruleId":"[a-z-]*"\|"httpStatus":[0-9]*' | paste - - - | head -8

exit $FAILED
