# Evidence: FR2 Agent identity (OAuth2 client credentials)

- **Date:** 2026-11-24
- **Requirement:** FR2. Agents authenticate with OAuth2 client credentials. A call may also carry the end user the agent acts for.
- **Method:** manual `curl` run against the gateway started locally (`SPRING_PROFILES_ACTIVE=local ./mvnw -pl gateway spring-boot:run`, PostgreSQL from `docker compose up -d`). Health was UP 12 s after start.
- **Agent used:** demo agent `support-bot` (clearance LOW), seeded by `DemoAgentSeeder` in the `local` profile. The secret below is the local-only default from `application.yml`, not a real secret.
- **Code changed for this test:** none.

Long values are shortened with `...`. Response headers other than the status line, `WWW-Authenticate` and `Content-Type` are left out.

## 1. Get a JWT with client_id / client_secret

```bash
curl -i -X POST localhost:8080/oauth2/token \
  -u support-bot:support-bot-local-secret \
  -d grant_type=client_credentials
```

Result: **HTTP 200**

```json
{"access_token":"eyJraWQiOiI4MjQyMzAxMy1jNjY0LTRmNTYtOTE3...","token_type":"Bearer","expires_in":300}
```

Decoded JWT payload (the middle part of the token, base64url-decoded):

```json
{"sub":"support-bot","aud":"support-bot","nbf":1795535864,"iss":"http://localhost:8080","exp":1795536164,"iat":1795535864,"jti":"85beb6f9-eda5-482c-b1bf-3d5cd811f4ad"}
```

The subject is the agent. The token lives 300 s (`exp - iat`).

## 2. Call an agent endpoint with the token: success

```bash
curl -i localhost:8080/api/agent/whoami \
  -H "Authorization: Bearer $TOKEN" \
  -H "X-On-Behalf-Of: cust-1001"
```

Result: **HTTP 200**, `Content-Type: application/json`

```json
{"agentId":"support-bot","endUserId":"cust-1001","clearance":"LOW"}
```

Both identities reach the call context: the agent (from the JWT) and the end user (from the `X-On-Behalf-Of` header).

## 3. Call without a token: 401

```bash
curl -i localhost:8080/api/agent/whoami
```

Result: **HTTP 401**, `Content-Type: application/problem+json`

```
WWW-Authenticate: Bearer resource_metadata="http://localhost:8080/.well-known/oauth-protected-resource"
```

```json
{"type":"about:blank","title":"Unauthorized","status":401,"detail":"Missing or invalid credentials","instance":"/api/agent/whoami"}
```

The error body is an RFC 9457 Problem Details document.

## 4. Token request with a wrong secret: fails

```bash
curl -i -X POST localhost:8080/oauth2/token \
  -u support-bot:WRONG \
  -d grant_type=client_credentials
```

Result: **HTTP 401**, `Content-Type: application/json;charset=UTF-8`

```json
{"error":"invalid_client"}
```

No token was issued.

## 5. Extra check: malformed token

```bash
curl -i localhost:8080/api/agent/whoami -H "Authorization: Bearer abc.def.ghi"
```

Result: **HTTP 401**

```
WWW-Authenticate: Bearer error="invalid_token", error_description="An error occurred while attempting to decode the Jwt: Malformed token", error_uri="https://tools.ietf.org/html/rfc6750#section-3.1", resource_metadata="http://localhost:8080/.well-known/oauth-protected-resource"
```

(Only the first lines of this response were captured; the body was not recorded.)

## Summary

| # | Check | Expected | Observed |
|---|-------|----------|----------|
| 1 | Valid client_id/secret at `/oauth2/token` | JWT issued | 200, Bearer token, 300 s |
| 2 | `/api/agent/whoami` with that token | success, identities shown | 200, agent + end user |
| 3 | `/api/agent/whoami` without token | rejected | 401 Problem Details |
| 4 | `/oauth2/token` with wrong secret | no token | 401 `invalid_client` |
| 5 | `/api/agent/whoami` with malformed token | rejected | 401 `invalid_token` |

## Not covered

- Only one agent (`support-bot`) and one endpoint (`/api/agent/whoami`) were tested.
- An expired token and the second demo agent (`analytics-bot`) were not tested.
- The commands were run by hand, not by an automated script. QA scripts and Testcontainers tests come in Phase 6.
