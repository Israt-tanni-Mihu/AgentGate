package com.agentgate.gateway.audit;

import io.swagger.v3.oas.annotations.media.Schema;

/** Result of walking the whole chain. {@code firstBrokenSeq} and {@code problem} are only set when status is BROKEN. */
public record AuditVerification(
        @Schema(description = "OK = every record matches; BROKEN = something was changed, removed or inserted")
        Status status,
        @Schema(description = "Records that were checked (up to and including the first broken one)")
        long records,
        @Schema(description = "Sequence number of the first record that does not match")
        Long firstBrokenSeq,
        String problem) {

    public enum Status { OK, BROKEN }

    static AuditVerification ok(long records) {
        return new AuditVerification(Status.OK, records, null, null);
    }

    static AuditVerification broken(long records, long seq, String problem) {
        return new AuditVerification(Status.BROKEN, records, seq, problem);
    }
}
