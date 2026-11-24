package com.agentgate.gateway.approval;

/** The only state that can change: waiting for a human. Every decision starts here. */
final class PendingState implements ApprovalStateBehavior {

    @Override
    public ApprovalState self() {
        return ApprovalState.PENDING;
    }

    @Override
    public ApprovalState approve() {
        return ApprovalState.APPROVED;
    }

    @Override
    public ApprovalState approveWithEdits() {
        return ApprovalState.APPROVED_WITH_EDITS;
    }

    @Override
    public ApprovalState reject() {
        return ApprovalState.REJECTED;
    }

    @Override
    public ApprovalState expire() {
        return ApprovalState.EXPIRED;
    }

    @Override
    public ApprovalState cancel() {
        return ApprovalState.CANCELLED;
    }
}
