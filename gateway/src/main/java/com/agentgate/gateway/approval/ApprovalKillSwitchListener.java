package com.agentgate.gateway.approval;

import com.agentgate.gateway.admin.KillSwitchActivated;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * OBSERVER of the FR8 kill switch: when a switch goes ON, the pending approvals it covers become CANCELLED.
 * Runs inside the kill-switch transaction; the held agent calls wake up right after it commits.
 */
@Component
class ApprovalKillSwitchListener {

    private static final Logger log = LoggerFactory.getLogger(ApprovalKillSwitchListener.class);

    private final ApprovalService service;

    ApprovalKillSwitchListener(ApprovalService service) {
        this.service = service;
    }

    @EventListener
    void onKillSwitch(KillSwitchActivated event) {
        int cancelled = service.cancelCoveredBy(event.scope(), event.target());
        log.info("Kill switch {} {} cancelled {} pending approval(s)", event.scope(), event.target(), cancelled);
    }
}
