package com.agentgate.gateway.identity;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Proof that FR2 works: echoes back who the gateway thinks is calling. */
@RestController
@RequestMapping("/api/agent")
@Tag(name = "Agent identity")
@SecurityRequirement(name = "agentToken")
public class WhoAmIController {

    @GetMapping("/whoami")
    @Operation(summary = "Show the calling agent and the end user it acts for")
    @Parameter(name = CallContextArgumentResolver.ON_BEHALF_OF_HEADER, in = ParameterIn.HEADER, required = false,
            description = "Id of the end user the agent acts for, e.g. cust-1001", example = "cust-1001")
    public CallContext whoAmI(@Parameter(hidden = true) CallContext callContext) {
        return callContext;
    }
}
