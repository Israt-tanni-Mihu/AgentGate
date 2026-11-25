package com.agentgate.gateway.catalog;

import com.agentgate.gateway.admin.KillSwitchState;
import com.agentgate.gateway.health.ToolBreakers;
import com.agentgate.gateway.identity.CallContext;
import com.agentgate.gateway.policy.ActivePolicyCache;
import com.agentgate.gateway.policy.PolicyEngine;
import com.agentgate.gateway.policy.PolicySnapshot;
import com.agentgate.gateway.registry.ToolResponse;
import com.agentgate.gateway.registry.ToolDirectory;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * The ONE place that decides which tools an agent sees (FR1 "agents see all permitted tools", FR10 "tools that
 * are down are hidden"). The REST tool list uses it now; the MCP endpoint (tools/list) will call the same method.
 *
 * A tool is listed when ALL are true: it is enabled, the policy could allow this agent to call it, no kill
 * switch blocks it, and its circuit breaker is not OPEN.
 */
@Service
public class ToolCatalogService {

    private final ToolDirectory toolDirectory;
    private final ActivePolicyCache policyCache;
    private final PolicyEngine policyEngine;
    private final KillSwitchState killSwitches;
    private final ToolBreakers breakers;

    public ToolCatalogService(ToolDirectory toolDirectory, ActivePolicyCache policyCache, PolicyEngine policyEngine,
                              KillSwitchState killSwitches, ToolBreakers breakers) {
        this.toolDirectory = toolDirectory;
        this.policyCache = policyCache;
        this.policyEngine = policyEngine;
        this.killSwitches = killSwitches;
        this.breakers = breakers;
    }

    public List<ToolResponse> visibleTo(CallContext agent) {
        PolicySnapshot policy = policyCache.current();
        return toolDirectory.listEnabled().stream()
                .filter(tool -> policyEngine.mayCall(policy, agent.agentId(), tool.name()))
                .filter(tool -> !killSwitches.blocks(agent.agentId(), tool.name(), tool.accessMode()))
                .filter(tool -> !breakers.isOpen(tool.name()))
                .toList();
    }
}
