package com.agentgate.gateway.pipeline;

import com.agentgate.gateway.identity.CallContext;
import com.agentgate.gateway.identity.CallContextArgumentResolver;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/** The one door agents use to call any tool (plain REST today; the MCP endpoint will reuse CallPipeline). */
@RestController
@RequestMapping("/api/agent/calls")
@Tag(name = "Tool calls (agent)")
@SecurityRequirement(name = "agentToken")
public class CallController {

    private final CallPipeline pipeline;
    private final ObjectMapper objectMapper;

    public CallController(CallPipeline pipeline, ObjectMapper objectMapper) {
        this.pipeline = pipeline;
        this.objectMapper = objectMapper;
    }

    @PostMapping("/{toolName}")
    @Operation(summary = "Call a tool through the gateway",
            description = "Body = the tool's arguments as a JSON object, e.g. {\"orderId\": 1001, \"amount\": 1500, "
                    + "\"reason\": \"damaged\"}. Denied calls return 403 with the deciding policy rule.")
    @Parameter(name = CallContextArgumentResolver.ON_BEHALF_OF_HEADER, in = ParameterIn.HEADER, required = false,
            description = "Id of the end user the agent acts for. For ShopBD this is the customer id, e.g. 1",
            example = "1")
    public ResponseEntity<JsonNode> call(@PathVariable String toolName,
                                         @RequestBody(required = false) JsonNode arguments,
                                         @Parameter(hidden = true) CallContext callContext) {
        CallOutcome outcome = pipeline.execute(toolName, asObject(arguments), callContext);
        return switch (outcome) {
            case CallOutcome.Completed completed -> ResponseEntity.status(completed.status()).body(completed.body());
            case CallOutcome.Denied denied -> throw new CallDeniedException(toolName, denied);
        };
    }

    private ObjectNode asObject(JsonNode arguments) {
        if (arguments == null || arguments.isNull() || arguments.isMissingNode()) {
            return objectMapper.createObjectNode();
        }
        if (!arguments.isObject()) {
            throw new InvalidArgumentsException("The request body must be a JSON object of arguments");
        }
        return (ObjectNode) arguments;
    }
}
