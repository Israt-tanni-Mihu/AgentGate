package com.agentgate.gateway.limits;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.dao.DataAccessException;
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

    public AlertService(AlertRepository repository) {
        this.repository = repository;
    }

    @EventListener
    public void onLimitBreached(LimitBreachedEvent event) {
        String message = "Limit '" + event.limitId() + "' (max " + event.max() + ") breached by agent '"
                + event.agentId() + "' on tool '" + event.toolName() + "'";
        try {
            repository.save(new Alert(AlertType.LIMIT_BREACH, event.agentId(), event.toolName(), message,
                    event.occurredAt()));
        } catch (DataAccessException e) {
            log.error("Could not store alert: {}", message, e);
        }
    }

    @Transactional(readOnly = true)
    public Page<AlertResponse> list(Pageable pageable) {
        return repository.findAll(pageable).map(AlertResponse::from);
    }
}
