package com.agentgate.gateway.admin;

import jakarta.validation.constraints.NotNull;

/** Body of PUT /api/admin/kill-switch/...: {"engaged": true} turns the switch on, false turns it off. */
public record KillSwitchRequest(@NotNull Boolean engaged) {
}
