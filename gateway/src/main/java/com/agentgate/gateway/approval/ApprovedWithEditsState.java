package com.agentgate.gateway.approval;

/** Final: approved with changed arguments (re-validated before forwarding). No transition is allowed. */
final class ApprovedWithEditsState implements ApprovalStateBehavior {

    @Override
    public ApprovalState self() {
        return ApprovalState.APPROVED_WITH_EDITS;
    }
}
