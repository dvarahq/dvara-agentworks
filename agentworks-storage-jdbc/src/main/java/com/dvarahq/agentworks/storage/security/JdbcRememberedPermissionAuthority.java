package com.dvarahq.agentworks.storage.security;

import com.dvarahq.agentworks.domain.security.PermissionRisk;
import com.dvarahq.agentworks.domain.security.PermissionScope;
import com.dvarahq.agentworks.domain.security.RememberedPermissionEvaluator;
import com.dvarahq.agentworks.domain.security.RememberedPermissionRequest;
import com.dvarahq.agentworks.domain.security.RememberedPermissionRule;
import com.dvarahq.agentworks.storage.TenantContext;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Tenant-bound Spring JDBC authority for remembered permissions. Each evaluation reads the latest database state and
 * uses database time, so revocation and expiry are rechecked at use instead of cached in a worker.
 */
public final class JdbcRememberedPermissionAuthority {
    private final JdbcTemplate jdbc;

    public JdbcRememberedPermissionAuthority(JdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    /** Appends one immutable version; callers perform authorization and audit in the surrounding transaction. */
    public void append(RememberedPermissionRule rule) {
        Objects.requireNonNull(rule, "rule");
        UUID workspace = TenantContext.requireWorkspace().value();
        UUID organization = organization(workspace);
        if (!scopeBelongsToTenant(rule.scope(), organization, workspace)) {
            throw new IllegalArgumentException("permission scope does not match the transaction tenant");
        }
        if (rule.version() == 1) {
            jdbc.update("insert into remembered_permission_identity(permission_id, organization_id, scope_level, "
                            + "scope_id) values (?, ?, ?, ?)", rule.id(), organization, rule.scope().level().name(),
                    rule.scope().id());
        }
        jdbc.update("insert into remembered_permission_rule(permission_id, version, organization_id, scope_level, "
                        + "scope_id, actor_id, agent_definition_id, release_id, capability, resource, action, "
                        + "environment_id, effect, maximum_risk, valid_from, expires_at, revoked_at) "
                        + "values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                rule.id(), rule.version(), organization, rule.scope().level().name(), rule.scope().id(), rule.actorId(),
                rule.agentDefinitionId().orElse(null), rule.releaseId().orElse(null), rule.capability(), rule.resource(),
                rule.action(), rule.environmentId(), rule.effect().name(), rule.maximumRisk().name(),
                timestamp(rule.validFrom()), timestamp(rule.expiresAt()), rule.revokedAt().map(
                        JdbcRememberedPermissionAuthority::timestamp).orElse(null));
    }

    /** Returns the latest immutable version of every permission visible to the bound workspace. */
    public List<RememberedPermissionRule> latest() {
        UUID workspace = TenantContext.requireWorkspace().value();
        UUID organization = organization(workspace);
        return jdbc.query("select r.* from remembered_permission_rule r join "
                        + "(select permission_id, max(version) version from remembered_permission_rule "
                        + "where organization_id = ? and ((scope_level = 'ORGANIZATION' and scope_id = ?) or "
                        + "(scope_level = 'WORKSPACE' and scope_id = ?)) group by permission_id) latest "
                        + "on (latest.permission_id, latest.version) = (r.permission_id, r.version) "
                        + "order by r.permission_id",
                (rs, row) -> rule(rs), organization, organization, workspace);
    }

    /** Keyset page of latest versions, ordered by stable permission identity. */
    public List<RememberedPermissionRule> latest(Optional<UUID> after, int limit) {
        Objects.requireNonNull(after, "after");
        if (limit < 1) throw new IllegalArgumentException("permission page limit must be positive");
        UUID workspace = TenantContext.requireWorkspace().value();
        UUID organization = organization(workspace);
        String base = "select r.* from remembered_permission_rule r join "
                + "(select permission_id, max(version) version from remembered_permission_rule "
                + "where organization_id = ? and ((scope_level = 'ORGANIZATION' and scope_id = ?) or "
                + "(scope_level = 'WORKSPACE' and scope_id = ?)) group by permission_id) latest "
                + "on (latest.permission_id, latest.version) = (r.permission_id, r.version) ";
        if (after.isPresent()) {
            return jdbc.query(base + "where r.permission_id > ? order by r.permission_id fetch first ? rows only",
                    (rs, row) -> rule(rs), organization, organization, workspace, after.orElseThrow(), limit);
        }
        return jdbc.query(base + "order by r.permission_id fetch first ? rows only", (rs, row) -> rule(rs),
                organization, organization, workspace, limit);
    }

    /** Locks and returns the latest version of one in-scope permission for an atomic management transition. */
    public Optional<RememberedPermissionRule> lockLatest(UUID permissionId) {
        Objects.requireNonNull(permissionId, "permissionId");
        UUID workspace = TenantContext.requireWorkspace().value();
        UUID organization = organization(workspace);
        List<UUID> locked = jdbc.query("select permission_id from remembered_permission_identity where "
                        + "permission_id = ? and organization_id = ? and ((scope_level = 'ORGANIZATION' and "
                        + "scope_id = ?) or (scope_level = 'WORKSPACE' and scope_id = ?)) for update",
                (rs, row) -> rs.getObject(1, UUID.class), permissionId, organization, organization, workspace);
        return locked.isEmpty() ? Optional.empty() : latest(permissionId);
    }

    /** Returns one latest in-scope permission without taking a management lock. */
    public Optional<RememberedPermissionRule> latest(UUID permissionId) {
        Objects.requireNonNull(permissionId, "permissionId");
        UUID workspace = TenantContext.requireWorkspace().value();
        UUID organization = organization(workspace);
        List<RememberedPermissionRule> rows = jdbc.query("select r.* from remembered_permission_rule r where "
                        + "r.permission_id = ? and r.version = (select max(version) from remembered_permission_rule "
                        + "where permission_id = ? and organization_id = ? and ((scope_level = 'ORGANIZATION' and "
                        + "scope_id = ?) or (scope_level = 'WORKSPACE' and scope_id = ?)))",
                (rs, row) -> rule(rs), permissionId, permissionId, organization, organization, workspace);
        return rows.stream().findFirst();
    }

    /** Database time used for authoring and revocation boundaries. */
    public Instant databaseNow() {
        TenantContext.requireWorkspace();
        return jdbc.queryForObject("select current_timestamp", OffsetDateTime.class).toInstant();
    }

    /** The organization derived from the bound workspace; callers never accept it from a request. */
    public UUID organizationId() {
        return organization(TenantContext.requireWorkspace().value());
    }

    /** Validates that every optional resource selector belongs to the bound tenant. */
    public void validateManagementReferences(RememberedPermissionRule rule) {
        Objects.requireNonNull(rule, "rule");
        UUID workspace = TenantContext.requireWorkspace().value();
        UUID organization = organization(workspace);
        requireExists("select count(*) from user_account u join workspace_membership m on m.organization_id = "
                        + "u.organization_id and m.subject_id = u.id where u.id = ? and u.organization_id = ? and "
                        + "u.status = 'ACTIVE' and m.workspace_id = ?", rule.actorId(), organization, workspace,
                "permission actor is not an active member of the workspace");
        requireExists("select count(*) from agent_environment where workspace_id = ? and id = ?", workspace,
                rule.environmentId(), "permission environment is not in the workspace");
        rule.agentDefinitionId().ifPresent(id -> requireExists(
                "select count(*) from agent_definition where workspace_id = ? and id = ?", workspace, id,
                "permission agent is not in the workspace"));
        rule.releaseId().ifPresent(id -> requireExists(
                "select count(*) from agent_release where workspace_id = ? and id = ?", workspace, id,
                "permission release is not in the workspace"));
        if (rule.agentDefinitionId().isPresent() && rule.releaseId().isPresent()) {
            requireExists("select count(*) from agent_release where workspace_id = ? and id = ? and definition_id = ?",
                    workspace, rule.releaseId().orElseThrow(), rule.agentDefinitionId().orElseThrow(),
                    "permission release does not belong to the selected agent");
        }
    }

    public RememberedPermissionEvaluator.Decision evaluate(RememberedPermissionRequest request) {
        Objects.requireNonNull(request, "request");
        UUID workspace = TenantContext.requireWorkspace().value();
        if (!workspace.equals(request.workspaceId())) {
            throw new IllegalArgumentException("permission request does not match the transaction tenant");
        }
        UUID organization = organization(workspace);
        if (!organization.equals(request.organizationId())) {
            throw new IllegalArgumentException("permission organization does not match the transaction tenant");
        }
        Instant databaseNow = jdbc.queryForObject("select current_timestamp", OffsetDateTime.class).toInstant();
        RememberedPermissionRequest atUse = new RememberedPermissionRequest(request.organizationId(),
                request.workspaceId(), request.actorId(), request.agentDefinitionId(), request.releaseId(),
                request.capability(), request.resource(), request.action(), request.environmentId(), request.risk(),
                databaseNow);
        List<RememberedPermissionRule> rules = jdbc.query("select r.* from remembered_permission_rule r join "
                        + "(select permission_id, max(version) version from remembered_permission_rule "
                        + "where organization_id = ? and ((scope_level = 'ORGANIZATION' and scope_id = ?) or "
                        + "(scope_level = 'WORKSPACE' and scope_id = ?)) group by permission_id) latest "
                        + "on (latest.permission_id, latest.version) = (r.permission_id, r.version)",
                (rs, row) -> rule(rs), organization, organization, workspace);
        return RememberedPermissionEvaluator.evaluate(atUse, rules);
    }

    private UUID organization(UUID workspace) {
        List<UUID> rows = jdbc.query("select organization_id from workspace where id = ?",
                (rs, row) -> rs.getObject(1, UUID.class), workspace);
        if (rows.size() != 1) throw new IllegalArgumentException("transaction workspace does not exist");
        return rows.getFirst();
    }

    private void requireExists(String sql, Object first, Object second, String message) {
        Long count = jdbc.queryForObject(sql, Long.class, first, second);
        if (count == null || count != 1L) throw new IllegalArgumentException(message);
    }

    private void requireExists(String sql, Object first, Object second, Object third, String message) {
        Long count = jdbc.queryForObject(sql, Long.class, first, second, third);
        if (count == null || count != 1L) throw new IllegalArgumentException(message);
    }

    private static boolean scopeBelongsToTenant(PermissionScope scope, UUID organization, UUID workspace) {
        return switch (scope.level()) {
            case ORGANIZATION -> scope.id().equals(organization);
            case WORKSPACE -> scope.id().equals(workspace);
        };
    }

    private static RememberedPermissionRule rule(ResultSet rs) throws SQLException {
        UUID agent = rs.getObject("agent_definition_id", UUID.class);
        UUID release = rs.getObject("release_id", UUID.class);
        OffsetDateTime revoked = rs.getObject("revoked_at", OffsetDateTime.class);
        return new RememberedPermissionRule(rs.getObject("permission_id", UUID.class), rs.getLong("version"),
                RememberedPermissionRule.Effect.valueOf(rs.getString("effect")),
                new PermissionScope(PermissionScope.Level.valueOf(rs.getString("scope_level")),
                        rs.getObject("scope_id", UUID.class)),
                rs.getObject("actor_id", UUID.class), Optional.ofNullable(agent), Optional.ofNullable(release),
                rs.getString("capability"), rs.getString("resource"), rs.getString("action"),
                rs.getObject("environment_id", UUID.class), PermissionRisk.valueOf(rs.getString("maximum_risk")),
                rs.getObject("valid_from", OffsetDateTime.class).toInstant(),
                rs.getObject("expires_at", OffsetDateTime.class).toInstant(),
                Optional.ofNullable(revoked).map(OffsetDateTime::toInstant));
    }

    private static OffsetDateTime timestamp(Instant value) {
        return value.atOffset(ZoneOffset.UTC);
    }
}
