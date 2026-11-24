package com.agentgate.gateway.approval;

/**
 * STATE pattern (see docs/patterns.md). Every transition returns the NEXT state or throws.
 * By default every transition is illegal; a state class overrides only the ones it allows.
 * So the terminal states (APPROVED, REJECTED, ...) have no transitions at all: nothing is allowed.
 */
interface ApprovalStateBehavior {

    ApprovalState self();

    default ApprovalState approve() {
        throw illegal("approve");
    }

    default ApprovalState approveWithEdits() {
        throw illegal("approve with edits");
    }

    default ApprovalState reject() {
        throw illegal("reject");
    }

    default ApprovalState expire() {
        throw illegal("expire");
    }

    default ApprovalState cancel() {
        throw illegal("cancel");
    }

    private IllegalApprovalTransitionException illegal(String action) {
        return new IllegalApprovalTransitionException(self(), action);
    }
}
