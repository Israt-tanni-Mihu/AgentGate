package com.agentgate.gateway.playground;

import com.agentgate.gateway.identity.AgentNotFoundException;
import com.agentgate.gateway.identity.AgentService;
import com.agentgate.gateway.pipeline.UnknownToolException;
import com.agentgate.gateway.policy.ActivePolicyCache;
import com.agentgate.gateway.policy.PolicyAdminService;
import com.agentgate.gateway.policy.PolicyDecision;
import com.agentgate.gateway.policy.PolicyEngine;
import com.agentgate.gateway.policy.PolicyEvaluation;
import com.agentgate.gateway.policy.PolicyInput;
import com.agentgate.gateway.policy.PolicySnapshot;
import com.agentgate.gateway.policy.PolicyStatus;
import com.agentgate.gateway.policy.PolicyVersionResponse;
import com.agentgate.gateway.registry.ToolService;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;

/**
 * FR9: "what would the gateway decide for this call?" — without calling the tool and without changing anything.
 *
 * It asks the SAME PolicyEngine that PolicyStep uses in the live pipeline, so the playground can never
 * disagree with real traffic. Only reads: the active version comes from the same in-memory cache the
 * pipeline reads; a chosen version (e.g. a DRAFT) is only loaded, never activated.
 */
@Service
public class PolicyPlaygroundService {

    private final PolicyEngine engine;
    private final ActivePolicyCache activePolicy;
    private final PolicyAdminService policies;
    private final AgentService agents;
    private final ToolService tools;

    public PolicyPlaygroundService(PolicyEngine engine, ActivePolicyCache activePolicy, PolicyAdminService policies,
                                   AgentService agents, ToolService tools) {
        this.engine = engine;
        this.activePolicy = activePolicy;
        this.policies = policies;
        this.agents = agents;
        this.tools = tools;
    }

    public PlaygroundResponse test(PlaygroundRequest request) {
        // A typo in the agent or tool name would otherwise just look like "default deny" — say it clearly instead.
        if (!agents.exists(request.agentId())) {
            throw new AgentNotFoundException(request.agentId());
        }
        if (tools.findByName(request.toolName()).isEmpty()) {
            throw new UnknownToolException(request.toolName());
        }

        PolicySnapshot policy;
        PolicyStatus status;
        if (request.policyVersion() == null) {
            policy = activePolicy.current();
            status = policy.version() == 0 ? null : PolicyStatus.ACTIVE;
        } else {
            PolicyVersionResponse version = policies.get(request.policyVersion());   // 404 if unknown
            policy = new PolicySnapshot(version.version(), version.dryRun(), version.rules());
            status = version.status();
        }

        JsonNode arguments = request.arguments() == null ? JsonNodeFactory.instance.objectNode() : request.arguments();
        PolicyInput input = new PolicyInput(request.agentId(), blankToNull(request.endUser()), request.toolName(), arguments);
        PolicyEvaluation evaluation = engine.explain(policy, input);
        PolicyDecision decision = evaluation.decision();

        return new PlaygroundResponse(
                decision.allowed() ? PlaygroundResponse.Decision.ALLOW : PlaygroundResponse.Decision.DENY,
                decision.ruleId(), decision.reason(), decision.policyVersion(), status, policy.dryRun(),
                note(policy, decision), evaluation.checks());
    }

    private static String note(PolicySnapshot policy, PolicyDecision decision) {
        if (policy.version() == 0) {
            return "No policy version is active, so every call is denied by default.";
        }
        if (policy.dryRun() && !decision.allowed()) {
            return "This version is in dry-run: live traffic would only log this denial and let the call through.";
        }
        return null;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
