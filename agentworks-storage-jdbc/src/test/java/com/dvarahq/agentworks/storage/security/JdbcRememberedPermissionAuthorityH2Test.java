package com.dvarahq.agentworks.storage.security;

import com.dvarahq.agentworks.domain.security.PermissionRisk;
import com.dvarahq.agentworks.domain.security.PermissionScope;
import com.dvarahq.agentworks.domain.security.RememberedPermissionEvaluator;
import com.dvarahq.agentworks.domain.security.RememberedPermissionRequest;
import com.dvarahq.agentworks.domain.security.RememberedPermissionRule;
import com.dvarahq.agentworks.domain.tenant.WorkspaceId;
import com.dvarahq.agentworks.storage.TenantTransactionTemplate;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JdbcRememberedPermissionAuthorityH2Test {
    private JdbcRememberedPermissionAuthority authority;
    private TenantTransactionTemplate tenants;
    private UUID organization;
    private WorkspaceId workspaceA;
    private WorkspaceId workspaceB;
    private UUID actor;
    private UUID agent;
    private UUID release;
    private UUID environment;

    @BeforeEach
    void setUp() {
        var source = new DriverManagerDataSource(
                "jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1", "sa", "");
        Flyway.configure().dataSource(source).locations("classpath:db/migration/common").load().migrate();
        JdbcTemplate jdbc = new JdbcTemplate(source);
        tenants = new TenantTransactionTemplate(new TransactionTemplate(new JdbcTransactionManager(source)));
        authority = new JdbcRememberedPermissionAuthority(jdbc);
        organization = UUID.randomUUID();
        workspaceA = new WorkspaceId(UUID.randomUUID());
        workspaceB = new WorkspaceId(UUID.randomUUID());
        jdbc.update("insert into organization(id, name) values (?, ?)", organization, "org");
        jdbc.update("insert into workspace(id, organization_id, name) values (?, ?, ?)",
                workspaceA.value(), organization, "a");
        jdbc.update("insert into workspace(id, organization_id, name) values (?, ?, ?)",
                workspaceB.value(), organization, "b");
        actor = UUID.randomUUID();
        agent = UUID.randomUUID();
        release = UUID.randomUUID();
        environment = UUID.randomUUID();
    }

    @Test
    void workspaceRulesAreTenantBoundAndOrganizationRulesSpanOnlyTheirOrganization() {
        RememberedPermissionRule workspaceRule = rule(UUID.randomUUID(), 1, PermissionScope.workspace(workspaceA.value()),
                Instant.now().minusSeconds(60), Instant.now().plusSeconds(60), Optional.empty());
        tenants.executeWithoutResult(workspaceA, () -> authority.append(workspaceRule));

        assertThat(tenants.execute(workspaceA, () -> authority.evaluate(request(workspaceA, Instant.EPOCH))).verdict())
                .isEqualTo(RememberedPermissionEvaluator.Verdict.ALLOW);
        assertThat(tenants.execute(workspaceB, () -> authority.evaluate(request(workspaceB, Instant.EPOCH))).reason())
                .isEqualTo(RememberedPermissionEvaluator.Reason.NO_MATCH);

        RememberedPermissionRule organizationRule = rule(UUID.randomUUID(), 1,
                PermissionScope.organization(organization), Instant.now().minusSeconds(60),
                Instant.now().plusSeconds(60), Optional.empty());
        tenants.executeWithoutResult(workspaceA, () -> authority.append(organizationRule));
        assertThat(tenants.execute(workspaceB, () -> authority.evaluate(request(workspaceB, Instant.EPOCH))).verdict())
                .isEqualTo(RememberedPermissionEvaluator.Verdict.ALLOW);
    }

    @Test
    void latestRevokedVersionPreventsFallbackAndDatabaseTimeControlsTheDecision() {
        UUID id = UUID.randomUUID();
        Instant now = Instant.now();
        RememberedPermissionRule old = rule(id, 1, PermissionScope.workspace(workspaceA.value()),
                now.minusSeconds(120), now.plusSeconds(120), Optional.empty());
        RememberedPermissionRule revoked = rule(id, 2, PermissionScope.workspace(workspaceA.value()),
                now.minusSeconds(120), now.plusSeconds(120), Optional.of(now.minusSeconds(1)));
        tenants.executeWithoutResult(workspaceA, () -> {
            authority.append(old);
            authority.append(revoked);
        });

        RememberedPermissionEvaluator.Decision decision = tenants.execute(workspaceA,
                () -> authority.evaluate(request(workspaceA, now.minusSeconds(100))));
        assertThat(decision.verdict()).isEqualTo(RememberedPermissionEvaluator.Verdict.DENY);
        assertThat(decision.reason()).isEqualTo(RememberedPermissionEvaluator.Reason.REVOKED);
        assertThat(decision.ruleVersion()).contains(2L);

        RememberedPermissionRule expired = new RememberedPermissionRule(UUID.randomUUID(), 1,
                RememberedPermissionRule.Effect.ALLOW, PermissionScope.workspace(workspaceA.value()), actor,
                Optional.of(agent), Optional.of(release), "ledger", "account/expired", "write", environment,
                PermissionRisk.HIGH, now.minusSeconds(120), now.minusSeconds(1), Optional.empty());
        tenants.executeWithoutResult(workspaceA, () -> authority.append(expired));
        RememberedPermissionRequest expiredRequest = new RememberedPermissionRequest(organization, workspaceA.value(),
                actor, agent, release, "ledger", "account/expired", "write", environment, PermissionRisk.LOW,
                now.minusSeconds(60));
        RememberedPermissionEvaluator.Decision expiry = tenants.execute(workspaceA,
                () -> authority.evaluate(expiredRequest));
        assertThat(expiry.reason()).isEqualTo(RememberedPermissionEvaluator.Reason.EXPIRED);
    }

    @Test
    void bindingAndScopeMismatchesFailClosedBeforeAWriteOrRead() {
        RememberedPermissionRule wrongScope = rule(UUID.randomUUID(), 1,
                PermissionScope.workspace(workspaceB.value()), Instant.now().minusSeconds(60),
                Instant.now().plusSeconds(60), Optional.empty());
        assertThatThrownBy(() -> tenants.executeWithoutResult(workspaceA, () -> authority.append(wrongScope)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("scope");
        assertThatThrownBy(() -> tenants.execute(workspaceA,
                () -> authority.evaluate(request(workspaceB, Instant.now()))))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("transaction tenant");
        assertThatThrownBy(() -> authority.evaluate(request(workspaceA, Instant.now())))
                .hasMessageContaining("active transaction");
    }

    private RememberedPermissionRule rule(UUID id, long version, PermissionScope scope, Instant validFrom,
            Instant expiresAt, Optional<Instant> revokedAt) {
        return new RememberedPermissionRule(id, version, RememberedPermissionRule.Effect.ALLOW, scope, actor,
                Optional.of(agent), Optional.of(release), "ledger", "account/7", "write", environment,
                PermissionRisk.HIGH, validFrom, expiresAt, revokedAt);
    }

    private RememberedPermissionRequest request(WorkspaceId workspace, Instant callerTime) {
        return new RememberedPermissionRequest(organization, workspace.value(), actor, agent, release, "ledger",
                "account/7", "write", environment, PermissionRisk.LOW, callerTime);
    }
}
