package com.agentgate.gateway.identity;

import java.security.SecureRandom;
import java.util.Base64;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Business rules for registering and disabling agents (FR2). */
@Service
@Transactional
public class AgentService {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final AgentRepository repository;
    private final PasswordEncoder passwordEncoder;

    public AgentService(AgentRepository repository, PasswordEncoder passwordEncoder) {
        this.repository = repository;
        this.passwordEncoder = passwordEncoder;
    }

    /** Generates a strong random secret, stores only its hash, and returns the plain secret once. */
    public RegisteredAgentResponse register(RegisterAgentRequest request) {
        return registerWithSecret(request, newSecret());
    }

    /** Used by the local demo seeder, which takes its secrets from environment variables. */
    RegisteredAgentResponse registerWithSecret(RegisterAgentRequest request, String plainSecret) {
        if (repository.existsByClientId(request.clientId())) {
            throw new DuplicateAgentException(request.clientId());
        }
        Agent agent = new Agent();
        agent.setClientId(request.clientId());
        agent.setDisplayName(request.displayName());
        agent.setClearance(request.clearance());
        agent.setClientSecretHash(passwordEncoder.encode(plainSecret));
        Agent saved = repository.save(agent);
        return new RegisteredAgentResponse(saved.getId(), saved.getClientId(), saved.getDisplayName(),
                saved.getClearance(), saved.isEnabled(), saved.getCreatedAt(), plainSecret);
    }

    @Transactional(readOnly = true)
    public AgentResponse get(String clientId) {
        return toResponse(findOrThrow(clientId));
    }

    @Transactional(readOnly = true)
    public Page<AgentResponse> list(Pageable pageable) {
        return repository.findAll(pageable).map(AgentService::toResponse);
    }

    /** A disabled agent can no longer get tokens, and tokens it already has stop working (see AgentJwtAuthenticationConverter). */
    public AgentResponse disable(String clientId) {
        Agent agent = findOrThrow(clientId);
        agent.setEnabled(false);
        return toResponse(repository.saveAndFlush(agent));
    }

    @Transactional(readOnly = true)
    public boolean exists(String clientId) {
        return repository.existsByClientId(clientId);
    }

    private Agent findOrThrow(String clientId) {
        return repository.findByClientId(clientId).orElseThrow(() -> new AgentNotFoundException(clientId));
    }

    private static String newSecret() {
        byte[] bytes = new byte[32];   // 256 bits of randomness
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    static AgentResponse toResponse(Agent agent) {
        return new AgentResponse(agent.getId(), agent.getClientId(), agent.getDisplayName(), agent.getClearance(),
                agent.isEnabled(), agent.getCreatedAt(), agent.getUpdatedAt());
    }
}
