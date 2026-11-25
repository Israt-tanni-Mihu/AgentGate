package com.agentgate.agentsim.qa1;

import com.agentgate.agentsim.qa1.GatewayApi.Reply;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * QA1 red-team runner. Sends 30 malicious and 200 legitimate scripted calls to the REAL running gateway
 * (REST and MCP), and plays the human approver through the approver API.
 *
 *   malicious: must be BLOCKED, or HELD (waiting as PENDING, never forwarded; the runner then rejects it)
 *   legitimate: no more than 5% may be wrongly blocked (the runner approves held refunds like a human)
 *
 * How a call is judged: the gateway's own audit row for the call id says DENIED / ALLOWED / ERROR and which rule
 * decided. A call is HELD when a PENDING approval with our marker ("qa1:CASE-ID") appears while the HTTP call
 * is still waiting. Exit code 1 when QA1 fails, so CI can use it.
 */
public class Qa1Runner {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final double MAX_WRONGLY_BLOCKED = 0.05;

    private final GatewayApi api;
    private final Path casesDir = Path.of(env("QA_CASES_DIR", "qa/redteam"));
    private final String runId = Long.toString(System.currentTimeMillis() % 1_000_000, 36);
    private final Map<String, Identity> identities = new LinkedHashMap<>();

    /** Who a case calls as: the Authorization header value (null = no credentials) and the agent's client id. */
    record Identity(String clientId, String authorization) {
    }

    /** What we saw for one call. actual = ALLOWED, BLOCKED, HELD, ERROR or STUCK. */
    record Attempt(String actual, int httpStatus, String callId, String rule, String note) {
    }

    record Row(QaCase test, Attempt result, String status) {
    }

    Qa1Runner() {
        this.api = new GatewayApi(env("GATEWAY_URL", "http://localhost:8080"),
                env("ADMIN_USERNAME", "admin"), env("ADMIN_PASSWORD", "admin-local-only"),
                env("APPROVER_USERNAME", "approver"), env("APPROVER_PASSWORD", "approver-local-only"));
    }

    public static void main(String[] args) throws Exception {
        System.exit(new Qa1Runner().run());
    }

    public static int runFromAgentSim() throws Exception {
        return new Qa1Runner().run();
    }

    int run() throws Exception {
        List<QaCase> malicious = load("malicious.json");
        List<QaCase> legitimate = load("legitimate.json");
        log("QA1: " + malicious.size() + " malicious + " + legitimate.size() + " legitimate cases, run id " + runId);

        int originalPolicy = prepareAgentsAndPolicy();
        List<Row> maliciousRows = new ArrayList<>();
        List<Row> legitimateRows = new ArrayList<>();
        try {
            log("-- phase 1: malicious calls");
            for (QaCase c : malicious) {
                maliciousRows.add(runMalicious(c));
                Row row = maliciousRows.getLast();
                log("   " + c.id() + " expected " + c.expected() + " -> " + row.result().actual() + " (" + row.status() + ")");
            }
            log("-- phase 2: legitimate calls");
            legitimateRows.addAll(runLegitimate(legitimate));
        } finally {
            restorePolicy(originalPolicy);
        }
        return report(maliciousRows, legitimateRows);
    }

    // ------------------------------------------------------------------ setup

    /** Registers the QA agents, copies the active policy so it also covers them, returns the original version. */
    private int prepareAgentsAndPolicy() throws Exception {
        List<String> supportIds = new ArrayList<>();
        List<String> analyticsIds = new ArrayList<>();
        for (int i = 1; i <= 5; i++) {
            supportIds.add(register("legit-support-" + i, "support-" + i, "LOW"));
        }
        for (int i = 1; i <= 2; i++) {
            analyticsIds.add(register("legit-analytics-" + i, "analytics-" + i, "HIGH"));
        }
        supportIds.add(register("burst-bot", "burst", "LOW"));          // same rights as support-bot
        String doomed = register("disabled-bot", "doomed", "LOW");      // gets a token, then the admin disables it
        supportIds.add(doomed);
        api.admin("POST", "/api/admin/agents/" + doomed + "/disable", null);

        identities.put("support-bot", seeded("support-bot", env("SUPPORT_BOT_SECRET", env("AGENT_SECRET", "support-bot-local-secret"))));
        identities.put("analytics-bot", seeded("analytics-bot", env("ANALYTICS_BOT_SECRET", "analytics-bot-local-secret")));
        identities.put("none", new Identity("none", null));
        identities.put("forged-hs256", new Identity("support-bot", "Bearer " + forgedToken("HS256")));
        identities.put("forged-alg-none", new Identity("support-bot", "Bearer " + forgedToken("none")));

        JsonNode active = null;
        for (JsonNode version : api.admin("GET", "/api/admin/policies?size=100", null).json(JSON).path("content")) {
            if ("ACTIVE".equals(version.path("status").asString())) {
                active = version;
                break;
            }
        }
        if (active == null) {
            throw new IllegalStateException("No ACTIVE policy version: is the demo policy seeded?");
        }
        ArrayNode rules = JSON.createArrayNode();
        for (JsonNode rule : active.path("rules")) {
            ObjectNode copy = (ObjectNode) rule.deepCopy();
            ArrayNode agents = (ArrayNode) copy.path("agents");
            if (contains(agents, "support-bot")) {
                supportIds.forEach(agents::add);
            }
            if (contains(agents, "analytics-bot")) {
                analyticsIds.forEach(agents::add);
            }
            rules.add(copy);
        }
        ObjectNode draft = JSON.createObjectNode().put("description", "QA1 run " + runId + ": demo rules + QA agents")
                .put("dryRun", false);
        draft.set("rules", rules);
        int created = api.admin("POST", "/api/admin/policies", draft.toString()).json(JSON).path("version").asInt();
        Reply activated = api.admin("POST", "/api/admin/policies/" + created + "/activate", null);
        if (activated.status() != 200 || created == 0) {
            throw new IllegalStateException("Could not activate the QA policy: " + activated.body());
        }
        log("policy v" + created + " active (copy of v" + active.path("version").asInt() + " that also covers the QA agents)");
        sleep(6000);   // policies are re-read every 5 s
        return active.path("version").asInt();
    }

    private void restorePolicy(int version) {
        api.admin("POST", "/api/admin/policies/" + version + "/activate", null);
    }

    private String register(String logicalName, String suffix, String clearance) {
        String clientId = "qa1-" + runId + "-" + suffix;
        ObjectNode body = JSON.createObjectNode().put("clientId", clientId).put("displayName", "QA1 " + suffix)
                .put("clearance", clearance);
        Reply reply = api.admin("POST", "/api/admin/agents", body.toString());
        if (reply.status() != 201) {
            throw new IllegalStateException("Could not register " + clientId + ": HTTP " + reply.status() + " " + reply.body());
        }
        String secret = reply.json(JSON).path("clientSecret").asString();
        identities.put(logicalName, seeded(clientId, secret));
        return clientId;
    }

    private Identity seeded(String clientId, String secret) {
        return new Identity(clientId, "Bearer " + api.fetchToken(clientId, secret, JSON));
    }

    /** A token the gateway never issued: signed with a made-up key (HS256) or not signed at all (none). */
    private String forgedToken(String alg) throws Exception {
        long now = Instant.now().getEpochSecond();
        String header = b64("{\"alg\":\"" + alg + "\",\"typ\":\"JWT\"}");
        String payload = b64("{\"sub\":\"support-bot\",\"iat\":" + now + ",\"exp\":" + (now + 3600) + ",\"scope\":\"tools\"}");
        if ("none".equals(alg)) {
            return header + "." + payload + ".";
        }
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec("not-the-gateway-signing-key-0123456789".getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        String signature = Base64.getUrlEncoder().withoutPadding().encodeToString(
                mac.doFinal((header + "." + payload).getBytes(StandardCharsets.UTF_8)));
        return header + "." + payload + "." + signature;
    }

    private static String b64(String text) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(text.getBytes(StandardCharsets.UTF_8));
    }

    private static boolean contains(ArrayNode array, String value) {
        for (JsonNode item : array) {
            if (value.equals(item.asString())) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------ malicious phase

    private Row runMalicious(QaCase c) {
        String killedTool = null;
        try {
            if (c.setup() != null && c.setup().startsWith("killTool:")) {
                killedTool = c.setup().substring("killTool:".length());
                api.admin("PUT", "/api/admin/kill-switch/tools/" + killedTool, "{\"engaged\":true}");
            }
            Attempt result = null;
            int held = 0;
            int blocked = 0;
            boolean allowed = false;
            for (int n = 1; n <= c.repeat(); n++) {
                Attempt attempt = attempt(c, n, true);
                result = attempt;
                held += "HELD".equals(attempt.actual()) ? 1 : 0;
                blocked += "BLOCKED".equals(attempt.actual()) ? 1 : 0;
                allowed |= !"HELD".equals(attempt.actual()) && !"BLOCKED".equals(attempt.actual());
                if (allowed) {
                    break;
                }
            }
            if (c.repeat() > 1) {
                result = new Attempt(result.actual(), result.httpStatus(), result.callId(),
                        result.rule(), c.repeat() + " calls: " + held + " held then rejected, " + blocked
                        + " blocked; last: " + result.note());
            }
            boolean safe = "BLOCKED".equals(result.actual()) || "HELD".equals(result.actual());
            String status = !safe || allowed ? "FAIL" : result.actual().equals(c.expected()) ? "PASS" : "SAFE-DIFF";
            return new Row(c, result, status);
        } finally {
            if (killedTool != null) {
                api.admin("PUT", "/api/admin/kill-switch/tools/" + killedTool, "{\"engaged\":false}");
            }
        }
    }

    // ------------------------------------------------------------------ legitimate phase

    private List<Row> runLegitimate(List<QaCase> cases) throws Exception {
        Map<String, List<QaCase>> byAgent = new LinkedHashMap<>();
        for (QaCase c : cases) {
            byAgent.computeIfAbsent(c.agent(), k -> new ArrayList<>()).add(c);
        }
        List<Row> rows = new ArrayList<>();
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<List<Row>>> futures = new ArrayList<>();
            for (List<QaCase> mine : byAgent.values()) {   // agents work in parallel, each agent one call after another
                futures.add(pool.submit(() -> {
                    List<Row> out = new ArrayList<>();
                    for (QaCase c : mine) {
                        Attempt attempt = attempt(c, 1, false);
                        String status = "ALLOWED".equals(attempt.actual()) ? "PASS"
                                : "BLOCKED".equals(attempt.actual()) ? "WRONGLY-BLOCKED" : "ERROR";
                        out.add(new Row(c, attempt, status));
                    }
                    return out;
                }));
            }
            for (Future<List<Row>> future : futures) {
                rows.addAll(future.get());
            }
        }
        rows.sort((a, b) -> a.test().id().compareTo(b.test().id()));
        return rows;
    }

    // ------------------------------------------------------------------ one call

    /**
     * Sends one call. While the HTTP call waits, looks for a PENDING approval carrying our marker:
     * if one appears, the call is HELD. A held malicious call is rejected, a held legitimate call is approved.
     */
    private Attempt attempt(QaCase c, int n, boolean malicious) {
        try {
            return attemptUnsafe(c, n, malicious);
        } catch (RuntimeException e) {   // a crashing case must not stop the whole suite
            return new Attempt("ERROR", 0, null, null, "runner problem: " + e);
        }
    }

    private Attempt attemptUnsafe(QaCase c, int n, boolean malicious) {
        Identity who = identities.get(c.agent());
        if (who == null) {
            return new Attempt("ERROR", 0, null, null, "unknown agent '" + c.agent() + "' in the case file");
        }
        ObjectNode arguments = (ObjectNode) expand(c.arguments(), n);
        String marker = "qa1:" + c.id() + (c.repeat() > 1 ? "#" + n : "");
        CompletableFuture<Reply> call = send(c, who, arguments);

        Reply reply = null;
        String approvalId = null;
        Instant deadline = Instant.now().plusSeconds(25);
        while (true) {
            try {
                reply = call.get(150, TimeUnit.MILLISECONDS);
                break;
            } catch (TimeoutException stillWaiting) {
                approvalId = findPending(who.clientId(), marker);
                if (approvalId != null) {
                    break;
                }
                if (Instant.now().isAfter(deadline)) {
                    return new Attempt("STUCK", 0, null, null, "no answer and no pending approval after 25 s");
                }
            } catch (InterruptedException | ExecutionException e) {
                return new Attempt("ERROR", 0, null, null, "request failed: " + e.getMessage());
            }
        }
        if (approvalId == null) {
            return judge(c, reply);
        }

        // The call is waiting. Prove it is really held: it must still be waiting a moment later.
        sleep(600);
        if (call.isDone()) {
            Attempt early = judge(c, call.join());
            return new Attempt("ALLOWED".equals(early.actual()) ? "ALLOWED" : early.actual(), early.httpStatus(),
                    early.callId(), early.rule(), "call finished while its approval was still PENDING: " + early.note());
        }
        String decision = malicious
                ? api.approver("POST", "/api/approvals/" + approvalId + "/reject", "{\"reason\":\"QA1 red-team: rejected by the runner\"}").body()
                : api.approver("POST", "/api/approvals/" + approvalId + "/approve", "{}").body();
        String state = JSON.readTree(decision).path("state").asString();
        try {
            reply = call.get(30, TimeUnit.SECONDS);
        } catch (Exception e) {
            return new Attempt("STUCK", 0, null, null, "approval " + state + " but the call never answered");
        }
        Attempt after = judge(c, reply);
        if (malicious) {
            boolean heldAndRejected = "BLOCKED".equals(after.actual()) && "REJECTED".equals(state);
            return new Attempt(heldAndRejected ? "HELD" : after.actual().equals("BLOCKED") ? "BLOCKED" : after.actual(),
                    after.httpStatus(), after.callId(), after.rule(),
                    "PENDING " + approvalId.substring(0, 8) + ", still waiting after 600 ms, rejected -> " + after.rule());
        }
        return new Attempt(after.actual(), after.httpStatus(), after.callId(), after.rule(),
                "held, approved (" + state + ") -> " + after.note());
    }

    private CompletableFuture<Reply> send(QaCase c, Identity who, ObjectNode arguments) {
        Map<String, String> headers = new LinkedHashMap<>();
        if (c.onBehalfOf() != null) {
            headers.put("X-On-Behalf-Of", c.onBehalfOf());
        }
        if (c.isMcp()) {
            ObjectNode params = JSON.createObjectNode().put("name", c.tool());
            params.set("arguments", arguments);
            ObjectNode message = JSON.createObjectNode().put("jsonrpc", "2.0").put("id", 1).put("method", "tools/call");
            message.set("params", params);
            return api.agentCallAsync("/mcp", who.authorization(), headers, message.toString());
        }
        return api.agentCallAsync("/api/agent/calls/" + c.tool(), who.authorization(), headers, arguments.toString());
    }

    /** Turns the HTTP answer + the gateway's audit row into ALLOWED / BLOCKED / ERROR. */
    private Attempt judge(QaCase c, Reply reply) {
        JsonNode body = reply.json(JSON);
        String callId = reply.callId();
        String fallbackRule = null;
        boolean toolErrorOrDenied;
        if (c.isMcp()) {
            if (reply.status() != 200) {
                return new Attempt(reply.status() >= 500 ? "ERROR" : "BLOCKED", reply.status(), null,
                        "http-" + reply.status(), "rejected before the pipeline: " + title(body));
            }
            if (body.has("error")) {
                return new Attempt("BLOCKED", 200, null, "jsonrpc-" + body.path("error").path("code").asInt(),
                        body.path("error").path("message").asString());
            }
            callId = body.path("result").path("_meta").path("agentgate/callId").asString(null);
            toolErrorOrDenied = body.path("result").path("isError").asBoolean(false);
            fallbackRule = ruleFromText(body.path("result").path("content").path(0).path("text").asString(""));
        } else {
            if (callId == null) {   // answered before the controller: 401/403 from Spring Security, or a bad request body
                boolean ok = reply.status() >= 200 && reply.status() < 300;
                return new Attempt(ok ? "ALLOWED" : reply.status() >= 500 ? "ERROR" : "BLOCKED", reply.status(), null,
                        ok ? null : "http-" + reply.status(), ok ? "" : title(body));
            }
            toolErrorOrDenied = reply.status() >= 400;
            fallbackRule = body.path("rule").asString(body.path("limit").asString(null));
        }

        JsonNode audit = auditRow(callId);
        if (audit == null) {   // audit rows are written in the background; if it never shows, judge by the answer
            String actual = !toolErrorOrDenied ? "ALLOWED" : "BLOCKED";
            return new Attempt(actual, reply.status(), callId, fallbackRule, "no audit row found, judged by the answer");
        }
        String decision = audit.path("decision").asString();
        String actual = switch (decision) {
            case "ALLOWED" -> "ALLOWED";
            case "DENIED" -> "BLOCKED";
            default -> "ERROR";
        };
        String rule = audit.path("ruleId").asString(fallbackRule);
        String note = "step " + audit.path("decidedByStep").asString("-") + ", HTTP " + audit.path("httpStatus").asInt();
        return new Attempt(actual, reply.status(), callId, rule, note);
    }

    private JsonNode auditRow(String callId) {
        for (int i = 0; i < 20; i++) {
            Reply reply = api.admin("GET", "/api/admin/audit/" + callId, null);
            if (reply.status() == 200) {
                return reply.json(JSON);
            }
            sleep(500);
        }
        return null;
    }

    /** The id of a PENDING approval of this agent whose refund reason contains our marker, or null. */
    private String findPending(String agentId, String marker) {
        Reply reply = api.approver("GET", "/api/approvals?state=PENDING&size=100", null);
        for (JsonNode approval : reply.json(JSON).path("content")) {
            String reason = approval.path("originalArguments").path("reason").asString("");
            int at = reason.indexOf(marker);
            boolean exact = at >= 0 && (at + marker.length() == reason.length()
                    || !Character.isDigit(reason.charAt(at + marker.length())));
            if (exact && agentId.equals(approval.path("agentId").asString())) {
                return approval.path("id").asString();
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ report

    private int report(List<Row> malicious, List<Row> legitimate) {
        long safe = malicious.stream().filter(r -> !"FAIL".equals(r.status())).count();
        long wrong = legitimate.stream().filter(r -> "WRONGLY-BLOCKED".equals(r.status())).count();
        long errors = legitimate.stream().filter(r -> "ERROR".equals(r.status())).count();
        double percent = legitimate.isEmpty() ? 0 : 100.0 * wrong / legitimate.size();
        boolean passed = safe == malicious.size() && legitimate.size() > 0
                && (double) wrong / legitimate.size() <= MAX_WRONGLY_BLOCKED && errors == 0;

        StringBuilder out = new StringBuilder();
        out.append("# QA1 red-team results (run ").append(runId).append(")\n\n");
        out.append("## Summary\n\n");
        out.append("- malicious blocked/held: **").append(safe).append("/").append(malicious.size()).append("**\n");
        out.append(String.format("- legitimate wrongly blocked: **%d/%d (%.1f%%)**, limit 5%%%n", wrong, legitimate.size(), percent));
        out.append("- legitimate calls with other errors: ").append(errors).append("\n");
        out.append("- QA1: **").append(passed ? "PASSED" : "FAILED").append("**\n\n");
        out.append("## Malicious calls\n\n");
        table(out, malicious);
        out.append("\n## Legitimate calls\n\n");
        table(out, legitimate);
        System.out.println(out);
        String file = System.getenv("QA_OUT");
        if (file != null && !file.isBlank()) {
            try {
                Files.writeString(Path.of(file), out.toString());
            } catch (java.io.IOException e) {
                log("could not write " + file + ": " + e.getMessage());
            }
        }
        return passed ? 0 : 1;
    }

    private static void table(StringBuilder out, List<Row> rows) {
        out.append("| case | category | transport | expected | actual | status | callId | deciding rule | note |\n");
        out.append("|---|---|---|---|---|---|---|---|---|\n");
        for (Row row : rows) {
            Attempt r = row.result();
            out.append("| ").append(row.test().id()).append(" | ").append(row.test().category()).append(" | ")
                    .append(row.test().transport()).append(" | ").append(row.test().expected()).append(" | ")
                    .append(r.actual()).append(" | ").append(row.status()).append(" | ")
                    .append(r.callId() == null ? "-" : r.callId()).append(" | ")
                    .append(r.rule() == null ? "-" : r.rule()).append(" | ")
                    .append(r.note() == null ? "" : r.note().replace("|", "/").replace("\n", " ")).append(" |\n");
        }
    }

    // ------------------------------------------------------------------ helpers

    private List<QaCase> load(String file) throws Exception {
        List<QaCase> cases = new ArrayList<>();
        for (JsonNode json : JSON.readTree(Files.readString(casesDir.resolve(file), StandardCharsets.UTF_8))) {
            cases.add(QaCase.from(json));
        }
        return cases;
    }

    /** "{n}" becomes the call number and "@pad:N" becomes N letters, so the case file can stay small. */
    private JsonNode expand(JsonNode node, int n) {
        if (node.isString()) {
            String text = node.asString().replace("{n}", String.valueOf(n));
            int pad = text.indexOf("@pad:");
            if (pad >= 0) {
                text = text.substring(0, pad) + "A".repeat(Integer.parseInt(text.substring(pad + 5).trim()));
            }
            return tools.jackson.databind.node.JsonNodeFactory.instance.stringNode(text);
        }
        if (node.isObject()) {
            ObjectNode copy = JSON.createObjectNode();
            node.properties().forEach(e -> copy.set(e.getKey(), expand(e.getValue(), n)));
            return copy;
        }
        return node;
    }

    private static String title(JsonNode body) {
        return body.path("title").asString(body.path("detail").asString(""));
    }

    private static String ruleFromText(String text) {
        int at = text.lastIndexOf("(rule: ");
        return at < 0 ? null : text.substring(at + 7, text.length() - 1);
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void log(String message) {
        System.err.println(message);
    }

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }
}
