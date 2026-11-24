package com.agentgate.gateway.approval;

import com.agentgate.gateway.admin.KillSwitchScope;
import com.agentgate.gateway.pipeline.ToolCall;
import com.agentgate.gateway.registry.AccessMode;
import com.agentgate.gateway.registry.Tool;
import com.agentgate.gateway.registry.ToolRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * All approval business rules. Each decision: lock the row, let the STATE object allow or refuse the transition,
 * save, publish {@link ApprovalDecided}. Nothing here waits: holding the agent's call is {@link ApprovalStep}'s job.
 */
@Service
public class ApprovalService {

    private final ApprovalRequestRepository repository;
    private final ToolRepository tools;
    private final ApplicationEventPublisher events;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public ApprovalService(ApprovalRequestRepository repository, ToolRepository tools,
                           ApplicationEventPublisher events, ObjectMapper objectMapper, Clock clock) {
        this.repository = repository;
        this.tools = tools;
        this.events = events;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @Transactional
    public ApprovalRequest open(UUID id, ToolCall call, Duration timeout) {
        Instant now = clock.instant();
        ApprovalRequest request = repository.save(new ApprovalRequest(id, call.context().agentId(),
                call.context().endUserId(), call.toolName(), call.arguments().toString(), now, now.plus(timeout)));
        events.publishEvent(new ApprovalRequested(id, request.getAgentId(), request.getEndUserId(),
                request.getToolName(), call.arguments(), request.getExpiresAt()));
        return request;
    }

    @Transactional
    public ApprovalResponse approve(UUID id, String approver) {
        return decide(id, request -> request.approve(approver, clock.instant()));
    }

    @Transactional
    public ApprovalResponse approveWithEdits(UUID id, String approver, ObjectNode newArguments) {
        return decide(id, request -> request.approveWithEdits(approver, newArguments.toString(), clock.instant()));
    }

    @Transactional
    public ApprovalResponse reject(UUID id, String approver, String reason) {
        return decide(id, request -> request.reject(approver, reason, clock.instant()));
    }

    @Transactional
    public ApprovalResponse expire(UUID id) {
        return decide(id, request -> request.expire(clock.instant()));
    }

    /** FR8: cancels every PENDING request that a kill switch covers. Requests that are not pending are left alone. */
    @Transactional
    public int cancelCoveredBy(KillSwitchScope scope, String target) {
        Set<String> writeTools = scope == KillSwitchScope.ALL_WRITES
                ? tools.findByAccessMode(AccessMode.WRITE).stream().map(Tool::getName).collect(Collectors.toSet())
                : Set.of();
        List<ApprovalRequest> covered = repository.findByState(ApprovalState.PENDING).stream()
                .filter(request -> switch (scope) {
                    case AGENT -> request.getAgentId().equals(target);
                    case TOOL -> request.getToolName().equals(target);
                    case ALL_WRITES -> writeTools.contains(request.getToolName());
                })
                .toList();
        String why = "cancelled by kill switch (" + scope + (scope == KillSwitchScope.ALL_WRITES ? "" : ": " + target) + ")";
        covered.forEach(request -> decide(request.getId(), r -> r.cancel(why, clock.instant())));
        return covered.size();
    }

    @Transactional(readOnly = true)
    public Page<ApprovalResponse> list(ApprovalState state, Pageable pageable) {
        Page<ApprovalRequest> page = state == null ? repository.findAll(pageable) : repository.findByState(state, pageable);
        return page.map(this::toResponse);
    }

    @Transactional(readOnly = true)
    public ApprovalResponse get(UUID id) {
        return toResponse(repository.findById(id).orElseThrow(() -> new ApprovalNotFoundException(id)));
    }

    private ApprovalResponse decide(UUID id, Consumer<ApprovalRequest> transition) {
        ApprovalRequest request = repository.findForUpdate(id).orElseThrow(() -> new ApprovalNotFoundException(id));
        transition.accept(request);   // throws IllegalApprovalTransitionException (409) if the state forbids it
        repository.save(request);
        events.publishEvent(new ApprovalDecided(id, request.getState(), request.getAgentId(), request.getEndUserId(),
                request.getToolName(), request.getApprover(), request.getReason(),
                parse(request.getEditedArguments()), request.getDecidedAt()));
        return toResponse(request);
    }

    private ApprovalResponse toResponse(ApprovalRequest r) {
        return new ApprovalResponse(r.getId(), r.getAgentId(), r.getEndUserId(), r.getToolName(),
                parse(r.getOriginalArguments()), parse(r.getEditedArguments()), r.getState(), r.getApprover(),
                r.getReason(), r.getCreatedAt(), r.getDecidedAt(), r.getExpiresAt());
    }

    private JsonNode parse(String json) {
        return json == null ? null : objectMapper.readTree(json);
    }
}
