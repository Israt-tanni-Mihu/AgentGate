package com.agentgate.gateway.admin;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * FR8: the kill switches that are ON, kept IN MEMORY so that the check on every call is a set lookup.
 * PostgreSQL is the source of truth: this copy is loaded at startup and updated by {@link KillSwitchService}
 * when an admin changes a switch, so a switch takes effect on the next call, with no cache refresh wait.
 *
 * Per instance: a second gateway instance learns about a change only when it restarts (see docs/adr/0006-in-memory-limits-and-kill-switch.md).
 */
@Component
public class KillSwitchState {

    private final KillSwitchRepository repository;
    private final Set<String> killedAgents = ConcurrentHashMap.newKeySet();
    private final Set<String> killedTools = ConcurrentHashMap.newKeySet();
    private volatile boolean allWritesKilled;

    public KillSwitchState(KillSwitchRepository repository) {
        this.repository = repository;
        reload();
    }

    /** Replaces the in-memory copy with what the database says is engaged. */
    public synchronized void reload() {
        killedAgents.clear();
        killedTools.clear();
        allWritesKilled = false;
        for (KillSwitch engaged : repository.findByEngagedTrue()) {
            apply(engaged.getScope(), engaged.getTarget(), true);
        }
    }

    public synchronized void apply(KillSwitchScope scope, String target, boolean engaged) {
        switch (scope) {
            case AGENT -> change(killedAgents, target, engaged);
            case TOOL -> change(killedTools, target, engaged);
            case ALL_WRITES -> allWritesKilled = engaged;
        }
    }

    /** True when a call of this agent to this tool would be stopped by a switch; used to hide the tool from lists. */
    public boolean blocks(String agentId, String toolName, com.agentgate.gateway.registry.AccessMode mode) {
        return isAgentKilled(agentId) || isToolKilled(toolName)
                || (allWritesKilled && mode == com.agentgate.gateway.registry.AccessMode.WRITE);
    }

    boolean isAgentKilled(String agentId) {
        return killedAgents.contains(agentId);
    }

    boolean isToolKilled(String toolName) {
        return killedTools.contains(toolName);
    }

    boolean areAllWritesKilled() {
        return allWritesKilled;
    }

    private static void change(Set<String> set, String name, boolean engaged) {
        if (engaged) {
            set.add(name);
        } else {
            set.remove(name);
        }
    }
}
