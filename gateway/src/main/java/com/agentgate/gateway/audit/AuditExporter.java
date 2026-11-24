package com.agentgate.gateway.audit;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.util.List;
import java.util.function.Function;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/** Writes audit records as CSV or as a JSON array, one record at a time (so the export can be large). */
@Component
public class AuditExporter {

    private record Column(String header, Function<AuditRecordResponse, Object> value) {
    }

    private static final List<Column> COLUMNS = List.of(
            new Column("seq", AuditRecordResponse::seq),
            new Column("callId", AuditRecordResponse::callId),
            new Column("occurredAt", AuditRecordResponse::occurredAt),
            new Column("agentId", AuditRecordResponse::agentId),
            new Column("endUserId", AuditRecordResponse::endUserId),
            new Column("toolName", AuditRecordResponse::toolName),
            new Column("arguments", AuditRecordResponse::arguments),
            new Column("decision", AuditRecordResponse::decision),
            new Column("decidedByStep", AuditRecordResponse::decidedByStep),
            new Column("ruleId", AuditRecordResponse::ruleId),
            new Column("policyVersion", AuditRecordResponse::policyVersion),
            new Column("approvalId", AuditRecordResponse::approvalId),
            new Column("approvalState", AuditRecordResponse::approvalState),
            new Column("approver", AuditRecordResponse::approver),
            new Column("editedArguments", AuditRecordResponse::editedArguments),
            new Column("latencyMs", AuditRecordResponse::latencyMs),
            new Column("httpStatus", AuditRecordResponse::httpStatus),
            new Column("reason", AuditRecordResponse::reason),
            new Column("prevHash", AuditRecordResponse::prevHash),
            new Column("hash", AuditRecordResponse::hash));

    private final ObjectMapper objectMapper;

    public AuditExporter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /** Returns the sink that writes each record; call {@code finish} afterwards. */
    public RecordWriter csv(Writer out) {
        return new RecordWriter() {
            private boolean started;

            @Override
            public void write(AuditRecordResponse r) {
                if (!started) {
                    line(out, COLUMNS.stream().map(Column::header).toList());
                    started = true;
                }
                line(out, COLUMNS.stream().map(c -> c.value().apply(r)).map(AuditExporter::text).toList());
            }

            @Override
            public void finish() {
                if (!started) {
                    line(out, COLUMNS.stream().map(Column::header).toList());   // empty result: header only
                }
            }
        };
    }

    public RecordWriter json(Writer out) {
        return new RecordWriter() {
            private boolean first = true;

            @Override
            public void write(AuditRecordResponse r) {
                put(out, first ? "[" : ",");
                put(out, objectMapper.writeValueAsString(r));
                first = false;
            }

            @Override
            public void finish() {
                put(out, first ? "[]" : "]");
            }
        };
    }

    public interface RecordWriter {
        void write(AuditRecordResponse record);

        void finish();
    }

    private static String text(Object value) {
        return value == null ? "" : value.toString();
    }

    private static void line(Writer out, List<String> cells) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < cells.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(cell(cells.get(i)));
        }
        put(out, sb.append("\r\n").toString());
    }

    /**
     * RFC 4180 quoting. A cell that starts with = + - @ would be run as a formula by Excel (CSV injection), and
     * the log holds text an agent chose, so such cells get a leading apostrophe.
     */
    private static String cell(String value) {
        String safe = !value.isEmpty() && "=+-@\t\r".indexOf(value.charAt(0)) >= 0 ? "'" + value : value;
        if (safe.contains(",") || safe.contains("\"") || safe.contains("\n") || safe.contains("\r")) {
            return "\"" + safe.replace("\"", "\"\"") + "\"";
        }
        return safe;
    }

    private static void put(Writer out, String s) {
        try {
            out.write(s);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
