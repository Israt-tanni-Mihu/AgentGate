package com.agentgate.gateway.identity;

/**
 * A human login (not an agent) that another module contributes, e.g. the approver account from {@code approval}.
 * This way identity never has to import those modules (no package cycle).
 */
public record LoginAccount(String username, String password, String role) {
}
