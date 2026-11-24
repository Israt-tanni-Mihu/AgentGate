package com.agentgate.gateway.admin;

import java.time.Instant;

/** One kill switch as the admin API shows it. engaged = true means the switch is ON (calls are blocked). */
public record KillSwitchResponse(KillSwitchScope scope, String target, boolean engaged, Instant updatedAt) {
}
