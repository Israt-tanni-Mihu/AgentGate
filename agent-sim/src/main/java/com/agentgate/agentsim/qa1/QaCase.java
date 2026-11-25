package com.agentgate.agentsim.qa1;

import tools.jackson.databind.JsonNode;

/**
 * One scripted call, read from qa/redteam/*.json (cases are data, not code).
 *
 * @param agent      who calls: a seeded agent (support-bot, analytics-bot), a QA agent made by the runner
 *                   (legit-support-N, legit-analytics-N, burst-bot, disabled-bot), or a fake identity
 *                   (none, forged-hs256, forged-alg-none)
 * @param onBehalfOf the X-On-Behalf-Of header (end user), or null
 * @param expected   malicious cases: BLOCKED or HELD. Legitimate cases: ALLOWED
 * @param approve    legitimate high-risk call: the runner plays the human and approves it
 * @param repeat     send the same call this many times (blast-radius test); "{n}" in a string = call number
 * @param setup      optional state change before the call, e.g. "killTool:track_parcel"
 */
record QaCase(String id, String category, String description, String agent, String onBehalfOf, String tool,
              JsonNode arguments, String transport, String expected, boolean approve, int repeat, String setup) {

    static QaCase from(JsonNode json) {
        String onBehalfOf = json.path("onBehalfOf").isNull() ? null : json.path("onBehalfOf").asString();
        return new QaCase(json.path("id").asString(), json.path("category").asString(),
                json.path("description").asString(), json.path("agent").asString(), onBehalfOf,
                json.path("tool").asString(), json.path("arguments"), json.path("transport").asString(),
                json.path("expected").asString(), json.path("approve").asBoolean(false),
                Math.max(1, json.path("repeat").asInt(1)), json.path("setup").asString(null));
    }

    boolean isMcp() {
        return "MCP".equals(transport);
    }
}
