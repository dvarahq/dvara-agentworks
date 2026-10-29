package com.dvarahq.agentworks.domain.security;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static com.dvarahq.agentworks.domain.security.RememberedPermissionEvaluator.Reason;
import static com.dvarahq.agentworks.domain.security.RememberedPermissionEvaluator.Verdict;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Issue #39: remembered permission boundary matrix independent of storage and provider adapters. */
class RememberedPermissionEvaluatorTest {
    private static final Instant NOW = Instant.parse("2026-10-08T05:00:00Z");
    private final UUID organization = UUID.randomUUID();
    private final UUID workspace = UUID.randomUUID();
    private final UUID actor = UUID.randomUUID();
    private final UUID agent = UUID.randomUUID();
    private final UUID release = UUID.randomUUID();
    private final UUID environment = UUID.randomUUID();

    @Test
    void exactWorkspaceRuleAllowsAtOrBelowItsRiskCeiling() {
        RememberedPermissionRule rule = rule(UUID.randomUUID(), 1, RememberedPermissionRule.Effect.ALLOW,
                PermissionScope.workspace(workspace), PermissionRisk.MEDIUM, Optional.empty());

        assertDecision(request(PermissionRisk.LOW), List.of(rule), Verdict.ALLOW, Reason.MATCHED_ALLOW, rule);
        assertDecision(request(PermissionRisk.MEDIUM), List.of(rule), Verdict.ALLOW, Reason.MATCHED_ALLOW, rule);
        assertDecision(request(PermissionRisk.HIGH), List.of(rule), Verdict.DENY,
                Reason.RISK_EXCEEDS_PERMISSION, rule);
    }

    @Test
    void everyScopedDimensionMustMatchExactly() {
        RememberedPermissionRule rule = rule(UUID.randomUUID(), 1, RememberedPermissionRule.Effect.ALLOW,
                PermissionScope.workspace(workspace), PermissionRisk.CRITICAL, Optional.empty());
        RememberedPermissionRequest base = request(PermissionRisk.LOW);

        List<RememberedPermissionRequest> mismatches = List.of(
                new RememberedPermissionRequest(organization, UUID.randomUUID(), actor, agent, release,
                        "ledger", "account/7", "write", environment, PermissionRisk.LOW, NOW),
                new RememberedPermissionRequest(organization, workspace, UUID.randomUUID(), agent, release,
                        "ledger", "account/7", "write", environment, PermissionRisk.LOW, NOW),
                new RememberedPermissionRequest(organization, workspace, actor, UUID.randomUUID(), release,
                        "ledger", "account/7", "write", environment, PermissionRisk.LOW, NOW),
                new RememberedPermissionRequest(organization, workspace, actor, agent, UUID.randomUUID(),
                        "ledger", "account/7", "write", environment, PermissionRisk.LOW, NOW),
                new RememberedPermissionRequest(organization, workspace, actor, agent, release,
                        "mail", "account/7", "write", environment, PermissionRisk.LOW, NOW),
                new RememberedPermissionRequest(organization, workspace, actor, agent, release,
                        "ledger", "account/8", "write", environment, PermissionRisk.LOW, NOW),
                new RememberedPermissionRequest(organization, workspace, actor, agent, release,
                        "ledger", "account/7", "read", environment, PermissionRisk.LOW, NOW),
                new RememberedPermissionRequest(organization, workspace, actor, agent, release,
                        "ledger", "account/7", "write", UUID.randomUUID(), PermissionRisk.LOW, NOW));

        assertThat(mismatches).allSatisfy(request -> assertThat(RememberedPermissionEvaluator
                .evaluate(request, List.of(rule)).reason()).isEqualTo(Reason.NO_MATCH));
        assertThat(RememberedPermissionEvaluator.evaluate(base, List.of(rule)).verdict()).isEqualTo(Verdict.ALLOW);
    }

    @Test
    void organizationScopeCanMatchItsWorkspaceButNotAnotherOrganization() {
        RememberedPermissionRule rule = rule(UUID.randomUUID(), 1, RememberedPermissionRule.Effect.ALLOW,
                PermissionScope.organization(organization), PermissionRisk.LOW, Optional.empty());

        assertThat(RememberedPermissionEvaluator.evaluate(request(PermissionRisk.LOW), List.of(rule)).verdict())
                .isEqualTo(Verdict.ALLOW);
        RememberedPermissionRequest otherOrganization = new RememberedPermissionRequest(UUID.randomUUID(), workspace,
                actor, agent, release, "ledger", "account/7", "write", environment, PermissionRisk.LOW, NOW);
        assertThat(RememberedPermissionEvaluator.evaluate(otherOrganization, List.of(rule)).reason())
                .isEqualTo(Reason.NO_MATCH);
    }

    @Test
    void explicitDenyWinsOverMoreSpecificAndHigherVersionAllows() {
        RememberedPermissionRule allow = rule(UUID.randomUUID(), 9, RememberedPermissionRule.Effect.ALLOW,
                PermissionScope.workspace(workspace), PermissionRisk.CRITICAL, Optional.empty());
        RememberedPermissionRule deny = rule(UUID.randomUUID(), 1, RememberedPermissionRule.Effect.DENY,
                PermissionScope.organization(organization), PermissionRisk.CRITICAL, Optional.empty());

        assertDecision(request(PermissionRisk.HIGH), List.of(allow, deny), Verdict.DENY, Reason.EXPLICIT_DENY, deny);
    }

    @Test
    void aDenyOutsideItsRiskScopeCannotOverrideAnAdequateGrant() {
        RememberedPermissionRule allow = rule(UUID.randomUUID(), 1, RememberedPermissionRule.Effect.ALLOW,
                PermissionScope.workspace(workspace), PermissionRisk.HIGH, Optional.empty());
        RememberedPermissionRule lowRiskDeny = rule(UUID.randomUUID(), 1, RememberedPermissionRule.Effect.DENY,
                PermissionScope.workspace(workspace), PermissionRisk.LOW, Optional.empty());

        assertDecision(request(PermissionRisk.HIGH), List.of(allow, lowRiskDeny), Verdict.ALLOW,
                Reason.MATCHED_ALLOW, allow);
    }

    @Test
    void latestVersionSupersedesOlderAuthorityAndCannotFallBackAfterRevocation() {
        UUID id = UUID.randomUUID();
        RememberedPermissionRule oldAllow = rule(id, 1, RememberedPermissionRule.Effect.ALLOW,
                PermissionScope.workspace(workspace), PermissionRisk.HIGH, Optional.empty());
        RememberedPermissionRule revoked = rule(id, 2, RememberedPermissionRule.Effect.ALLOW,
                PermissionScope.workspace(workspace), PermissionRisk.HIGH, Optional.of(NOW.minusSeconds(1)));

        assertDecision(request(PermissionRisk.LOW), List.of(oldAllow, revoked), Verdict.DENY, Reason.REVOKED, revoked);
    }

    @Test
    void validityBoundariesAreClosedAtStartAndOpenAtExpiry() {
        RememberedPermissionRule rule = rule(UUID.randomUUID(), 1, RememberedPermissionRule.Effect.ALLOW,
                PermissionScope.workspace(workspace), PermissionRisk.LOW, Optional.empty());
        RememberedPermissionRequest before = withTime(rule.validFrom().minusNanos(1));
        RememberedPermissionRequest atStart = withTime(rule.validFrom());
        RememberedPermissionRequest atExpiry = withTime(rule.expiresAt());

        assertThat(RememberedPermissionEvaluator.evaluate(before, List.of(rule)).reason())
                .isEqualTo(Reason.NOT_YET_ACTIVE);
        assertThat(RememberedPermissionEvaluator.evaluate(atStart, List.of(rule)).verdict()).isEqualTo(Verdict.ALLOW);
        assertThat(RememberedPermissionEvaluator.evaluate(atExpiry, List.of(rule)).reason())
                .isEqualTo(Reason.EXPIRED);
    }

    @Test
    void conflictsAtOneImmutableRuleVersionAreRejected() {
        UUID id = UUID.randomUUID();
        RememberedPermissionRule allow = rule(id, 1, RememberedPermissionRule.Effect.ALLOW,
                PermissionScope.workspace(workspace), PermissionRisk.LOW, Optional.empty());
        RememberedPermissionRule deny = rule(id, 1, RememberedPermissionRule.Effect.DENY,
                PermissionScope.workspace(workspace), PermissionRisk.LOW, Optional.empty());

        assertThatThrownBy(() -> RememberedPermissionEvaluator.evaluate(request(PermissionRisk.LOW),
                List.of(allow, deny))).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("conflicting remembered permission versions");
    }

    private RememberedPermissionRule rule(UUID id, long version, RememberedPermissionRule.Effect effect,
            PermissionScope scope, PermissionRisk risk, Optional<Instant> revokedAt) {
        return new RememberedPermissionRule(id, version, effect, scope, actor, Optional.of(agent),
                Optional.of(release), "ledger", "account/7", "write", environment, risk,
                NOW.minusSeconds(60), NOW.plusSeconds(60), revokedAt);
    }

    private RememberedPermissionRequest request(PermissionRisk risk) {
        return new RememberedPermissionRequest(organization, workspace, actor, agent, release, "ledger",
                "account/7", "write", environment, risk, NOW);
    }

    private RememberedPermissionRequest withTime(Instant at) {
        return new RememberedPermissionRequest(organization, workspace, actor, agent, release, "ledger",
                "account/7", "write", environment, PermissionRisk.LOW, at);
    }

    private static void assertDecision(RememberedPermissionRequest request, List<RememberedPermissionRule> rules,
            Verdict verdict, Reason reason, RememberedPermissionRule matched) {
        RememberedPermissionEvaluator.Decision decision = RememberedPermissionEvaluator.evaluate(request, rules);
        assertThat(decision.verdict()).isEqualTo(verdict);
        assertThat(decision.reason()).isEqualTo(reason);
        assertThat(decision.ruleId()).contains(matched.id());
        assertThat(decision.ruleVersion()).contains(matched.version());
    }
}
