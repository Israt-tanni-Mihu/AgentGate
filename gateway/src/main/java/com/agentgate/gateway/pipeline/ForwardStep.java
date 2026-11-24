package com.agentgate.gateway.pipeline;

import com.agentgate.gateway.registry.ToolKind;
import com.agentgate.gateway.registry.ToolResponse;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Last step: calls the REST tool and returns its result.
 *
 * Arguments are mapped like this (tool GET /api/orders/{id}, arguments {"id": 1001}):
 * path variables come from the argument with the same name; for GET/DELETE the remaining arguments become
 * query parameters; for POST/PUT/PATCH they become the JSON body.
 */
@Component
@Order(CallStepOrder.FORWARD)
public class ForwardStep implements CallStep {

    private static final Pattern PATH_VARIABLE = Pattern.compile("\\{([^}/]+)}");
    private static final Set<String> METHODS_WITHOUT_BODY = Set.of("GET", "DELETE");

    private final RestClient restClient;
    private final ObjectMapper objectMapper;

    public ForwardStep(RestClient.Builder builder, ObjectMapper objectMapper) {
        // Simple fixed timeouts for now; per-tool timeouts and circuit breakers come with FR10.
        HttpClient httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(Duration.ofSeconds(10));
        this.restClient = builder.requestFactory(requestFactory).build();
        this.objectMapper = objectMapper;
    }

    @Override
    public CallOutcome handle(ToolCall call, CallChain chain) {
        ToolResponse tool = call.tool();
        if (tool.kind() != ToolKind.REST) {
            throw new ToolNotForwardableException(tool.name());
        }
        String method = tool.httpMethod();
        ObjectNode remaining = call.arguments().deepCopy();
        Map<String, Object> uriVariables = new HashMap<>();

        // 1. Fill path variables, e.g. {id}, and remove them from the remaining arguments.
        Matcher matcher = PATH_VARIABLE.matcher(tool.path());
        while (matcher.find()) {
            String name = matcher.group(1);
            JsonNode value = remaining.remove(name);
            if (value == null || value.isNull() || !value.isValueNode()) {
                throw new InvalidArgumentsException("Argument '" + name + "' is required and must be a simple value");
            }
            uriVariables.put(name, value.asString());
        }

        // 2. Remaining arguments → query parameters (GET/DELETE) or JSON body (others).
        UriComponentsBuilder uri = UriComponentsBuilder.fromUriString(tool.baseUrl() + tool.path());
        boolean withoutBody = METHODS_WITHOUT_BODY.contains(method);
        if (withoutBody) {
            for (Map.Entry<String, JsonNode> argument : remaining.properties()) {
                if (!argument.getValue().isValueNode()) {
                    throw new InvalidArgumentsException("Argument '" + argument.getKey() + "' must be a simple value");
                }
                String variable = "query_" + uriVariables.size();
                uri.queryParam(argument.getKey(), "{" + variable + "}");
                uriVariables.put(variable, argument.getValue().asString());
            }
        }
        // encode() BEFORE expanding = values are fully escaped, so "1/../admin" becomes "1%2F..%2Fadmin"
        // and cannot change the path (argument smuggling, QA1).
        URI target = uri.encode().buildAndExpand(uriVariables).toUri();

        try {
            RestClient.RequestBodySpec request = restClient.method(HttpMethod.valueOf(method)).uri(target)
                    .accept(MediaType.APPLICATION_JSON);
            if (!withoutBody) {
                request.contentType(MediaType.APPLICATION_JSON).body(remaining);
            }
            CallOutcome.Completed outcome = request.exchange((req, response) ->
                    new CallOutcome.Completed(response.getStatusCode().value(), readJson(response.getBody())));
            if (outcome.status() >= 500) {
                throw new ToolCallFailedException(tool.name(), "the tool answered HTTP " + outcome.status());
            }
            return outcome;
        } catch (ResourceAccessException e) {
            throw new ToolCallFailedException(tool.name(), "the tool could not be reached");
        }
    }

    /** Empty body → null; JSON → parsed; anything else → returned as a JSON string. */
    private JsonNode readJson(InputStream body) throws IOException {
        byte[] bytes = body.readAllBytes();
        if (bytes.length == 0) {
            return null;
        }
        try {
            return objectMapper.readTree(bytes);
        } catch (tools.jackson.core.JacksonException e) {
            return objectMapper.getNodeFactory().stringNode(new String(bytes, java.nio.charset.StandardCharsets.UTF_8));
        }
    }
}
