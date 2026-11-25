package com.agentgate.gateway.limits;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import com.agentgate.gateway.shared.failsafe.PolicyStoreState;
import com.agentgate.gateway.shared.failsafe.PolicyStoreStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Stores alerts in PostgreSQL. It is an OBSERVER of {@link LimitBreachedEvent}: LimitStep does not know that
 * alerts exist. If the database is down, the agent still gets its 429; only the stored alert is lost (logged).
 */
@Service
public class AlertService {

    private static final Logger log = LoggerFactory.getLogger(AlertService.class);

    private final AlertRepository repository;
    private final PolicyStoreStatus store;

    public AlertService(AlertRepository repository, PolicyStoreStatus store) {
        this.store = store;
        this.repository = repository;
    }

    @EventListener
    public void onLimitBreached(LimitBreachedEvent event) {
        String message = "Limit '" + event.limitId() + "' (max " + event.max() + ") breached by agent '"
                + event.agentId() + "' on tool '" + event.toolName() + "'";
        if (store.state() == PolicyStoreState.STALE) {
            // QA4: do not make the agent's call wait for a database that is down. The log line is the alert.
            log.error("ALERT (database down, not stored): {}", message);
            return;
        }
        try {
            repository.save(new Alert(AlertType.LIMIT_BREACH, event.agentId(), event.toolName(), message,
                    event.occurredAt()));
        } catch (RuntimeException e) {   // also transaction errors, which are not DataAccessExceptions
            if (PolicyStoreStatus.isStoreFailure(e)) {
                store.markUnreachable(e);
            }
            log.error("Could not store alert: {}", message, e);
        }
    }

    @Transactional(readOnly = true)
    public Page<AlertResponse> list(Pageable pageable) {
        return repository.findAll(pageable).map(AlertResponse::from);
    }
}
