package com.dvarahq.agentworks.autoconfigure;

import com.dvarahq.agentworks.controlplane.deployment.HumanApprovalEvidenceSource;
import com.dvarahq.agentworks.controlplane.lifecycle.LifecycleJson;
import com.dvarahq.agentworks.controlplane.lifecycle.LifecycleConflictException;
import com.dvarahq.agentworks.controlplane.lifecycle.LifecycleNotFoundException;
import com.dvarahq.agentworks.controlplane.lifecycle.LifecycleValidation;
import com.dvarahq.agentworks.controlplane.lifecycle.LifecycleModel;
import com.dvarahq.agentworks.domain.tenant.WorkspaceId;
import com.dvarahq.agentworks.storage.TenantTransactionTemplate;
import com.dvarahq.agentworks.storage.lifecycle.DatabaseClock;
import com.dvarahq.agentworks.storage.lifecycle.Digests;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Durable Control Plane authority for an explicit, environment/policy/release-bound promotion approval. */
public final class JdbcPromotionApprovalAuthority implements HumanApprovalEvidenceSource {
    public record Approval(UUID id, UUID workspaceId, UUID environmentId, String policyDigest, UUID releaseId,
            String status, String requestedBy, String approvedBy, Instant createdAt, Instant approvedAt) {}

    private final JdbcTemplate jdbc;
    private final TenantTransactionTemplate tenants;
    private final DatabaseClock clock;

    public JdbcPromotionApprovalAuthority(JdbcTemplate jdbc, TenantTransactionTemplate tenants, DatabaseClock clock) {
        this.jdbc = jdbc; this.tenants = tenants; this.clock = clock;
    }

    public Approval request(WorkspaceId workspace, UUID environmentId, String policyDigest, UUID releaseId,
            String actor) {
        if (releaseId == null) throw LifecycleValidation.invalid("/releaseId", "is required");
        UUID id = UUID.randomUUID();
        Instant now = clock.now();
        tenants.executeWithoutResult(workspace, () -> {
            Integer present = jdbc.queryForObject("select count(*) from agent_release where workspace_id = ? and id = ?",
                    Integer.class, workspace.value(), releaseId);
            if (present == null || present == 0) throw new LifecycleNotFoundException();
            jdbc.update("insert into promotion_approval(workspace_id, id, environment_id, policy_digest, release_id, "
                    + "status, requested_by, created_at) values (?, ?, ?, ?, ?, 'REQUESTED', ?, ?)",
                    workspace.value(), id, environmentId, policyDigest, releaseId, actor,
                    OffsetDateTime.ofInstant(now, ZoneOffset.UTC));
        });
        return find(workspace, id).orElseThrow();
    }

    public Approval approve(WorkspaceId workspace, UUID id, String actor) {
        Instant now = clock.now();
        int updated = tenants.execute(workspace, () -> jdbc.update("update promotion_approval set status = 'APPROVED', "
                + "approved_by = ?, approved_at = ? where workspace_id = ? and id = ? and status = 'REQUESTED'",
                actor, OffsetDateTime.ofInstant(now, ZoneOffset.UTC), workspace.value(), id));
        if (updated != 1) {
            Approval existing = find(workspace, id).orElseThrow();
            if ("APPROVED".equals(existing.status())) return existing;
            throw new LifecycleConflictException("APPROVAL_NOT_AVAILABLE",
                    "promotion approval is unavailable or already resolved");
        }
        return find(workspace, id).orElseThrow();
    }

    public Optional<Approval> find(WorkspaceId workspace, UUID id) {
        return tenants.execute(workspace, () -> jdbc.query("select id, workspace_id, environment_id, policy_digest, "
                + "release_id, status, requested_by, approved_by, created_at, approved_at from promotion_approval "
                + "where workspace_id = ? and id = ?", (rs, row) -> new Approval(rs.getObject("id", UUID.class),
                        rs.getObject("workspace_id", UUID.class), rs.getObject("environment_id", UUID.class),
                        rs.getString("policy_digest"), rs.getObject("release_id", UUID.class), rs.getString("status"),
                        rs.getString("requested_by"), rs.getString("approved_by"),
                        rs.getObject("created_at", OffsetDateTime.class).toInstant(),
                        rs.getObject("approved_at", OffsetDateTime.class) == null ? null
                                : rs.getObject("approved_at", OffsetDateTime.class).toInstant()),
                workspace.value(), id).stream().findFirst());
    }

    @Override public Optional<LifecycleModel.PromotionEvidence> resolve(WorkspaceId workspace, UUID environmentId,
            String policyDigest, UUID releaseId, UUID approvalId) {
        return find(workspace, approvalId).filter(item -> "APPROVED".equals(item.status()))
                .filter(item -> environmentId.equals(item.environmentId()) && policyDigest.equals(item.policyDigest())
                        && releaseId.equals(item.releaseId()) && item.approvedBy() != null)
                .map(item -> new LifecycleModel.PromotionEvidence(item.id(),
                        LifecycleModel.EvidenceKind.HUMAN_APPROVAL, releaseId,
                        Digests.prefixed(LifecycleJson.write(Map.of("approvalId", item.id(), "environmentId",
                                environmentId, "policyDigest", policyDigest, "releaseId", releaseId,
                                "approvedBy", item.approvedBy(), "approvedAt", item.approvedAt()))),
                        "APPROVED", Map.of(), item.approvedBy(), item.approvedAt()));
    }
}
