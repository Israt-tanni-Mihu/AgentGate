package com.agentgate.gateway.audit;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * The hash chain rule, in one place (used by the writer AND the verifier, so they can never disagree):
 * <pre>hash = SHA-256( prevHash + canonicalJson(fields) )</pre>
 * "Canonical" = fields always in the same order, written by a private mapper, so the same row always gives the
 * same text. Changing any field of any stored row changes its hash, and every later row stops matching.
 */
final class AuditHasher {

    /** Fixed starting value for the first record. */
    static final String GENESIS_HASH = "0".repeat(64);

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private AuditHasher() {
    }

    static String hash(String prevHash, AuditFields f) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest((prevHash + canonicalJson(f)).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is missing in this JVM", e);
        }
    }

    static String canonicalJson(AuditFields f) {
        ObjectNode json = MAPPER.createObjectNode();   // keeps insertion order = the order below
        json.put("seq", f.seq());
        json.put("callId", String.valueOf(f.callId()));
        json.put("occurredAt", String.valueOf(f.occurredAt()));
        json.put("agentId", f.agentId());
        json.put("endUserId", f.endUserId());
        json.put("toolName", f.toolName());
        json.put("arguments", f.arguments());
        json.put("decision", f.decision());
        json.put("decidedByStep", f.decidedByStep());
        json.put("ruleId", f.ruleId());
        json.put("policyVersion", f.policyVersion());
        json.put("approvalId", f.approvalId() == null ? null : f.approvalId().toString());
        json.put("approvalState", f.approvalState());
        json.put("approver", f.approver());
        json.put("editedArguments", f.editedArguments());
        json.put("latencyMs", f.latencyMs());
        json.put("httpStatus", f.httpStatus());
        json.put("reason", f.reason());
        return MAPPER.writeValueAsString(json);
    }
}
