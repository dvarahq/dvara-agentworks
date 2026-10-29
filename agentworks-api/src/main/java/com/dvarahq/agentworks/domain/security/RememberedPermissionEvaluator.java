package com.dvarahq.agentworks.domain.security;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Pure, deterministic remembered-permission matcher. It performs no storage, network, or credential access. */
public final class RememberedPermissionEvaluator {
    public enum Verdict { ALLOW, DENY }

    public enum Reason {
        MATCHED_ALLOW,
        EXPLICIT_DENY,
        REVOKED,
        EXPIRED,
        NOT_YET_ACTIVE,
        RISK_EXCEEDS_PERMISSION,
        NO_MATCH
    }

    /** The matched immutable rule identity is present whenever a scoped candidate explains the decision. */
    public record Decision(Verdict verdict, Reason reason, Optional<UUID> ruleId, Optional<Long> ruleVersion) {
        public Decision {
            Objects.requireNonNull(verdict, "verdict");
            Objects.requireNonNull(reason, "reason");
            ruleId = Objects.requireNonNull(ruleId, "ruleId");
            ruleVersion = Objects.requireNonNull(ruleVersion, "ruleVersion");
            if (ruleId.isPresent() != ruleVersion.isPresent()) {
                throw new IllegalArgumentException("rule id and version must be present together");
            }
        }
    }

    private static final Comparator<RememberedPermissionRule> PRECEDENCE = Comparator
            .comparingInt(RememberedPermissionEvaluator::specificity).reversed()
            .thenComparing(RememberedPermissionRule::version, Comparator.reverseOrder())
            .thenComparing(rule -> rule.id().toString());

    private RememberedPermissionEvaluator() {}

    public static Decision evaluate(RememberedPermissionRequest request,
            Collection<RememberedPermissionRule> rules) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(rules, "rules");
        List<RememberedPermissionRule> candidates = latestVersions(rules).stream()
                .filter(rule -> matchesDimensions(rule, request)).sorted(PRECEDENCE).toList();
        if (candidates.isEmpty()) return denied(Reason.NO_MATCH, Optional.empty());

        Optional<RememberedPermissionRule> explicitDeny = candidates.stream()
                .filter(rule -> rule.effect() == RememberedPermissionRule.Effect.DENY)
                .filter(rule -> active(rule, request.evaluatedAt()))
                .filter(rule -> rule.maximumRisk().covers(request.risk())).findFirst();
        if (explicitDeny.isPresent()) return denied(Reason.EXPLICIT_DENY, explicitDeny);

        Optional<RememberedPermissionRule> allowed = candidates.stream()
                .filter(rule -> rule.effect() == RememberedPermissionRule.Effect.ALLOW)
                .filter(rule -> active(rule, request.evaluatedAt()))
                .filter(rule -> rule.maximumRisk().covers(request.risk())).findFirst();
        if (allowed.isPresent()) return decided(Verdict.ALLOW, Reason.MATCHED_ALLOW, allowed.get());

        Optional<RememberedPermissionRule> revoked = candidates.stream()
                .filter(rule -> revoked(rule, request.evaluatedAt())).findFirst();
        if (revoked.isPresent()) return denied(Reason.REVOKED, revoked);
        Optional<RememberedPermissionRule> expired = candidates.stream()
                .filter(rule -> !request.evaluatedAt().isBefore(rule.expiresAt())).findFirst();
        if (expired.isPresent()) return denied(Reason.EXPIRED, expired);
        Optional<RememberedPermissionRule> future = candidates.stream()
                .filter(rule -> request.evaluatedAt().isBefore(rule.validFrom())).findFirst();
        if (future.isPresent()) return denied(Reason.NOT_YET_ACTIVE, future);
        Optional<RememberedPermissionRule> insufficientRisk = candidates.stream()
                .filter(rule -> rule.effect() == RememberedPermissionRule.Effect.ALLOW)
                .filter(rule -> active(rule, request.evaluatedAt())).findFirst();
        if (insufficientRisk.isPresent()) return denied(Reason.RISK_EXCEEDS_PERMISSION, insufficientRisk);
        return denied(Reason.NO_MATCH, Optional.empty());
    }

    private static List<RememberedPermissionRule> latestVersions(Collection<RememberedPermissionRule> rules) {
        Map<UUID, RememberedPermissionRule> latest = new HashMap<>();
        for (RememberedPermissionRule rule : rules) {
            Objects.requireNonNull(rule, "rules contains null");
            RememberedPermissionRule existing = latest.get(rule.id());
            if (existing == null || rule.version() > existing.version()) {
                latest.put(rule.id(), rule);
            } else if (rule.version() == existing.version() && !rule.equals(existing)) {
                throw new IllegalArgumentException("conflicting remembered permission versions for " + rule.id());
            }
        }
        return new ArrayList<>(latest.values());
    }

    private static boolean matchesDimensions(RememberedPermissionRule rule, RememberedPermissionRequest request) {
        boolean tenant = switch (rule.scope().level()) {
            case ORGANIZATION -> rule.scope().id().equals(request.organizationId());
            case WORKSPACE -> rule.scope().id().equals(request.workspaceId());
        };
        return tenant
                && rule.actorId().equals(request.actorId())
                && rule.agentDefinitionId().map(request.agentDefinitionId()::equals).orElse(true)
                && rule.releaseId().map(request.releaseId()::equals).orElse(true)
                && rule.capability().equals(request.capability())
                && rule.resource().equals(request.resource())
                && rule.action().equals(request.action())
                && rule.environmentId().equals(request.environmentId());
    }

    private static boolean active(RememberedPermissionRule rule, Instant at) {
        return !at.isBefore(rule.validFrom()) && at.isBefore(rule.expiresAt()) && !revoked(rule, at);
    }

    private static boolean revoked(RememberedPermissionRule rule, Instant at) {
        return rule.revokedAt().filter(revokedAt -> !at.isBefore(revokedAt)).isPresent();
    }

    private static int specificity(RememberedPermissionRule rule) {
        return (rule.scope().level() == PermissionScope.Level.WORKSPACE ? 4 : 0)
                + (rule.releaseId().isPresent() ? 2 : 0)
                + (rule.agentDefinitionId().isPresent() ? 1 : 0);
    }

    private static Decision denied(Reason reason, Optional<RememberedPermissionRule> rule) {
        return rule.map(value -> decided(Verdict.DENY, reason, value))
                .orElseGet(() -> new Decision(Verdict.DENY, reason, Optional.empty(), Optional.empty()));
    }

    private static Decision decided(Verdict verdict, Reason reason, RememberedPermissionRule rule) {
        return new Decision(verdict, reason, Optional.of(rule.id()), Optional.of(rule.version()));
    }
}
