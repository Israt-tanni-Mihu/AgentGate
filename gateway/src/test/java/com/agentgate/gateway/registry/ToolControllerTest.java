package com.agentgate.gateway.registry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;

import com.agentgate.gateway.registry.importer.ToolImportService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

/**
 * Web slice test: only the controller + error handler start; the services are Mockito mocks.
 * Checks HTTP status codes, Bean Validation and the Problem Details format — no database needed.
 * Security filters are switched off here; security is tested in AgentIdentityIntegrationTest.
 */
@WebMvcTest(ToolController.class)
@AutoConfigureMockMvc(addFilters = false)
class ToolControllerTest {

    @Autowired
    MockMvcTester mvc;

    @MockitoBean
    ToolService toolService;

    @MockitoBean
    ToolImportService importService;

    @Test
    void unknownIdGives404ProblemDetails() {
        given(toolService.get(42L)).willThrow(new ToolNotFoundException(42L));

        assertThat(mvc.get().uri("/api/admin/tools/42"))
                .hasStatus(404)
                .hasContentType(MediaType.APPLICATION_PROBLEM_JSON)
                .bodyJson()
                .hasPathSatisfying("$.title", v -> v.assertThat().isEqualTo("Tool not found"))
                .hasPathSatisfying("$.detail", v -> v.assertThat().isEqualTo("Tool with id 42 not found"));
    }

    @Test
    void duplicateNameGives409ProblemDetails() {
        given(toolService.create(any())).willThrow(new DuplicateToolNameException("getOrder"));

        assertThat(mvc.post().uri("/api/admin/tools").contentType(MediaType.APPLICATION_JSON).content(validBody()))
                .hasStatus(409)
                .hasContentType(MediaType.APPLICATION_PROBLEM_JSON)
                .bodyJson()
                .hasPathSatisfying("$.detail", v -> v.assertThat().isEqualTo("A tool named 'getOrder' already exists"));
    }

    @Test
    void invalidBodyGives400WithFieldErrors() {
        String body = """
                {"name": "1bad name", "description": "", "inputSchema": [1, 2],
                 "riskLevel": "LOW", "ownerTeam": "shop-team", "kind": "REST"}
                """;

        assertThat(mvc.post().uri("/api/admin/tools").contentType(MediaType.APPLICATION_JSON).content(body))
                .hasStatus(400)
                .hasContentType(MediaType.APPLICATION_PROBLEM_JSON)
                .bodyJson()
                .hasPath("$.errors.name")
                .hasPath("$.errors.description")
                .hasPath("$.errors.inputSchemaObject")
                .hasPath("$.errors.targetValidForKind");
    }

    @Test
    void unknownRiskLevelGives400() {
        String body = validBody().replace("\"LOW\"", "\"EXTREME\"");

        assertThat(mvc.post().uri("/api/admin/tools").contentType(MediaType.APPLICATION_JSON).content(body))
                .hasStatus(400)
                .hasContentType(MediaType.APPLICATION_PROBLEM_JSON);
    }

    @Test
    void importRejectsNonHttpSpecUrl() {
        String body = """
                {"specUrl": "file:///etc/passwd", "ownerTeam": "shop-team", "baseUrl": "http://localhost:8081"}
                """;

        assertThat(mvc.post().uri("/api/admin/tools/import").contentType(MediaType.APPLICATION_JSON).content(body))
                .hasStatus(400)
                .bodyJson()
                .hasPath("$.errors.specUrl");
    }

    static String validBody() {
        return """
                {"name": "getOrder", "description": "Get an order by id",
                 "inputSchema": {"type": "object", "properties": {"id": {"type": "integer"}}, "required": ["id"]},
                 "riskLevel": "LOW", "ownerTeam": "shop-team", "kind": "REST",
                 "httpMethod": "GET", "baseUrl": "http://localhost:8081", "path": "/api/orders/{id}"}
                """;
    }
}
