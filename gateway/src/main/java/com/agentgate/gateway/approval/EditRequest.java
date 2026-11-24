package com.agentgate.gateway.approval;

import jakarta.validation.constraints.NotNull;
import tools.jackson.databind.node.ObjectNode;

/** The approver's replacement arguments. They are NOT trusted: schema + policy run again before forwarding. */
public record EditRequest(@NotNull ObjectNode arguments) {
}
