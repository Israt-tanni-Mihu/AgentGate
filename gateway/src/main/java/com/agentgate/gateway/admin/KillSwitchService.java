package com.agentgate.gateway.admin;

import com.agentgate.gateway.identity.AgentRepository;
import com.agentgate.gateway.registry.ToolRepository;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Business rules of the kill switch (FR8): validates the target, saves the switch and updates the in-memory state. */
@Service
public class KillSwitchService {

    private final KillSwitchRepository repository;
    private final KillSwitchState state;
    private final AgentRepository agents;
    private final ToolRepository tools;
    private final ApplicationEventPublisher events;

    public KillSwitchService(KillSwitchRepository repository, KillSwitchState state, AgentRepository agents,
                             ToolRepository tools, ApplicationEventPublisher events) {
        this.repository = repository;
        this.state = state;
        this.agents = agents;
        this.tools = tools;
        this.events = events;
    }

    /** Turns one switch on or off. Idempotent: setting the same state again changes nothing and publishes nothing. */
    @Transactional
    public KillSwitchResponse set(KillSwitchScope scope, String target, boolean engaged) {
        String name = scope == KillSwitchScope.ALL_WRITES ? KillSwitch.ALL_TARGET : target;
        if (scope == KillSwitchScope.AGENT && !agents.existsByClientId(name)) {
            throw new KillSwitchTargetNotFoundException(scope, name);
        }
        if (scope == KillSwitchScope.TOOL && !tools.existsByName(name)) {
            throw new KillSwitchTargetNotFoundException(scope, name);
        }

        KillSwitch row = repository.findByScopeAndTarget(scope, name).orElseGet(() -> new KillSwitch(scope, name));
        boolean wasEngaged = row.isEngaged();
        row.setEngaged(engaged);
        KillSwitch saved = repository.save(row);
        state.apply(scope, name, engaged);

        if (engaged && !wasEngaged) {
            events.publishEvent(new KillSwitchActivated(scope, name));
        }
        return toResponse(saved);
    }

    @Transactional(readOnly = true)
    public Page<KillSwitchResponse> list(Pageable pageable) {
        return repository.findAll(pageable).map(KillSwitchService::toResponse);
    }

    private static KillSwitchResponse toResponse(KillSwitch row) {
        return new KillSwitchResponse(row.getScope(), row.getTarget(), row.isEngaged(), row.getUpdatedAt());
    }
}
