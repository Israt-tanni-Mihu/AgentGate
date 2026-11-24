package com.agentgate.gateway.approval;

/**
 * The stored lifecycle state of an approval request. Each value owns a behaviour object (STATE pattern),
 * so "what may happen next" lives in the state's class, not in a switch.
 */
public enum ApprovalState {
    PENDING(new PendingState()),
    APPROVED(new ApprovedState()),
    APPROVED_WITH_EDITS(new ApprovedWithEditsState()),
    REJECTED(new RejectedState()),
    EXPIRED(new ExpiredState()),
    CANCELLED(new CancelledState());

    private final ApprovalStateBehavior behavior;

    ApprovalState(ApprovalStateBehavior behavior) {
        this.behavior = behavior;
    }

    ApprovalStateBehavior behavior() {
        return behavior;
    }
}
