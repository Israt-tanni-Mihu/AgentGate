package com.agentgate.gateway.approval;

/** Final: approved as sent. No transition is allowed. */
final class ApprovedState implements ApprovalStateBehavior {

    @Override
    public ApprovalState self() {
        return ApprovalState.APPROVED;
    }
}
