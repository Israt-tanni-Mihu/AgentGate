package com.agentgate.gateway.audit;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Admin API for the audit log. Deliberately read-only: no endpoint can change or delete a record. */
@RestController
@RequestMapping("/api/admin/audit")
@Tag(name = "Audit (admin)")
@SecurityRequirement(name = "adminBasic")
public class AuditController {

    private final AuditVerifier verifier;

    public AuditController(AuditVerifier verifier) {
        this.verifier = verifier;
    }

    @GetMapping("/verify")
    @Operation(summary = "Recompute the hash chain",
            description = "Returns status OK with the record count, or BROKEN with the first sequence number that "
                    + "does not match. Always HTTP 200: the answer is in 'status'.")
    public AuditVerification verify() {
        return verifier.verify();
    }
}
