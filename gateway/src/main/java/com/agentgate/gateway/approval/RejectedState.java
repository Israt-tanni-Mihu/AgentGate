package com.agentgate.gateway.approval;

/** Final: an approver said no. No transition is allowed. */
final class RejectedState implements ApprovalStateBehavior {

    @Override
    public ApprovalState self() {
        return ApprovalState.REJECTED;
    }
}
