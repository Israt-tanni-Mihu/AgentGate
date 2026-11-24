package com.agentgate.gateway.playground;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** FR9 policy playground (admin only, see SecurityConfig: /api/admin/** needs the ADMIN role). */
@RestController
@Tag(name = "Policy playground (admin)")
@SecurityRequirement(name = "adminBasic")
public class PolicyPlaygroundController {

    private final PolicyPlaygroundService service;

    public PolicyPlaygroundController(PolicyPlaygroundService service) {
        this.service = service;
    }

    @PostMapping("/api/admin/policies/playground")
    @Operation(summary = "Test a sample call against the active or a draft policy version",
            description = "Shows ALLOW/DENY, the deciding rule and what happened to every rule. "
                    + "Nothing is forwarded to the tool and nothing is changed.")
    public PlaygroundResponse test(@Valid @RequestBody PlaygroundRequest request) {
        return service.test(request);
    }
}
