package com.dvarahq.agentworks.storage.lifecycle;

import com.dvarahq.agentworks.domain.lifecycle.state.LifecycleReads;
import com.dvarahq.agentworks.storage.TenantContext;
import com.dvarahq.agentworks.domain.lifecycle.state.LifecycleRows.AliasHistoryRow;
import com.dvarahq.agentworks.domain.lifecycle.state.LifecycleRows.AliasRow;
import com.dvarahq.agentworks.domain.lifecycle.state.LifecycleRows.CohortRow;
import com.dvarahq.agentworks.domain.lifecycle.state.LifecycleRows.DecisionRow;
import com.dvarahq.agentworks.domain.lifecycle.state.LifecycleRows.DeploymentRow;
import com.dvarahq.agentworks.domain.lifecycle.state.LifecycleRows.EnvironmentRow;
import com.dvarahq.agentworks.domain.lifecycle.state.LifecycleRows.OutboxRow;
import com.dvarahq.agentworks.domain.lifecycle.state.LifecycleRows.QuiescenceBlocker;
import com.dvarahq.agentworks.domain.lifecycle.state.LifecycleRows.ReleaseRow;
import com.dvarahq.agentworks.domain.lifecycle.state.LifecycleRows.ReleaseBundleRow;
import com.dvarahq.agentworks.domain.lifecycle.state.LifecycleRows.ReleaseLifecycleRow;
import com.dvarahq.agentworks.domain.lifecycle.state.LifecycleRows.ReleaseRevocationRow;
import com.dvarahq.agentworks.domain.lifecycle.state.LifecycleRows.RevisionRow;
import com.dvarahq.agentworks.domain.lifecycle.state.LifecycleRows.RoutingGenerationRow;
import com.dvarahq.agentworks.domain.lifecycle.state.LifecycleRows.RoutingTargetRow;
import com.dvarahq.agentworks.domain.lifecycle.state.LifecycleRows.TransitionRow;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

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
 * Workspace-scoped persistence for releases, aliases, environments, deployments, revisions, cohorts, routing,
 * decisions, and outbox records. Every statement binds the transaction workspace from {@link TenantContext}.
 * Lock methods follow the documented order: environment, deployment, then cohorts by revision generation.
 */
public final class JdbcLifecycleRepository implements LifecycleReads {
    private static final String RELEASE = "id, workspace_id, definition_id, version_id, release_name, manifest_json, "
            + "manifest_digest, created_at";
    private static final String ENVIRONMENT = "id, workspace_id, name, description, policy_version, promotion_policy_json, "
            + "promotion_policy_digest, created_at";
    private static final String DEPLOYMENT = "id, workspace_id, environment_id, definition_id, name, desired_release_id, "
            + "revision, current_revision_id, current_routing_generation_id, created_at";
    private static final String REVISION = "id, workspace_id, deployment_id, generation, release_id, definition_id, "
            + "environment_id, placement, configuration_json, content_digest, cause, decision_id, previous_revision_id, "
            + "created_at";
    private static final String COHORT = "c.id, c.workspace_id, c.deployment_id, c.revision_id, c.state, "
            + "c.transition_version, c.state_changed_at, c.drain_started_at, c.drain_deadline, c.quiescent_since, "
            + "c.retired_at, c.created_at";
    private static final String DECISION = "id, workspace_id, kind, outcome, environment_id, definition_id, deployment_id, "
            + "release_id, cohort_id, policy_version, policy_digest, gate_results_json, actor, reason, revision_id, "
            + "routing_generation_id, created_at";

    private static final RowMapper<ReleaseRow> RELEASES = (rs, n) -> new ReleaseRow(uuid(rs, "id"),
            uuid(rs, "workspace_id"), uuid(rs, "definition_id"), uuid(rs, "version_id"), rs.getString("release_name"),
            rs.getString("manifest_json"), rs.getString("manifest_digest"), instant(rs, "created_at"));
    private static final RowMapper<EnvironmentRow> ENVIRONMENTS = (rs, n) -> new EnvironmentRow(uuid(rs, "id"),
            uuid(rs, "workspace_id"), rs.getString("name"), rs.getString("description"), rs.getLong("policy_version"),
            rs.getString("promotion_policy_json"), rs.getString("promotion_policy_digest"), instant(rs, "created_at"));
    private static final RowMapper<DeploymentRow> DEPLOYMENTS = (rs, n) -> new DeploymentRow(uuid(rs, "id"),
            uuid(rs, "workspace_id"), uuid(rs, "environment_id"), uuid(rs, "definition_id"), rs.getString("name"),
            uuid(rs, "desired_release_id"), rs.getLong("revision"), uuid(rs, "current_revision_id"),
            uuid(rs, "current_routing_generation_id"), instant(rs, "created_at"));
    private static final RowMapper<RevisionRow> REVISIONS = (rs, n) -> new RevisionRow(uuid(rs, "id"),
            uuid(rs, "workspace_id"), uuid(rs, "deployment_id"), rs.getLong("generation"), uuid(rs, "release_id"),
            uuid(rs, "definition_id"), uuid(rs, "environment_id"), rs.getString("placement"),
            rs.getString("configuration_json"), rs.getString("content_digest"), rs.getString("cause"),
            uuid(rs, "decision_id"), uuid(rs, "previous_revision_id"), instant(rs, "created_at"));
    private static final RowMapper<CohortRow> COHORTS = (rs, n) -> new CohortRow(uuid(rs, "id"),
            uuid(rs, "workspace_id"), uuid(rs, "deployment_id"), uuid(rs, "revision_id"), rs.getString("state"),
            rs.getLong("transition_version"), instant(rs, "state_changed_at"), instant(rs, "drain_started_at"),
            instant(rs, "drain_deadline"), instant(rs, "quiescent_since"), instant(rs, "retired_at"),
            instant(rs, "created_at"));
    private static final RowMapper<DecisionRow> DECISIONS = (rs, n) -> new DecisionRow(uuid(rs, "id"),
            uuid(rs, "workspace_id"), rs.getString("kind"), rs.getString("outcome"), uuid(rs, "environment_id"),
            uuid(rs, "definition_id"), uuid(rs, "deployment_id"), uuid(rs, "release_id"), uuid(rs, "cohort_id"),
            (Long) rs.getObject("policy_version", Long.class), rs.getString("policy_digest"),
            rs.getString("gate_results_json"), rs.getString("actor"), rs.getString("reason"), uuid(rs, "revision_id"),
            uuid(rs, "routing_generation_id"), instant(rs, "created_at"));
    private static final RowMapper<RoutingGenerationRow> GENERATIONS = (rs, n) -> new RoutingGenerationRow(
            uuid(rs, "id"), uuid(rs, "workspace_id"), uuid(rs, "deployment_id"), rs.getLong("generation"),
            rs.getString("policy"), uuid(rs, "decision_id"), uuid(rs, "previous_generation_id"), instant(rs, "created_at"));

    private final JdbcTemplate jdbc;
    private final Dialect dialect;

    public JdbcLifecycleRepository(JdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.dialect = new Dialect(jdbc);
    }

    // Releases and aliases

    public void insertRelease(ReleaseRow row) {
        try {
            insertReleaseRow(row);
        } catch (DuplicateKeyException concurrentName) {
            throw new ConcurrentInsertException(concurrentName);
        }
    }

    private void insertReleaseRow(ReleaseRow row) {
        jdbc.update("insert into agent_release(workspace_id, id, definition_id, version_id, release_name, manifest_json, "
                        + "manifest_digest, created_at) values (?, ?, ?, ?, ?, ?, ?, ?)", workspace(), row.id(),
                row.definitionId(), row.versionId(), row.name(), row.manifestJson(), row.manifestDigest(),
                timestamp(row.createdAt()));
        if (!dialect.releaseLifecycleAvailable()) return;
        jdbc.update("insert into release_lifecycle(workspace_id, release_id, state, lifecycle_version, reason, actor, "
                        + "changed_at) values (?, ?, 'AVAILABLE', 1, 'STRUCTURE_VERIFIED', 'system:release', ?)",
                workspace(), row.id(), timestamp(row.createdAt()));
        jdbc.update("insert into release_lifecycle_event(workspace_id, id, release_id, lifecycle_version, from_state, "
                        + "to_state, reason, actor, occurred_at) values (?, ?, ?, 1, null, 'AVAILABLE', "
                        + "'STRUCTURE_VERIFIED', 'system:release', ?)", workspace(), UUID.randomUUID(), row.id(),
                timestamp(row.createdAt()));
    }

    @Override
    public Optional<ReleaseRow> findRelease(UUID id) {
        return first(jdbc.query("select " + RELEASE + " from agent_release where workspace_id = ? and id = ?",
                RELEASES, workspace(), id));
    }

    public Optional<ReleaseRow> findReleaseByName(String name) {
        return first(jdbc.query("select " + RELEASE + " from agent_release where workspace_id = ? and release_name = ?",
                RELEASES, workspace(), name));
    }

    public List<ReleaseRow> listReleases() {
        return jdbc.query("select " + RELEASE + " from agent_release where workspace_id = ? order by created_at, release_name",
                RELEASES, workspace());
    }

    /** One keyset page of releases ordered by creation time, then id, after the given position (issue #35). */
    public List<ReleaseRow> listReleases(Instant afterCreatedAt, UUID afterId, int limit) {
        if ((afterCreatedAt == null) != (afterId == null)) throw new IllegalArgumentException("a position names both keys");
        if (afterCreatedAt == null) {
            return jdbc.query("select " + RELEASE + " from agent_release where workspace_id = ? order by created_at, id "
                    + "fetch first " + limit + " rows only", RELEASES, workspace());
        }
        return jdbc.query("select " + RELEASE + " from agent_release where workspace_id = ? and (created_at, id) > (?, ?) "
                        + "order by created_at, id fetch first " + limit + " rows only",
                RELEASES, workspace(), timestamp(afterCreatedAt), afterId);
    }

    /** Stores the one immutable canonical signed bundle of a release. A retry must be byte-identical. */
    public void insertReleaseBundle(ReleaseBundleRow row) {
        Optional<ReleaseBundleRow> existing = findReleaseBundle(row.releaseId());
        if (existing.isPresent()) {
            if (!existing.get().bundleDigest().equals(row.bundleDigest())) {
                throw new IllegalStateException("a different signed bundle already exists for release " + row.releaseId());
            }
            return;
        }
        try {
            jdbc.update("insert into release_evidence_bundle(workspace_id, release_id, bundle_json, bundle_digest, "
                            + "manifest_digest, signing_key_id, signed_at, imported_at) values (?, ?, ?, ?, ?, ?, ?, ?)",
                    workspace(), row.releaseId(), row.bundleJson(), row.bundleDigest(), row.manifestDigest(),
                    row.signingKeyId(), timestamp(row.signedAt()), timestamp(row.importedAt()));
        } catch (DuplicateKeyException concurrentInsert) {
            throw new ConcurrentInsertException(concurrentInsert);
        }
    }

    public Optional<ReleaseBundleRow> findReleaseBundle(UUID releaseId) {
        return first(jdbc.query("select workspace_id, release_id, bundle_json, bundle_digest, manifest_digest, "
                        + "signing_key_id, signed_at, imported_at from release_evidence_bundle "
                        + "where workspace_id = ? and release_id = ?",
                (rs, n) -> new ReleaseBundleRow(uuid(rs, "workspace_id"), uuid(rs, "release_id"),
                        rs.getString("bundle_json"), rs.getString("bundle_digest"), rs.getString("manifest_digest"),
                        rs.getString("signing_key_id"), instant(rs, "signed_at"), instant(rs, "imported_at")),
                workspace(), releaseId));
    }

    public Optional<ReleaseLifecycleRow> findReleaseLifecycle(UUID releaseId) {
        return first(jdbc.query("select workspace_id, release_id, state, lifecycle_version, reason, actor, changed_at "
                        + "from release_lifecycle where workspace_id = ? and release_id = ?",
                (rs, n) -> new ReleaseLifecycleRow(uuid(rs, "workspace_id"), uuid(rs, "release_id"),
                        rs.getString("state"), rs.getLong("lifecycle_version"), rs.getString("reason"),
                        rs.getString("actor"), instant(rs, "changed_at")), workspace(), releaseId));
    }

    public Optional<ReleaseLifecycleRow> lockReleaseLifecycle(UUID releaseId) {
        return first(jdbc.query("select workspace_id, release_id, state, lifecycle_version, reason, actor, changed_at "
                        + "from release_lifecycle where workspace_id = ? and release_id = ?" + dialect.exclusive(),
                (rs, n) -> new ReleaseLifecycleRow(uuid(rs, "workspace_id"), uuid(rs, "release_id"),
                        rs.getString("state"), rs.getLong("lifecycle_version"), rs.getString("reason"),
                        rs.getString("actor"), instant(rs, "changed_at")), workspace(), releaseId));
    }

    public boolean transitionRelease(ReleaseLifecycleRow current, String next, String reason, String actor,
            UUID revocationId, Instant now) {
        int changed = jdbc.update("update release_lifecycle set state = ?, lifecycle_version = lifecycle_version + 1, "
                        + "reason = ?, actor = ?, changed_at = ? where workspace_id = ? and release_id = ? "
                        + "and lifecycle_version = ?", next, reason, actor, timestamp(now), workspace(),
                current.releaseId(), current.lifecycleVersion());
        if (changed != 1) return false;
        jdbc.update("insert into release_lifecycle_event(workspace_id, id, release_id, lifecycle_version, from_state, "
                        + "to_state, reason, actor, revocation_id, occurred_at) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                workspace(), UUID.randomUUID(), current.releaseId(), current.lifecycleVersion() + 1, current.state(),
                next, reason, actor, revocationId, timestamp(now));
        return true;
    }

    public void insertReleaseRevocation(ReleaseRevocationRow row) {
        jdbc.update("insert into release_revocation(workspace_id, id, release_id, subject_kind, subject_identity, "
                        + "reason, evidence_digest, actor, occurred_at) values (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                workspace(), row.id(), row.releaseId(), row.subjectKind(), row.subjectIdentity(), row.reason(),
                row.evidenceDigest(), row.actor(), timestamp(row.occurredAt()));
    }

    public List<ReleaseRevocationRow> listReleaseRevocations(UUID releaseId, String subjectKind,
            Instant beforeOccurredAt, UUID beforeId, int limit) {
        if ((beforeOccurredAt == null) != (beforeId == null)) throw new IllegalArgumentException("a position names both keys");
        StringBuilder sql = new StringBuilder("select id, release_id, subject_kind, subject_identity, reason, "
                + "evidence_digest, actor, occurred_at from release_revocation where workspace_id = ?");
        List<Object> args = new java.util.ArrayList<>(); args.add(workspace());
        if (releaseId != null) { sql.append(" and release_id = ?"); args.add(releaseId); }
        if (subjectKind != null) { sql.append(" and subject_kind = ?"); args.add(subjectKind); }
        if (beforeOccurredAt != null) {
            sql.append(" and (occurred_at < ? or (occurred_at = ? and id < ?))");
            args.add(timestamp(beforeOccurredAt)); args.add(timestamp(beforeOccurredAt)); args.add(beforeId);
        }
        sql.append(" order by occurred_at desc, id desc fetch first ").append(limit).append(" rows only");
        return jdbc.query(sql.toString(), (rs, n) -> new ReleaseRevocationRow(uuid(rs, "id"),
                uuid(rs, "release_id"), rs.getString("subject_kind"), rs.getString("subject_identity"),
                rs.getString("reason"), rs.getString("evidence_digest"), rs.getString("actor"),
                instant(rs, "occurred_at")), args.toArray());
    }

    public Optional<AliasRow> lockAlias(String name) {
        return first(jdbc.query("select workspace_id, name, release_id, alias_version, updated_at from release_alias "
                        + "where workspace_id = ? and name = ?" + dialect.exclusive(),
                (rs, n) -> new AliasRow(uuid(rs, "workspace_id"), rs.getString("name"), uuid(rs, "release_id"),
                        rs.getLong("alias_version"), instant(rs, "updated_at")), workspace(), name));
    }

    public void insertAlias(String name, UUID releaseId, Instant now) {
        try {
            insertAliasRow(name, releaseId, now);
        } catch (DuplicateKeyException concurrentCreate) {
            throw new ConcurrentInsertException(concurrentCreate);
        }
    }

    private void insertAliasRow(String name, UUID releaseId, Instant now) {
        jdbc.update("insert into release_alias(workspace_id, name, release_id, alias_version, updated_at) values (?, ?, ?, 1, ?)",
                workspace(), name, releaseId, timestamp(now));
    }

    public boolean moveAlias(String name, UUID releaseId, long expectedVersion, Instant now) {
        return jdbc.update("update release_alias set release_id = ?, alias_version = alias_version + 1, updated_at = ? "
                        + "where workspace_id = ? and name = ? and alias_version = ?",
                releaseId, timestamp(now), workspace(), name, expectedVersion) == 1;
    }

    public void insertAliasHistory(String name, long version, UUID releaseId, UUID previousReleaseId, Instant now) {
        jdbc.update("insert into release_alias_history(workspace_id, id, alias_name, alias_version, release_id, "
                        + "previous_release_id, changed_at) values (?, ?, ?, ?, ?, ?, ?)", workspace(), UUID.randomUUID(),
                name, version, releaseId, previousReleaseId, timestamp(now));
    }

    public List<AliasHistoryRow> aliasHistory(String name) {
        return jdbc.query("select id, alias_name, alias_version, release_id, previous_release_id, changed_at "
                        + "from release_alias_history where workspace_id = ? and alias_name = ? order by alias_version",
                (rs, n) -> new AliasHistoryRow(uuid(rs, "id"), rs.getString("alias_name"), rs.getLong("alias_version"),
                        uuid(rs, "release_id"), uuid(rs, "previous_release_id"), instant(rs, "changed_at")),
                workspace(), name);
    }

    // Environments

    public void insertEnvironment(EnvironmentRow row) {
        jdbc.update("insert into agent_environment(workspace_id, id, name, description, policy_version, "
                        + "promotion_policy_json, promotion_policy_digest, created_at) values (?, ?, ?, ?, ?, ?, ?, ?)",
                workspace(), row.id(), row.name(), row.description(), row.policyVersion(), row.policyJson(),
                row.policyDigest(), timestamp(row.createdAt()));
        insertPolicyVersion(row.id(), row.policyVersion(), row.policyJson(), row.policyDigest(), row.createdAt());
    }

    public Optional<EnvironmentRow> findEnvironment(UUID id) {
        return first(jdbc.query("select " + ENVIRONMENT + " from agent_environment where workspace_id = ? and id = ?",
                ENVIRONMENTS, workspace(), id));
    }

    public Optional<EnvironmentRow> findEnvironmentByName(String name) {
        return first(jdbc.query("select " + ENVIRONMENT + " from agent_environment where workspace_id = ? and name = ?",
                ENVIRONMENTS, workspace(), name));
    }

    /** Serializes promotions in one environment with policy updates, so each decision evaluates one policy version. */
    public Optional<EnvironmentRow> lockEnvironmentForPromotion(UUID id) {
        return first(jdbc.query("select " + ENVIRONMENT + " from agent_environment where workspace_id = ? and id = ?"
                + dialect.exclusive(),
                ENVIRONMENTS, workspace(), id));
    }

    public List<EnvironmentRow> listEnvironments() {
        return jdbc.query("select " + ENVIRONMENT + " from agent_environment where workspace_id = ? order by name",
                ENVIRONMENTS, workspace());
    }

    /** One keyset page of environments ordered by name, then id, after the given position (issue #35). */
    public List<EnvironmentRow> listEnvironments(String afterName, UUID afterId, int limit) {
        if ((afterName == null) != (afterId == null)) throw new IllegalArgumentException("a position names both keys");
        if (afterName == null) {
            return jdbc.query("select " + ENVIRONMENT + " from agent_environment where workspace_id = ? order by name, id "
                    + "fetch first " + limit + " rows only", ENVIRONMENTS, workspace());
        }
        return jdbc.query("select " + ENVIRONMENT + " from agent_environment where workspace_id = ? and (name, id) > (?, ?) "
                        + "order by name, id fetch first " + limit + " rows only",
                ENVIRONMENTS, workspace(), afterName, afterId);
    }

    public boolean updatePolicy(UUID environmentId, long expectedVersion, String policyJson, String digest, Instant now) {
        if (jdbc.update("update agent_environment set policy_version = policy_version + 1, promotion_policy_json = ?, "
                        + "promotion_policy_digest = ? where workspace_id = ? and id = ? and policy_version = ?",
                policyJson, digest, workspace(), environmentId, expectedVersion) != 1) {
            return false;
        }
        insertPolicyVersion(environmentId, expectedVersion + 1, policyJson, digest, now);
        return true;
    }

    private void insertPolicyVersion(UUID environmentId, long version, String json, String digest, Instant now) {
        jdbc.update("insert into promotion_policy_version(workspace_id, environment_id, policy_version, policy_json, "
                + "policy_digest, created_at) values (?, ?, ?, ?, ?, ?)", workspace(), environmentId, version, json,
                digest, timestamp(now));
    }

    /** True when the release was ever the revision content of a deployment of its agent in the environment. */
    public boolean releaseDeployedInEnvironment(UUID releaseId, UUID environmentId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("select count(*) > 0 from deployment_revision "
                + "where workspace_id = ? and release_id = ? and environment_id = ?", Boolean.class, workspace(),
                releaseId, environmentId));
    }

    // Deployments

    public void insertDeployment(DeploymentRow row) {
        try {
            insertDeploymentRow(row);
        } catch (DuplicateKeyException concurrentFirstPromotion) {
            throw new ConcurrentInsertException(concurrentFirstPromotion);
        }
    }

    private void insertDeploymentRow(DeploymentRow row) {
        jdbc.update("insert into agent_deployment(workspace_id, id, environment_id, desired_release_id, revision, "
                        + "definition_id, name, created_at) values (?, ?, ?, ?, 0, ?, ?, ?)", workspace(), row.id(),
                row.environmentId(), row.desiredReleaseId(), row.definitionId(), row.name(), timestamp(row.createdAt()));
    }

    @Override
    public Optional<DeploymentRow> findDeployment(UUID id) {
        return first(jdbc.query("select " + DEPLOYMENT + " from agent_deployment where workspace_id = ? and id = ?",
                DEPLOYMENTS, workspace(), id));
    }

    public Optional<DeploymentRow> lockDeployment(UUID id) {
        return first(jdbc.query("select " + DEPLOYMENT + " from agent_deployment where workspace_id = ? and id = ?"
                + dialect.exclusive(),
                DEPLOYMENTS, workspace(), id));
    }

    public Optional<DeploymentRow> lockDeploymentByName(UUID environmentId, String name) {
        return first(jdbc.query("select " + DEPLOYMENT + " from agent_deployment where workspace_id = ? "
                + "and environment_id = ? and name = ?" + dialect.exclusive(), DEPLOYMENTS, workspace(), environmentId, name));
    }

    public List<DeploymentRow> listDeployments() {
        return jdbc.query("select " + DEPLOYMENT + " from agent_deployment where workspace_id = ? order by created_at, name",
                DEPLOYMENTS, workspace());
    }

    /**
     * One keyset page of deployments ordered by creation time, then id, after the given position, optionally of one
     * environment (issue #35).
     */
    public List<DeploymentRow> listDeployments(UUID environmentId, Instant afterCreatedAt, UUID afterId, int limit) {
        if ((afterCreatedAt == null) != (afterId == null)) throw new IllegalArgumentException("a position names both keys");
        List<Object> arguments = new java.util.ArrayList<>();
        StringBuilder sql = new StringBuilder("select " + DEPLOYMENT + " from agent_deployment where workspace_id = ?");
        arguments.add(workspace());
        if (environmentId != null) {
            sql.append(" and environment_id = ?");
            arguments.add(environmentId);
        }
        if (afterCreatedAt != null) {
            // A row-value comparison lets the index seek to the position instead of filtering earlier rows.
            sql.append(" and (created_at, id) > (?, ?)");
            arguments.add(timestamp(afterCreatedAt));
            arguments.add(afterId);
        }
        sql.append(" order by created_at, id fetch first ").append(limit).append(" rows only");
        return jdbc.query(sql.toString(), DEPLOYMENTS, arguments.toArray());
    }

    /** Compare-and-set advance of current desired state; false when another command advanced it first. */
    public boolean advanceDeployment(UUID id, long expectedGeneration, UUID releaseId, UUID revisionId,
            UUID routingGenerationId) {
        return jdbc.update("update agent_deployment set revision = ?, desired_release_id = ?, current_revision_id = ?, "
                        + "current_routing_generation_id = ? where workspace_id = ? and id = ? and revision = ?",
                expectedGeneration + 1, releaseId, revisionId, routingGenerationId, workspace(), id,
                expectedGeneration) == 1;
    }

    // Revisions

    public void insertRevision(RevisionRow row) {
        jdbc.update("insert into deployment_revision(" + REVISION + ") values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                row.id(), workspace(), row.deploymentId(), row.generation(), row.releaseId(), row.definitionId(),
                row.environmentId(), row.placement(), row.configurationJson(), row.contentDigest(), row.cause(),
                row.decisionId(), row.previousRevisionId(), timestamp(row.createdAt()));
    }

    @Override
    public Optional<RevisionRow> findRevision(UUID id) {
        return first(jdbc.query("select " + REVISION + " from deployment_revision where workspace_id = ? and id = ?",
                REVISIONS, workspace(), id));
    }

    public List<RevisionRow> listRevisions(UUID deploymentId) {
        return jdbc.query("select " + REVISION + " from deployment_revision where workspace_id = ? and deployment_id = ? "
                + "order by generation", REVISIONS, workspace(), deploymentId);
    }

    public boolean releaseEverDeployed(UUID deploymentId, UUID releaseId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("select count(*) > 0 from deployment_revision "
                + "where workspace_id = ? and deployment_id = ? and release_id = ?", Boolean.class, workspace(),
                deploymentId, releaseId));
    }

    // Cohorts

    public void insertCohort(UUID id, UUID deploymentId, UUID revisionId, Instant now) {
        jdbc.update("insert into runtime_cohort(workspace_id, id, deployment_id, revision_id, state, transition_version, "
                + "state_changed_at, created_at) values (?, ?, ?, ?, 'PENDING', 1, ?, ?)", workspace(), id, deploymentId,
                revisionId, timestamp(now), timestamp(now));
    }

    @Override
    public Optional<CohortRow> findCohort(UUID id) {
        return first(jdbc.query("select " + COHORT + " from runtime_cohort c where c.workspace_id = ? and c.id = ?",
                COHORTS, workspace(), id));
    }

    @Override
    public Optional<CohortRow> findCohortForRevision(UUID revisionId) {
        return first(jdbc.query("select " + COHORT + " from runtime_cohort c where c.workspace_id = ? "
                        + "and c.revision_id = ?", COHORTS, workspace(), revisionId));
    }

    public Optional<CohortRow> lockCohort(UUID id) {
        return first(jdbc.query("select " + COHORT + " from runtime_cohort c where c.workspace_id = ? and c.id = ?"
                + dialect.exclusive(),
                COHORTS, workspace(), id));
    }

    /** Locks every cohort of a deployment in the documented deterministic order (revision generation). */
    public List<CohortRow> lockCohorts(UUID deploymentId, List<String> states) {
        String placeholders = String.join(", ", states.stream().map(state -> "?").toList());
        Object[] arguments = new Object[states.size() + 2];
        arguments[0] = workspace();
        arguments[1] = deploymentId;
        for (int index = 0; index < states.size(); index++) arguments[index + 2] = states.get(index);
        return jdbc.query("select " + COHORT + " from runtime_cohort c join deployment_revision r "
                + "on r.workspace_id = c.workspace_id and r.id = c.revision_id where c.workspace_id = ? "
                + "and c.deployment_id = ? and c.state in (" + placeholders + ") order by r.generation"
                + dialect.exclusiveOf("c"),
                COHORTS, arguments);
    }

    public List<CohortRow> listCohorts(UUID deploymentId) {
        return jdbc.query("select " + COHORT + " from runtime_cohort c join deployment_revision r "
                + "on r.workspace_id = c.workspace_id and r.id = c.revision_id where c.workspace_id = ? "
                + "and c.deployment_id = ? order by r.generation", COHORTS, workspace(), deploymentId);
    }

    /** Deployment identities using a release, sorted for deterministic multi-deployment locking. */
    public List<UUID> deploymentIdsForRelease(UUID releaseId) {
        return jdbc.query("select distinct deployment_id from deployment_revision where workspace_id = ? "
                        + "and release_id = ? order by deployment_id", (rs, n) -> uuid(rs, "deployment_id"),
                workspace(), releaseId);
    }

    /** Locks the cohorts of one deployment/release in revision-generation order. */
    public List<CohortRow> lockCohortsForRelease(UUID deploymentId, UUID releaseId) {
        return jdbc.query("select " + COHORT + " from runtime_cohort c join deployment_revision r "
                        + "on r.workspace_id = c.workspace_id and r.id = c.revision_id where c.workspace_id = ? "
                        + "and c.deployment_id = ? and r.release_id = ? order by r.generation" + dialect.exclusiveOf("c"),
                COHORTS, workspace(), deploymentId, releaseId);
    }

    /** Version-checked transition that also appends the immutable transition record. */
    public boolean transitionCohort(CohortRow cohort, String toState, String cause, UUID decisionId, String actor,
            String reason, Instant now, Instant drainDeadline) {
        long next = cohort.transitionVersion() + 1;
        int updated = jdbc.update("update runtime_cohort set state = ?, transition_version = ?, state_changed_at = ?, "
                        + "drain_started_at = case when ? = 'DRAINING' and drain_started_at is null then ? else drain_started_at end, "
                        + "drain_deadline = case when ? = 'DRAINING' and drain_started_at is null then ? else drain_deadline end, "
                        + "quiescent_since = case when ? = 'QUIESCENT' then ? else null end, "
                        + "retired_at = case when ? = 'RETIRED' then ? else retired_at end "
                        + "where workspace_id = ? and id = ? and transition_version = ?",
                toState, next, timestamp(now), toState, timestamp(now), toState, timestamp(drainDeadline), toState,
                timestamp(now), toState, timestamp(now), workspace(), cohort.id(), cohort.transitionVersion());
        if (updated != 1) return false;
        jdbc.update("insert into cohort_transition(workspace_id, id, cohort_id, transition_version, from_state, to_state, "
                        + "cause, decision_id, actor, reason, occurred_at) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                workspace(), UUID.randomUUID(), cohort.id(), next, cohort.state(), toState, cause, decisionId, actor,
                reason, timestamp(now));
        return true;
    }

    public void recordInitialTransition(UUID cohortId, String actor, UUID decisionId, Instant now) {
        jdbc.update("insert into cohort_transition(workspace_id, id, cohort_id, transition_version, from_state, to_state, "
                        + "cause, decision_id, actor, occurred_at) values (?, ?, ?, 1, null, 'PENDING', 'CREATED', ?, ?, ?)",
                workspace(), UUID.randomUUID(), cohortId, decisionId, actor, timestamp(now));
    }

    public List<TransitionRow> transitions(UUID cohortId) {
        return jdbc.query("select id, cohort_id, transition_version, from_state, to_state, cause, decision_id, actor, "
                        + "reason, occurred_at from cohort_transition where workspace_id = ? and cohort_id = ? "
                        + "order by transition_version",
                (rs, n) -> new TransitionRow(uuid(rs, "id"), uuid(rs, "cohort_id"), rs.getLong("transition_version"),
                        rs.getString("from_state"), rs.getString("to_state"), rs.getString("cause"),
                        uuid(rs, "decision_id"), rs.getString("actor"), rs.getString("reason"),
                        instant(rs, "occurred_at")), workspace(), cohortId);
    }

    /**
     * Durable work that keeps a cohort from being quiescent: non-terminal runs, then every non-terminal
     * {@code cohort_work_item} ledger row by category (claims, pending operations, restorable checkpoints, retained
     * sandboxes, and the #57 categories). Every category in
     * {@code config/lifecycle/resumable-work-categories.json} is counted here and
     * participates in the cohort fence. A run that a claim quarantine is holding back (#181) is reported as
     * {@code QUARANTINED_RUN}, with state {@code <reason> <code> held_back_for=<identity> other_identities_live=unknown},
     * so an operator can see that the cohort waits for a release, cancellation, or failure of that run (the
     * administrative surface is #79) rather than for work in progress, and which nodes refuse it. The run is held back
     * only for the nodes of that identity: nodes of another identity ignore the quarantine, and whether any is running
     * and can claim the run is not recorded ({@code other_identities_live=unknown}), so the blocker alone is no reason to
     * cancel the run (#185). A quarantine holds a run back only while it is in force, the run is otherwise claimable,
     * and the cohort admits claims ({@code ClaimQuarantineSql.holdsBack}, the predicate the Control Plane listing
     * uses too, #189): a run that another worker holds under a live lease, typically a node of another compatibility
     * identity that can run it, is reported as the {@code NON_TERMINAL_RUN} it is, and so is a blocked or paused one,
     * or any run of a cohort that admits no claims. A run held back by quarantines of several identities is counted
     * under each of them.
     */
    public List<QuiescenceBlocker> quiescenceBlockers(UUID cohortId) {
        String now = com.dvarahq.agentworks.storage.run.ClaimQuarantineSql.now(dialect.postgres());
        String heldBack = com.dvarahq.agentworks.storage.run.ClaimQuarantineSql.holdsBack("q", "r", "c", now);
        String notEnded = com.dvarahq.agentworks.storage.run.ClaimQuarantineSql.notEnded("r");
        String cohort = " join runtime_cohort c on c.workspace_id = r.workspace_id and c.id = r.cohort_id ";
        List<QuiescenceBlocker> blockers = new java.util.ArrayList<>(jdbc.query(
                "select r.lifecycle, count(*) as blocked, min(r.created_at) as oldest from agent_run r" + cohort
                        + "where r.workspace_id = ? and r.cohort_id = ? "
                        + "and " + notEnded + " and not exists (select 1 from "
                        + "agent_run_claim_quarantine q where q.workspace_id = r.workspace_id and q.run_id = r.id and "
                        + heldBack + ") group by r.lifecycle order by r.lifecycle",
                (rs, n) -> new QuiescenceBlocker("NON_TERMINAL_RUN", rs.getString("lifecycle"), rs.getLong("blocked"),
                        instant(rs, "oldest")), workspace(), cohortId));
        blockers.addAll(jdbc.query("select q.reason, q.code, q.compatibility, count(distinct r.id) as blocked, "
                        + "min(r.created_at) as oldest from agent_run r" + cohort + "join agent_run_claim_quarantine q on "
                        + "q.workspace_id = r.workspace_id and q.run_id = r.id and " + heldBack
                        + " where r.workspace_id = ? and r.cohort_id = ? "
                        + "group by q.reason, q.code, q.compatibility order by q.reason, q.code, q.compatibility",
                (rs, n) -> new QuiescenceBlocker("QUARANTINED_RUN", rs.getString("reason") + " " + rs.getString("code")
                        + " held_back_for=" + rs.getString("compatibility") + " other_identities_live=unknown",
                        rs.getLong("blocked"),
                        instant(rs, "oldest")), workspace(), cohortId));
        blockers.addAll(jdbc.query("select category, state, count(*) as blocked, min(created_at) as oldest "
                        + "from cohort_work_item where workspace_id = ? and cohort_id = ? and state <> 'TERMINAL' "
                        + "group by category, state order by category, state",
                (rs, n) -> new QuiescenceBlocker(workBlocker(rs.getString("category")), rs.getString("state"),
                        rs.getLong("blocked"), instant(rs, "oldest")), workspace(), cohortId));
        return blockers;
    }

    /** The typed blocker name of a resumable-work category, as catalogued in resumable-work-categories.json. */
    static String workBlocker(String category) {
        return switch (category) {
            case "CLAIM" -> "ACTIVE_CLAIM";
            case "OPERATION" -> "PENDING_OPERATION";
            case "CHECKPOINT" -> "RESTORABLE_CHECKPOINT";
            case "SANDBOX" -> "RETAINED_SANDBOX";
            default -> "PENDING_" + category;
        };
    }

    // Routing

    public void insertRoutingGeneration(RoutingGenerationRow row, List<RoutingTargetRow> targets) {
        jdbc.update("insert into routing_generation(workspace_id, id, deployment_id, generation, policy, decision_id, "
                        + "previous_generation_id, created_at) values (?, ?, ?, ?, ?, ?, ?, ?)", workspace(), row.id(),
                row.deploymentId(), row.generation(), row.policy(), row.decisionId(), row.previousGenerationId(),
                timestamp(row.createdAt()));
        for (RoutingTargetRow target : targets) {
            jdbc.update("insert into routing_target(workspace_id, generation_id, ordinal, cohort_id, revision_id, weight) "
                    + "values (?, ?, ?, ?, ?, ?)", workspace(), row.id(), target.ordinal(), target.cohortId(),
                    target.revisionId(), target.weight());
        }
    }

    @Override
    public Optional<RoutingGenerationRow> findRoutingGeneration(UUID id) {
        return first(jdbc.query("select id, workspace_id, deployment_id, generation, policy, decision_id, "
                + "previous_generation_id, created_at from routing_generation where workspace_id = ? and id = ?",
                GENERATIONS, workspace(), id));
    }

    public List<RoutingGenerationRow> listRoutingGenerations(UUID deploymentId) {
        return jdbc.query("select id, workspace_id, deployment_id, generation, policy, decision_id, previous_generation_id, "
                + "created_at from routing_generation where workspace_id = ? and deployment_id = ? order by generation",
                GENERATIONS, workspace(), deploymentId);
    }

    public long nextRoutingGeneration(UUID deploymentId) {
        return Objects.requireNonNull(jdbc.queryForObject("select coalesce(max(generation), 0) + 1 from routing_generation "
                + "where workspace_id = ? and deployment_id = ?", Long.class, workspace(), deploymentId));
    }

    @Override
    public List<RoutingTargetRow> routingTargets(UUID generationId) {
        return jdbc.query("select generation_id, ordinal, cohort_id, revision_id, weight from routing_target "
                        + "where workspace_id = ? and generation_id = ? order by ordinal",
                (rs, n) -> new RoutingTargetRow(uuid(rs, "generation_id"), rs.getInt("ordinal"), uuid(rs, "cohort_id"),
                        uuid(rs, "revision_id"), rs.getInt("weight")), workspace(), generationId);
    }

    // Decisions and outbox

    public void insertDecision(DecisionRow row) {
        jdbc.update("insert into lifecycle_decision(" + DECISION + ") values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                row.id(), workspace(), row.kind(), row.outcome(), row.environmentId(), row.definitionId(),
                row.deploymentId(), row.releaseId(), row.cohortId(), row.policyVersion(), row.policyDigest(),
                row.gateResultsJson(), row.actor(), row.reason(), row.revisionId(), row.routingGenerationId(),
                timestamp(row.createdAt()));
    }

    public Optional<DecisionRow> findDecision(UUID id) {
        return first(jdbc.query("select " + DECISION + " from lifecycle_decision where workspace_id = ? and id = ?",
                DECISIONS, workspace(), id));
    }

    public List<DecisionRow> listDecisions(UUID environmentId) {
        return jdbc.query("select " + DECISION + " from lifecycle_decision where workspace_id = ? and environment_id = ? "
                + "order by created_at, id", DECISIONS, workspace(), environmentId);
    }

    public void appendOutbox(String eventType, UUID decisionId, String payloadJson, Instant now) {
        jdbc.update("insert into control_plane_outbox(workspace_id, id, event_type, decision_id, payload_json, created_at) "
                + "values (?, ?, ?, ?, ?, ?)", workspace(), UUID.randomUUID(), eventType, decisionId, payloadJson,
                timestamp(now));
    }

    public List<OutboxRow> outbox() {
        return jdbc.query("select id, sequence_number, event_type, decision_id, payload_json, created_at "
                        + "from control_plane_outbox where workspace_id = ? order by sequence_number",
                (rs, n) -> new OutboxRow(uuid(rs, "id"), rs.getLong("sequence_number"), rs.getString("event_type"),
                        uuid(rs, "decision_id"), rs.getString("payload_json"), instant(rs, "created_at")), workspace());
    }

    static UUID workspace() {
        return TenantContext.requireWorkspace().value();
    }

    static <T> Optional<T> first(List<T> rows) {
        return rows.stream().findFirst();
    }

    static UUID uuid(ResultSet rs, String column) throws SQLException {
        return rs.getObject(column, UUID.class);
    }

    static Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    static OffsetDateTime timestamp(Instant instant) {
        return instant == null ? null : OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
