package com.agentgate.gateway.identity;

import com.agentgate.gateway.shared.failsafe.PolicyStoreStatus;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * QA4: how the call path looks up the agent behind an already validated token (JWT checking itself needs no
 * database). Same idea as ToolDirectory: read through to the database while it is healthy (so disabling an
 * agent still works at once), answer from the last known copy while it is down.
 * Only already issued tokens work during an outage: issuing NEW tokens needs the database (accepted in QA4).
 */
@Component
public class AgentDirectory {

    /** The few facts the call path needs about an agent. Immutable, safe to keep in memory. */
    public record AgentIdentity(String clientId, boolean enabled, Clearance clearance) {
        static AgentIdentity of(Agent agent) {
            return new AgentIdentity(agent.getClientId(), agent.isEnabled(), agent.getClearance());
        }
    }

    private final AgentRepository repository;
    private final PolicyStoreStatus store;
    private final Map<String, AgentIdentity> lastKnown = new ConcurrentHashMap<>();

    public AgentDirectory(AgentRepository repository, PolicyStoreStatus store) {
        this.repository = repository;
        this.store = store;
    }

    public Optional<AgentIdentity> findByClientId(String clientId) {
        return store.readOrFallback(() -> {
            Optional<AgentIdentity> agent = repository.findByClientId(clientId).map(AgentIdentity::of);
            agent.ifPresentOrElse(a -> lastKnown.put(clientId, a), () -> lastKnown.remove(clientId));
            return agent;
        }, () -> Optional.ofNullable(lastKnown.get(clientId)));
    }

    @EventListener(ApplicationReadyEvent.class)
    @Scheduled(fixedDelayString = "${agentgate.policy.refresh-interval:5s}",
            initialDelayString = "${agentgate.policy.refresh-interval:5s}")
    void warm() {
        store.readOrFallback(() -> {
            Map<String, AgentIdentity> all = repository.findAll().stream().map(AgentIdentity::of)
                    .collect(Collectors.toMap(AgentIdentity::clientId, a -> a));
            lastKnown.keySet().retainAll(all.keySet());
            lastKnown.putAll(all);
            return null;
        }, () -> null);
    }
}
