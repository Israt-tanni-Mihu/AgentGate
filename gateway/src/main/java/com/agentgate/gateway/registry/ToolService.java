package com.agentgate.gateway.registry;

import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Business rules of the tool registry (FR1). Controllers call this; this calls the repository. */
@Service
@Transactional
public class ToolService {

    private final ToolRepository repository;
    private final ObjectMapper objectMapper;

    public ToolService(ToolRepository repository, ObjectMapper objectMapper) {
        this.repository = repository;
        this.objectMapper = objectMapper;
    }

    public ToolResponse create(ToolRequest request) {
        if (repository.existsByName(request.name())) {
            throw new DuplicateToolNameException(request.name());
        }
        Tool tool = new Tool();
        copyRequestInto(request, tool);
        return toResponse(repository.save(tool));
    }

    @Transactional(readOnly = true)
    public ToolResponse get(Long id) {
        return toResponse(findOrThrow(id));
    }

    /** Used by the call pipeline to find the tool an agent wants to call. */
    @Transactional(readOnly = true)
    public Optional<ToolResponse> findByName(String name) {
        return repository.findByName(name).map(this::toResponse);
    }

    @Transactional(readOnly = true)
    public Page<ToolResponse> list(Pageable pageable) {
        return repository.findAll(pageable).map(this::toResponse);
    }

    public ToolResponse update(Long id, ToolRequest request) {
        Tool tool = findOrThrow(id);
        if (repository.existsByNameAndIdNot(request.name(), id)) {
            throw new DuplicateToolNameException(request.name());
        }
        copyRequestInto(request, tool);
        // saveAndFlush so @PreUpdate runs now and the response shows the new updatedAt
        return toResponse(repository.saveAndFlush(tool));
    }

    public void delete(Long id) {
        repository.delete(findOrThrow(id));
    }

    private Tool findOrThrow(Long id) {
        return repository.findById(id).orElseThrow(() -> new ToolNotFoundException(id));
    }

    private void copyRequestInto(ToolRequest request, Tool tool) {
        tool.setName(request.name());
        tool.setDescription(request.description());
        tool.setInputSchema(objectMapper.writeValueAsString(request.inputSchema()));
        tool.setRiskLevel(request.riskLevel());
        tool.setOwnerTeam(request.ownerTeam());
        tool.setKind(request.kind());
        tool.setHttpMethod(request.httpMethod());
        tool.setBaseUrl(request.baseUrl());
        tool.setPath(request.path());
        tool.setEnabled(request.enabled() == null || request.enabled());
        // FR6: an omitted accessMode is derived from the HTTP method again (an admin sets it explicitly to override).
        tool.setAccessMode(request.accessMode() != null
                ? request.accessMode()
                : AccessMode.defaultFor(request.kind(), request.httpMethod()));
    }

    ToolResponse toResponse(Tool tool) {
        return new ToolResponse(tool.getId(), tool.getName(), tool.getDescription(),
                readJson(tool.getInputSchema()), tool.getRiskLevel(), tool.getOwnerTeam(), tool.getKind(),
                tool.getHttpMethod(), tool.getBaseUrl(), tool.getPath(), tool.isEnabled(),
                tool.getAccessMode(), tool.getCreatedAt(), tool.getUpdatedAt());
    }

    JsonNode readJson(String json) {
        return objectMapper.readTree(json);
    }
}
