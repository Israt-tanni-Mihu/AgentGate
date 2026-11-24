package com.agentgate.gateway.approval;

/** Final: nobody answered in time. No transition is allowed. */
final class ExpiredState implements ApprovalStateBehavior {

    @Override
    public ApprovalState self() {
        return ApprovalState.EXPIRED;
    }
}
