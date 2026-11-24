#!/usr/bin/env bash
# Admin setup for the downstream-MCP demo: register the mock-tools MCP server, import its tools,
# and activate a policy version that lets support-bot call the imported tool.
# Needs: gateway on :8080 and mock-tools on :8081 running (profile local), and curl.
GW=${GW:-http://localhost:8080}
ADMIN="-u ${ADMIN_USERNAME:-admin}:${ADMIN_PASSWORD:-admin-local-only}"
JSON='Content-Type: application/json'
cd "$(dirname "$0")"

echo '$ register the MCP server'
curl -s $ADMIN -H "$JSON" -d '{"name":"shop-parcels","url":"http://localhost:8081/mcp","ownerTeam":"logistics-team","defaultRiskLevel":"LOW"}' "$GW/api/admin/mcp-servers"
echo; echo
ID=$(curl -s $ADMIN "$GW/api/admin/mcp-servers?size=100" | sed 's/.*"id":\([0-9]*\),"name":"shop-parcels".*/\1/')

echo "\$ import its tools (MCP tools/list), server id $ID"
curl -s $ADMIN -X POST "$GW/api/admin/mcp-servers/$ID/import"
echo; echo

echo '$ import the REST tools too (OpenAPI), so the demo has issueRefund, getOrder, getCustomer'
curl -s $ADMIN -H "$JSON" -d '{"specUrl":"http://localhost:8081/v3/api-docs","ownerTeam":"shop-team","baseUrl":"http://localhost:8081"}' "$GW/api/admin/tools/import" | head -c 300
echo; echo

echo '$ new policy version with a rule for track_parcel, then activate it'
VERSION=$(curl -s $ADMIN -H "$JSON" -d @demo-policy-with-parcels.json "$GW/api/admin/policies" | sed 's/^{"version":\([0-9]*\),.*/\1/')
curl -s $ADMIN -X POST "$GW/api/admin/policies/$VERSION/activate" | head -c 200
echo
