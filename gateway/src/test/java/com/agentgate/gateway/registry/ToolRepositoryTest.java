package com.agentgate.gateway.registry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.agentgate.gateway.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * JPA slice test: only JPA + Flyway start (no web layer), against a real PostgreSQL in Docker.
 * This proves migration V1 matches the entity (ddl-auto=validate) and jsonb really stores JSON.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)   // keep the Postgres container
@Import(TestcontainersConfiguration.class)
class ToolRepositoryTest {

    @Autowired
    ToolRepository repository;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Test
    void savesAndFindsToolByNameWithTimestamps() {
        repository.saveAndFlush(restTool("getOrder"));

        Tool found = repository.findByName("getOrder").orElseThrow();
        assertThat(found.getId()).isNotNull();
        assertThat(found.getRiskLevel()).isEqualTo(RiskLevel.LOW);
        assertThat(found.getCreatedAt()).isNotNull();
        assertThat(found.getUpdatedAt()).isEqualTo(found.getCreatedAt());
        assertThat(repository.existsByName("getOrder")).isTrue();
        assertThat(repository.existsByNameAndIdNot("getOrder", found.getId())).isFalse();
    }

    @Test
    void inputSchemaIsStoredAsRealJsonb() {
        repository.saveAndFlush(restTool("getOrder"));

        String jsonType = jdbcTemplate.queryForObject(
                "select jsonb_typeof(input_schema) from tools where name = 'getOrder'", String.class);
        String idType = jdbcTemplate.queryForObject(
                "select input_schema -> 'properties' -> 'id' ->> 'type' from tools where name = 'getOrder'",
                String.class);
        assertThat(jsonType).isEqualTo("object");
        assertThat(idType).isEqualTo("integer");
    }

    @Test
    void databaseRejectsDuplicateNames() {
        repository.saveAndFlush(restTool("getOrder"));

        assertThatThrownBy(() -> repository.saveAndFlush(restTool("getOrder")))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void databaseRejectsRestToolWithoutTarget() {
        Tool broken = restTool("broken");
        broken.setPath(null);

        assertThatThrownBy(() -> repository.saveAndFlush(broken))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    static Tool restTool(String name) {
        Tool tool = new Tool();
        tool.setName(name);
        tool.setDescription("Get an order by id");
        tool.setInputSchema("{\"type\":\"object\",\"properties\":{\"id\":{\"type\":\"integer\"}},\"required\":[\"id\"]}");
        tool.setRiskLevel(RiskLevel.LOW);
        tool.setOwnerTeam("shop-team");
        tool.setKind(ToolKind.REST);
        tool.setHttpMethod("GET");
        tool.setBaseUrl("http://localhost:8081");
        tool.setPath("/api/orders/{id}");
        return tool;
    }
}
