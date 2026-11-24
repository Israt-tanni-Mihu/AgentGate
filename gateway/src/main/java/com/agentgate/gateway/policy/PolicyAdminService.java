package com.agentgate.gateway.policy;

import com.agentgate.gateway.policy.operator.ConditionOperator;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Managing policy versions: create a draft, list/view, activate, toggle dry-run.
 *
 * Changes run inside a transaction (TransactionTemplate); AFTER it commits we refresh the in-memory
 * cache, so the new policy is used by the very next call.
 */
@Service
public class PolicyAdminService {

    private final PolicyVersionRepository repository;
    private final PolicyRulesJson rulesJson;
    private final PolicyEngine engine;
    private final ActivePolicyCache cache;
    private final TransactionTemplate transaction;

    public PolicyAdminService(PolicyVersionRepository repository, PolicyRulesJson rulesJson, PolicyEngine engine,
                              ActivePolicyCache cache, TransactionTemplate transaction) {
        this.repository = repository;
        this.rulesJson = rulesJson;
        this.engine = engine;
        this.cache = cache;
        this.transaction = transaction;
    }

    public PolicyVersionResponse createDraft(CreatePolicyVersionRequest request) {
        validate(request.rules());
        return transaction.execute(status -> {
            PolicyVersion draft = new PolicyVersion();
            draft.setVersion(repository.findHighestVersion() + 1);
            draft.setStatus(PolicyStatus.DRAFT);
            draft.setDryRun(Boolean.TRUE.equals(request.dryRun()));
            draft.setDescription(request.description());
            draft.setRules(rulesJson.write(request.rules()));
            return toResponse(repository.save(draft));
        });
    }

    public Page<PolicyVersionResponse> list(Pageable pageable) {
        return transaction.execute(status -> repository.findAll(pageable).map(this::toResponse));
    }

    public PolicyVersionResponse get(int version) {
        return transaction.execute(status -> toResponse(findOrThrow(version)));
    }

    public Optional<PolicyVersionResponse> active() {
        return transaction.execute(status -> repository.findByStatus(PolicyStatus.ACTIVE).map(this::toResponse));
    }

    /** The old ACTIVE version becomes RETIRED; this one becomes ACTIVE. Works for rollback too. */
    public PolicyVersionResponse activate(int version) {
        PolicyVersionResponse result = transaction.execute(status -> {
            PolicyVersion target = findOrThrow(version);
            if (target.getStatus() == PolicyStatus.ACTIVE) {
                return toResponse(target);
            }
            repository.findByStatus(PolicyStatus.ACTIVE).ifPresent(old -> {
                old.setStatus(PolicyStatus.RETIRED);
                repository.saveAndFlush(old);   // flush first: the DB allows only one ACTIVE row at a time
            });
            target.setStatus(PolicyStatus.ACTIVE);
            target.setActivatedAt(Instant.now());
            return toResponse(repository.saveAndFlush(target));
        });
        cache.refresh();
        return result;
    }

    public PolicyVersionResponse setDryRun(int version, boolean enabled) {
        PolicyVersionResponse result = transaction.execute(status -> {
            PolicyVersion target = findOrThrow(version);
            target.setDryRun(enabled);
            return toResponse(repository.saveAndFlush(target));
        });
        cache.refresh();
        return result;
    }

    /** Checks what Bean Validation cannot: unique rule ids, known operators, right kind of values. */
    private void validate(List<PolicyRule> rules) {
        List<String> problems = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        for (PolicyRule rule : rules) {
            if (!ids.add(rule.id())) {
                problems.add("rule id '" + rule.id() + "' is used twice");
            }
            for (Condition condition : rule.conditions()) {
                Optional<ConditionOperator> operator = engine.operator(condition.operator());
                if (operator.isEmpty()) {
                    problems.add("rule '" + rule.id() + "': unknown operator '" + condition.operator() + "'");
                    continue;
                }
                String valueProblem = operator.get().checkValue(condition.value());
                if (valueProblem != null) {
                    problems.add("rule '" + rule.id() + "': " + condition.operator() + " " + valueProblem);
                }
            }
        }
        if (!problems.isEmpty()) {
            throw new InvalidPolicyException(problems);
        }
    }

    private PolicyVersion findOrThrow(int version) {
        return repository.findByVersion(version).orElseThrow(() -> new PolicyVersionNotFoundException(version));
    }

    private PolicyVersionResponse toResponse(PolicyVersion version) {
        return new PolicyVersionResponse(version.getVersion(), version.getStatus(), version.isDryRun(),
                version.getDescription(), rulesJson.read(version.getRules()), version.getCreatedAt(),
                version.getActivatedAt());
    }
}
