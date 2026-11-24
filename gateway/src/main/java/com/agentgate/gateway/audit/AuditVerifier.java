package com.agentgate.gateway.audit;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Recomputes the whole chain from the stored rows (plain SQL, not JPA, so nothing comes from a cache).
 * A row is fine when (1) its seq follows the previous one, (2) its prev_hash is the previous row's hash and
 * (3) its hash is what the hash rule gives for its stored values.
 */
@Service
public class AuditVerifier {

    private final JdbcTemplate jdbc;
    private final AuditWriter writer;

    public AuditVerifier(JdbcTemplate jdbc, AuditWriter writer) {
        this.jdbc = jdbc;
        this.writer = writer;
    }

    @Transactional(readOnly = true)
    public AuditVerification verify() {
        writer.flush();   // include calls that finished a moment ago and are still in the writer's queue
        return jdbc.query(con -> {
            var statement = con.prepareStatement("SELECT * FROM audit_records ORDER BY seq");
            statement.setFetchSize(1000);   // read in pieces, not the whole table at once
            return statement;
        }, this::walk);
    }

    private AuditVerification walk(ResultSet rows) throws SQLException {
        String expectedPrev = AuditHasher.GENESIS_HASH;
        long expectedSeq = 1;
        long checked = 0;
        while (rows.next()) {
            checked++;
            long seq = rows.getLong("seq");
            if (seq != expectedSeq) {
                return AuditVerification.broken(checked, seq,
                        "expected sequence number " + expectedSeq + " (a record is missing)");
            }
            if (!expectedPrev.equals(rows.getString("prev_hash"))) {
                return AuditVerification.broken(checked, seq, "prev_hash does not match the previous record");
            }
            String recomputed = AuditHasher.hash(expectedPrev, fieldsOf(rows));
            if (!recomputed.equals(rows.getString("hash"))) {
                return AuditVerification.broken(checked, seq, "the stored values do not match the record's hash");
            }
            expectedPrev = rows.getString("hash");
            expectedSeq++;
        }
        return AuditVerification.ok(checked);
    }

    private static AuditFields fieldsOf(ResultSet r) throws SQLException {
        return new AuditFields(r.getLong("seq"), r.getObject("call_id", UUID.class),
                r.getObject("occurred_at", OffsetDateTime.class).toInstant(),
                r.getString("agent_id"), r.getString("end_user_id"), r.getString("tool_name"),
                r.getString("arguments"), r.getString("decision"), r.getString("decided_by_step"),
                r.getString("rule_id"), (Integer) r.getObject("policy_version"),
                r.getObject("approval_id", UUID.class), r.getString("approval_state"), r.getString("approver"),
                r.getString("edited_arguments"), r.getLong("latency_ms"), r.getInt("http_status"),
                r.getString("reason"));
    }
}
