package com.agentgate.gateway.approval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.http.HttpStatus;

/** FR4 lifecycle (STATE pattern) without Spring or a database. */
class ApprovalStateTest {

    private static final Instant NOW = Instant.parse("2026-11-24T10:00:00Z");

    private static ApprovalRequest pending() {
        return new ApprovalRequest(UUID.randomUUID(), "support-bot", "1", "issueRefund", "{}", NOW, NOW.plusSeconds(120));
    }

    @Test
    void pendingCanMoveToEveryOtherState() {
        ApprovalRequest approved = pending();
        approved.approve("approver", NOW);
        assertThat(approved.getState()).isEqualTo(ApprovalState.APPROVED);
        assertThat(approved.getApprover()).isEqualTo("approver");
        assertThat(approved.getDecidedAt()).isEqualTo(NOW);

        ApprovalRequest edited = pending();
        edited.approveWithEdits("approver", "{\"amount\":10}", NOW);
        assertThat(edited.getState()).isEqualTo(ApprovalState.APPROVED_WITH_EDITS);
        assertThat(edited.getEditedArguments()).isEqualTo("{\"amount\":10}");

        ApprovalRequest rejected = pending();
        rejected.reject("approver", "too risky", NOW);
        assertThat(rejected.getState()).isEqualTo(ApprovalState.REJECTED);
        assertThat(rejected.getReason()).isEqualTo("too risky");

        ApprovalRequest expired = pending();
        expired.expire(NOW);
        assertThat(expired.getState()).isEqualTo(ApprovalState.EXPIRED);

        ApprovalRequest cancelled = pending();
        cancelled.cancel("kill switch", NOW);
        assertThat(cancelled.getState()).isEqualTo(ApprovalState.CANCELLED);
    }

    static Stream<ApprovalState> finalStates() {
        return Stream.of(ApprovalState.APPROVED, ApprovalState.APPROVED_WITH_EDITS, ApprovalState.REJECTED,
                ApprovalState.EXPIRED, ApprovalState.CANCELLED);
    }

    /** Every final state refuses every transition: no way back to PENDING, no second decision. */
    @ParameterizedTest
    @MethodSource("finalStates")
    void finalStatesRefuseEveryTransitionWith409(ApprovalState finalState) {
        ApprovalRequest request = pending();
        moveTo(request, finalState);

        for (Consumer<ApprovalRequest> action : java.util.List.<Consumer<ApprovalRequest>>of(
                r -> r.approve("a", NOW),
                r -> r.approveWithEdits("a", "{}", NOW),
                r -> r.reject("a", "no", NOW),
                r -> r.expire(NOW),
                r -> r.cancel("why", NOW))) {
            assertThatThrownBy(() -> action.accept(request))
                    .isInstanceOf(IllegalApprovalTransitionException.class)
                    .satisfies(e -> assertThat(((IllegalApprovalTransitionException) e).getStatusCode())
                            .isEqualTo(HttpStatus.CONFLICT));
            assertThat(request.getState()).isEqualTo(finalState);   // unchanged
        }
    }

    @Test
    void approvingAnExpiredRequestIsRefused() {
        ApprovalRequest request = pending();
        request.expire(NOW);
        assertThatThrownBy(() -> request.approve("approver", NOW))
                .isInstanceOf(IllegalApprovalTransitionException.class)
                .hasMessageContaining("EXPIRED");
    }

    private static void moveTo(ApprovalRequest request, ApprovalState target) {
        switch (target) {
            case APPROVED -> request.approve("a", NOW);
            case APPROVED_WITH_EDITS -> request.approveWithEdits("a", "{}", NOW);
            case REJECTED -> request.reject("a", "no", NOW);
            case EXPIRED -> request.expire(NOW);
            case CANCELLED -> request.cancel("why", NOW);
            case PENDING -> { }
        }
    }
}
