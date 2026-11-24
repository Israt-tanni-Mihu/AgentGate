package com.agentgate.gateway.registry.importer;

import com.agentgate.gateway.registry.RiskLevel;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Adapter pattern — the "Adapter" for OpenAPI 3.x JSON documents.
 *
 * Each operation (path + HTTP method) becomes one {@link ToolDefinition}:
 * operationId → name, description (or summary) → description, path/query parameters + JSON request body
 * → one flat JSON Schema object, x-risk-level → suggested risk level (MEDIUM if missing).
 */
@Component
public class OpenApiToolSpecAdapter implements ToolSpecAdapter {

    private static final List<String> HTTP_METHODS = List.of("get", "post", "put", "patch", "delete");
    private static final String VALID_NAME = "^[A-Za-z][A-Za-z0-9_-]{0,63}$";
    private static final int MAX_DESCRIPTION = 2000;

    private final ObjectMapper objectMapper;

    public OpenApiToolSpecAdapter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public String formatName() {
        return "openapi-3";
    }

    @Override
    public boolean supports(String specContent) {
        JsonNode spec = parseOrNull(specContent);
        return spec != null && spec.path("openapi").asString("").startsWith("3.");
    }

    @Override
    public AdaptedSpec adapt(String specContent) {
        JsonNode spec = parseOrNull(specContent);
        if (spec == null) {
            throw new IllegalArgumentException("Spec is not valid JSON");
        }
        List<ToolDefinition> tools = new ArrayList<>();
        List<String> skipped = new ArrayList<>();

        for (Map.Entry<String, JsonNode> pathEntry : spec.path("paths").properties()) {
            String path = pathEntry.getKey();
            JsonNode pathItem = pathEntry.getValue();
            for (String method : HTTP_METHODS) {
                JsonNode operation = pathItem.get(method);
                if (operation == null) {
                    continue;
                }
                String label = method.toUpperCase(Locale.ROOT) + " " + path;
                try {
                    tools.add(toToolDefinition(spec, path, method, pathItem, operation));
                } catch (IllegalArgumentException e) {
                    skipped.add(label + ": " + e.getMessage());
                }
            }
        }
        return new AdaptedSpec(tools, skipped);
    }

    private ToolDefinition toToolDefinition(JsonNode spec, String path, String method,
                                            JsonNode pathItem, JsonNode operation) {
        String name = operation.path("operationId").asString("");
        if (name.isBlank()) {
            throw new IllegalArgumentException("no operationId");
        }
        if (!name.matches(VALID_NAME)) {
            throw new IllegalArgumentException("operationId '" + name + "' is not a valid tool name");
        }
        return new ToolDefinition(
                name,
                describe(operation, method, path),
                buildInputSchema(spec, pathItem, operation),
                riskLevelOf(operation),
                method.toUpperCase(Locale.ROOT),
                path);
    }

    private String describe(JsonNode operation, String method, String path) {
        String text = operation.path("description").asString("");
        if (text.isBlank()) {
            text = operation.path("summary").asString("");
        }
        if (text.isBlank()) {
            text = method.toUpperCase(Locale.ROOT) + " " + path;
        }
        return text.length() > MAX_DESCRIPTION ? text.substring(0, MAX_DESCRIPTION) : text;
    }

    private RiskLevel riskLevelOf(JsonNode operation) {
        String value = operation.path("x-risk-level").asString("").toUpperCase(Locale.ROOT);
        return switch (value) {
            case "LOW" -> RiskLevel.LOW;
            case "HIGH" -> RiskLevel.HIGH;
            default -> RiskLevel.MEDIUM;   // missing or unknown value → the safe middle
        };
    }

    /**
     * Builds one flat object schema that an agent fills in, e.g.
     * {"type":"object","properties":{"id":{...}},"required":["id"],"additionalProperties":false}.
     */
    private ObjectNode buildInputSchema(JsonNode spec, JsonNode pathItem, JsonNode operation) {
        ObjectNode properties = objectMapper.createObjectNode();
        Set<String> required = new LinkedHashSet<>();

        // 1. Parameters: path-level ones first, operation-level ones override them (same name + location).
        Map<String, JsonNode> parameters = new LinkedHashMap<>();
        for (JsonNode source : List.of(pathItem.path("parameters"), operation.path("parameters"))) {
            for (JsonNode rawParameter : source) {
                JsonNode parameter = resolve(spec, rawParameter);
                parameters.put(parameter.path("in").asString("") + ":" + parameter.path("name").asString(""),
                        parameter);
            }
        }
        for (JsonNode parameter : parameters.values()) {
            String location = parameter.path("in").asString("");
            if (!location.equals("path") && !location.equals("query")) {
                continue;   // headers/cookies (e.g. auth) are the gateway's job, not the agent's
            }
            String name = parameter.path("name").asString("");
            ObjectNode schema = copyAsObject(resolve(spec, parameter.path("schema")));
            if (parameter.has("description") && !schema.has("description")) {
                schema.put("description", parameter.get("description").asString());
            }
            properties.set(name, schema);
            if (location.equals("path") || parameter.path("required").asBoolean(false)) {
                required.add(name);
            }
        }

        // 2. JSON request body: its properties are merged into the same flat object.
        JsonNode requestBody = resolve(spec, operation.path("requestBody"));
        if (!requestBody.isMissingNode()) {
            JsonNode bodySchema = resolve(spec, jsonContentSchema(requestBody));
            boolean bodyRequired = requestBody.path("required").asBoolean(false);
            if (bodySchema.path("properties").isObject()) {
                for (Map.Entry<String, JsonNode> property : bodySchema.path("properties").properties()) {
                    if (properties.has(property.getKey())) {
                        throw new IllegalArgumentException(
                                "body field '" + property.getKey() + "' clashes with a parameter of the same name");
                    }
                    properties.set(property.getKey(), property.getValue());
                }
                if (bodyRequired) {
                    bodySchema.path("required").forEach(field -> required.add(field.asString()));
                }
            } else {
                // Not an object (e.g. an array): expose it as a single "body" argument.
                properties.set("body", bodySchema);
                if (bodyRequired) {
                    required.add("body");
                }
            }
        }

        ObjectNode inputSchema = objectMapper.createObjectNode();
        inputSchema.put("type", "object");
        inputSchema.set("properties", properties);
        if (!required.isEmpty()) {
            ArrayNode requiredArray = inputSchema.putArray("required");
            required.forEach(requiredArray::add);
        }
        // Unknown arguments are rejected later by schema validation (FR5) → no argument smuggling (QA1).
        inputSchema.put("additionalProperties", false);
        return inputSchema;
    }

    private JsonNode jsonContentSchema(JsonNode requestBody) {
        JsonNode content = requestBody.path("content");
        if (content.has("application/json")) {
            return content.get("application/json").path("schema");
        }
        for (Map.Entry<String, JsonNode> entry : content.properties()) {
            if (entry.getKey().contains("json")) {
                return entry.getValue().path("schema");
            }
        }
        throw new IllegalArgumentException("request body is not JSON");
    }

    /** Replaces local "$ref"s (e.g. #/components/schemas/RefundRequest) with the referenced JSON, recursively. */
    private JsonNode resolve(JsonNode spec, JsonNode node) {
        return resolve(spec, node, new HashSet<>());
    }

    private JsonNode resolve(JsonNode spec, JsonNode node, Set<String> refsInProgress) {
        if (node.isArray()) {
            ArrayNode copy = objectMapper.createArrayNode();
            node.forEach(element -> copy.add(resolve(spec, element, refsInProgress)));
            return copy;
        }
        if (!node.isObject()) {
            return node;
        }
        if (node.has("$ref")) {
            String ref = node.get("$ref").asString("");
            if (!ref.startsWith("#/")) {
                throw new IllegalArgumentException("external $ref '" + ref + "' is not supported");
            }
            if (refsInProgress.contains(ref)) {
                return objectMapper.createObjectNode().put("type", "object");   // cut a recursive schema
            }
            JsonNode target = spec.at(ref.substring(1));
            if (target.isMissingNode()) {
                throw new IllegalArgumentException("$ref '" + ref + "' points to nothing");
            }
            refsInProgress.add(ref);
            JsonNode resolved = resolve(spec, target, refsInProgress);
            refsInProgress.remove(ref);
            return resolved;
        }
        ObjectNode copy = objectMapper.createObjectNode();
        for (Map.Entry<String, JsonNode> field : node.properties()) {
            copy.set(field.getKey(), resolve(spec, field.getValue(), refsInProgress));
        }
        return copy;
    }

    private ObjectNode copyAsObject(JsonNode node) {
        return node.isObject() ? (ObjectNode) node.deepCopy() : objectMapper.createObjectNode();
    }

    private JsonNode parseOrNull(String content) {
        try {
            JsonNode node = objectMapper.readTree(content);
            return node != null && node.isObject() ? node : null;
        } catch (JacksonException e) {
            return null;
        }
    }
}
