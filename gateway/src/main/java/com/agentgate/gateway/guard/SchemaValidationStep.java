package com.agentgate.gateway.guard;

import com.agentgate.gateway.pipeline.CallChain;
import com.agentgate.gateway.pipeline.CallOutcome;
import com.agentgate.gateway.pipeline.CallStep;
import com.agentgate.gateway.pipeline.CallStepOrder;
import com.agentgate.gateway.pipeline.ToolCall;
import com.networknt.schema.Error;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * FR5 guard, runs BEFORE the policy step. Checks the arguments against the tool's JSON Schema:
 * wrong types, missing required fields, and UNKNOWN extra fields (argument smuggling: a field the policy
 * does not look at but the downstream tool might use, e.g. a query parameter on a GET).
 *
 * Why before the policy: policies only make sense for well-formed calls, and a malformed call should
 * fail with a clear 400 instead of a confusing policy denial.
 */
@Component
@Order(CallStepOrder.SCHEMA)
public class SchemaValidationStep implements CallStep {

    /** The importer already writes this keyword; we report unknown fields ourselves, so skip its duplicate. */
    private static final String ADDITIONAL_PROPERTIES = "additionalProperties";

    private final SchemaRegistry registry = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12);
    /** Compiled schemas, keyed by the schema text. Compiling is slow, so each schema is compiled once. */
    private final Map<String, Schema> compiled = new ConcurrentHashMap<>();

    @Override
    public CallOutcome handle(ToolCall call, CallChain chain) {
        List<String> problems = problemsWith(call.tool().inputSchema(), call.arguments());
        if (!problems.isEmpty()) {
            throw new SchemaViolationException(call.toolName(), problems);
        }
        return chain.proceed(call);
    }

    private List<String> problemsWith(JsonNode inputSchema, JsonNode arguments) {
        List<String> problems = new ArrayList<>();

        // Unknown fields: every argument must be declared under "properties".
        JsonNode declared = inputSchema.path("properties");
        for (Map.Entry<String, JsonNode> argument : arguments.properties()) {
            if (!declared.has(argument.getKey())) {
                problems.add("unknown argument '" + argument.getKey() + "' (not in the tool's schema)");
            }
        }

        // Types and required fields: the JSON Schema library does the work.
        Schema schema = compiled.computeIfAbsent(inputSchema.toString(), text -> registry.getSchema(inputSchema));
        for (Error error : schema.validate(arguments)) {
            if (!ADDITIONAL_PROPERTIES.equals(error.getKeyword())) {
                // Prefix with the JSON location, e.g. "$.amount: string found, number expected".
                problems.add(error.getInstanceLocation() + ": " + error.getMessage());
            }
        }
        return problems;
    }
}
