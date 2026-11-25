package com.agentgate.gateway.registry;

import com.agentgate.gateway.shared.failsafe.PolicyStoreStatus;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.domain.Pageable;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * QA4: how the call path finds tools. While the database is healthy it reads through to it (so a change by an
 * admin is visible at once) and remembers what it read. While the database is down it answers from that memory,
 * so a call never needs the database just to find its tool.
 *
 * A scheduled warm-up copies ALL tools into memory, so tools nobody called yet are known too.
 */
@Component
public class ToolDirectory {

    private final ToolService toolService;
    private final PolicyStoreStatus store;
    private final Map<String, ToolResponse> lastKnown = new ConcurrentHashMap<>();

    public ToolDirectory(ToolService toolService, PolicyStoreStatus store) {
        this.toolService = toolService;
        this.store = store;
    }

    public Optional<ToolResponse> findByName(String name) {
        return store.readOrFallback(() -> {
            Optional<ToolResponse> tool = toolService.findByName(name);
            tool.ifPresentOrElse(t -> lastKnown.put(name, t), () -> lastKnown.remove(name));
            return tool;
        }, () -> Optional.ofNullable(lastKnown.get(name)));
    }

    /** Enabled tools by name (the base of the agent tool list). */
    public List<ToolResponse> listEnabled() {
        return store.readOrFallback(() -> {
            List<ToolResponse> tools = toolService.listEnabled();
            tools.forEach(t -> lastKnown.put(t.name(), t));
            return tools;
        }, () -> lastKnown.values().stream()
                .filter(ToolResponse::enabled)
                .sorted(Comparator.comparing(ToolResponse::name))
                .toList());
    }

    @EventListener(ApplicationReadyEvent.class)
    @Scheduled(fixedDelayString = "${agentgate.policy.refresh-interval:5s}",
            initialDelayString = "${agentgate.policy.refresh-interval:5s}")
    void warm() {
        store.readOrFallback(() -> {
            Map<String, ToolResponse> all = toolService.list(Pageable.unpaged()).stream()
                    .collect(Collectors.toMap(ToolResponse::name, t -> t));
            lastKnown.keySet().retainAll(all.keySet());
            lastKnown.putAll(all);
            return null;
        }, () -> null);
    }
}
