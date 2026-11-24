package com.agentgate.gateway.approval;

/** Final: cancelled by the kill switch. No transition is allowed. */
final class CancelledState implements ApprovalStateBehavior {

    @Override
    public ApprovalState self() {
        return ApprovalState.CANCELLED;
    }
}
