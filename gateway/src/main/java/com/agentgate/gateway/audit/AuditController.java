package com.agentgate.gateway.audit;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.Writer;
import java.time.Instant;
import java.util.UUID;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Admin API for the audit log. Deliberately read-only: no endpoint can change or delete a record. */
@RestController
@RequestMapping("/api/admin/audit")
@Tag(name = "Audit (admin)")
@SecurityRequirement(name = "adminBasic")
public class AuditController {

    private final AuditVerifier verifier;
    private final AuditSearchService search;
    private final AuditExporter exporter;

    public AuditController(AuditVerifier verifier, AuditSearchService search, AuditExporter exporter) {
        this.verifier = verifier;
        this.search = search;
        this.exporter = exporter;
    }

    @GetMapping
    @Operation(summary = "Search the audit log (newest first)",
            description = "All filters are optional and combined with AND. from/to are ISO-8601 instants, inclusive, "
                    + "e.g. 2026-11-24T10:00:00Z. decision is ALLOWED, DENIED or ERROR.")
    public Page<AuditRecordResponse> search(
            @RequestParam(required = false) String agentId,
            @RequestParam(required = false) String endUserId,
            @RequestParam(required = false) String toolName,
            @RequestParam(required = false) String decision,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @ParameterObject @PageableDefault(size = 20) Pageable pageable) {
        return search.search(new AuditFilter(agentId, endUserId, toolName, decision, from, to), pageable);
    }

    @GetMapping("/export")
    @Operation(summary = "Export the matching records (oldest first) as CSV or JSON",
            description = "Same filters as the search. format=csv (default) or format=json. Sent as a file download.")
    public void export(
            @RequestParam(defaultValue = "csv") String format,
            @RequestParam(required = false) String agentId,
            @RequestParam(required = false) String endUserId,
            @RequestParam(required = false) String toolName,
            @RequestParam(required = false) String decision,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            HttpServletResponse response) throws IOException {
        boolean csv = format.equalsIgnoreCase("csv");
        if (!csv && !format.equalsIgnoreCase("json")) {
            throw new AuditBadRequestException("format must be 'csv' or 'json'");
        }
        AuditFilter filter = new AuditFilter(agentId, endUserId, toolName, decision, from, to);

        response.setStatus(200);
        response.setCharacterEncoding("UTF-8");
        response.setContentType(csv ? "text/csv" : MediaType.APPLICATION_JSON_VALUE);
        response.setHeader("Content-Disposition", "attachment; filename=\"audit-export." + (csv ? "csv" : "json") + "\"");
        Writer out = response.getWriter();
        AuditExporter.RecordWriter writer = csv ? exporter.csv(out) : exporter.json(out);
        search.export(filter, writer::write);
        writer.finish();
        out.flush();
    }

    @GetMapping("/{callId}")
    @Operation(summary = "One full audit record, found by the call id (the X-Call-Id response header)")
    public AuditRecordResponse get(@PathVariable UUID callId) {
        return search.get(callId);
    }

    @GetMapping("/verify")
    @Operation(summary = "Recompute the hash chain",
            description = "Returns status OK with the record count, or BROKEN with the first sequence number that "
                    + "does not match. Always HTTP 200: the answer is in 'status'.")
    public AuditVerification verify() {
        return verifier.verify();
    }
}
