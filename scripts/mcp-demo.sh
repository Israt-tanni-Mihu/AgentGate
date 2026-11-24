#!/usr/bin/env bash
# Demo of the MCP endpoint (/mcp): initialize, tools/list for two agents, tools/call allowed and denied.
# Needs: gateway on :8080 and mock-tools on :8081 running (profile local). Needs only curl and bash.
GW=${GW:-http://localhost:8080}
token() { curl -s -u "$1:$1-local-secret" -d grant_type=client_credentials "$GW/oauth2/token" | sed 's/.*"access_token":"\([^"]*\)".*/\1/'; }
rpc() { # rpc <token> <end-user or -> <json body>
  local h=(-H "Authorization: Bearer $1" -H "Content-Type: application/json" -H "Accept: application/json")
  [ "$2" != "-" ] && h+=(-H "X-On-Behalf-Of: $2")
  curl -s -w '\nHTTP %{http_code}\n' "${h[@]}" -d "$3" "$GW/mcp"
}
show() { echo; echo "\$ $1"; shift; "$@"; }

curl -s -o /dev/null -u admin:admin-local-only -H 'Content-Type: application/json' \
  -d '{"specUrl":"http://localhost:8081/v3/api-docs","ownerTeam":"shop-team","baseUrl":"http://localhost:8081"}' "$GW/api/admin/tools/import"
SUPPORT=$(token support-bot); ANALYTICS=$(token analytics-bot)

show "no token" curl -s -w '\nHTTP %{http_code}\n' -H 'Content-Type: application/json' -d '{"jsonrpc":"2.0","id":1,"method":"tools/list"}' "$GW/mcp"
show "initialize (support-bot)" rpc "$SUPPORT" - '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"demo","version":"1"}}}'
show "notifications/initialized" rpc "$SUPPORT" - '{"jsonrpc":"2.0","method":"notifications/initialized"}'
echo; echo "# tools/list names per agent"
for a in support-bot analytics-bot; do
  t=$SUPPORT; [ $a = analytics-bot ] && t=$ANALYTICS
  echo "$a: $(rpc "$t" - '{"jsonrpc":"2.0","id":2,"method":"tools/list"}' | grep -o '"name":"[A-Za-z]*"' | tr '\n' ' ')"
done
show "tools/list (support-bot), first 700 chars" bash -c "$(declare -f rpc); GW=$GW; rpc $SUPPORT - '{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\"}' | head -c 700; echo"
show "tools/call getCustomer 1 for customer 1 (ALLOWED, result redacted by guard)" rpc "$SUPPORT" 1 '{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"getCustomer","arguments":{"id":1}}}'
show "tools/call getCustomer 2 for customer 1 (DENIED: other customer)" rpc "$SUPPORT" 1 '{"jsonrpc":"2.0","id":4,"method":"tools/call","params":{"name":"getCustomer","arguments":{"id":2}}}'
show "analytics-bot calls issueRefund (DENIED: not allowed for this agent)" rpc "$ANALYTICS" - '{"jsonrpc":"2.0","id":5,"method":"tools/call","params":{"name":"issueRefund","arguments":{"orderId":1001,"amount":100,"reason":"x"}}}'
show "wrong argument type (blocked by schema guard)" rpc "$SUPPORT" 1 '{"jsonrpc":"2.0","id":6,"method":"tools/call","params":{"name":"getCustomer","arguments":{"id":"x"}}}'
