package com.agentgate.gateway.approval;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The approver's API (role APPROVER, HTTP Basic). An agent token cannot reach it: see SecurityConfig.
 * POSTs require a JSON content type, so a hidden cross-site HTML form cannot trigger an approval.
 */
@RestController
@RequestMapping("/api/approvals")
@Tag(name = "Approvals (approver)")
public class ApprovalController {

    private final ApprovalService service;

    public ApprovalController(ApprovalService service) {
        this.service = service;
    }

    @GetMapping
    @Operation(summary = "List approval requests (newest first), optionally only one state, e.g. ?state=PENDING")
    public Page<ApprovalResponse> list(@RequestParam(required = false) ApprovalState state,
                                       @PageableDefault(size = 20, sort = "createdAt",
                                               direction = org.springframework.data.domain.Sort.Direction.DESC)
                                       Pageable pageable) {
        return service.list(state, pageable);
    }

    @GetMapping("/{id}")
    public ApprovalResponse get(@PathVariable UUID id) {
        return service.get(id);
    }

    @PostMapping(value = "/{id}/approve", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Approve as sent. The body is ignored; send {}")
    public ApprovalResponse approve(@PathVariable UUID id, Authentication approver) {
        return service.approve(id, approver.getName());
    }

    @PostMapping(value = "/{id}/reject", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ApprovalResponse reject(@PathVariable UUID id, @Valid @RequestBody RejectRequest body,
                                   Authentication approver) {
        return service.reject(id, approver.getName(), body.reason());
    }

    @PostMapping(value = "/{id}/edit", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Approve with changed arguments",
            description = "The new arguments go through the schema check and the policy again before the call is forwarded.")
    public ApprovalResponse edit(@PathVariable UUID id, @Valid @RequestBody EditRequest body, Authentication approver) {
        return service.approveWithEdits(id, approver.getName(), body.arguments());
    }
}
