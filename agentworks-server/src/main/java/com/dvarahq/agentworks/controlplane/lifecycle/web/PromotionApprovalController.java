package com.dvarahq.agentworks.controlplane.lifecycle.web;

import com.dvarahq.agentworks.autoconfigure.JdbcPromotionApprovalAuthority;
import com.dvarahq.agentworks.controlplane.agent.web.WorkspaceScopeResolver;
import com.dvarahq.agentworks.controlplane.deployment.EnvironmentService;
import com.dvarahq.agentworks.controlplane.lifecycle.LifecycleNotFoundException;
import com.dvarahq.agentworks.controlplane.lifecycle.LifecycleValidation;
import com.dvarahq.agentworks.controlplane.lifecycle.ReleaseService;
import com.dvarahq.agentworks.identity.web.CurrentPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** Explicit Control Plane workflow for requesting and granting promotion approval. */
@RestController
@RequestMapping(path = "/api/v1/environments/{environmentId}/promotion-approvals",
        produces = MediaType.APPLICATION_JSON_VALUE)
public final class PromotionApprovalController {
    public record Request(UUID releaseId) {}
    public record Approve(UUID releaseId, String policyDigest) {}

    private final JdbcPromotionApprovalAuthority approvals;
    private final EnvironmentService environments;
    private final ReleaseService releases;
    private final WorkspaceScopeResolver workspaces;

    public PromotionApprovalController(JdbcPromotionApprovalAuthority approvals, EnvironmentService environments,
            ReleaseService releases, WorkspaceScopeResolver workspaces) {
        this.approvals = approvals; this.environments = environments; this.releases = releases;
        this.workspaces = workspaces;
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public JdbcPromotionApprovalAuthority.Approval request(HttpServletRequest request,
            @PathVariable UUID environmentId, @RequestBody Request body) {
        var workspace = workspaces.resolve(request);
        var environment = environments.get(workspace, environmentId);
        if (body.releaseId() == null) throw LifecycleValidation.invalid("/releaseId", "is required");
        releases.getRelease(workspace, body.releaseId());
        return approvals.request(workspace, environmentId, environment.policyDigest(), body.releaseId(),
                CurrentPrincipal.actor());
    }

    @GetMapping(path = "/{approvalId}")
    public JdbcPromotionApprovalAuthority.Approval get(HttpServletRequest request,
            @PathVariable UUID environmentId, @PathVariable UUID approvalId) {
        var workspace = workspaces.resolve(request);
        var approval = approvals.find(workspace, approvalId).orElseThrow(LifecycleNotFoundException::new);
        if (!environmentId.equals(approval.environmentId())) throw new LifecycleNotFoundException();
        return approval;
    }

    @PostMapping(path = "/{approvalId}/approve", consumes = MediaType.APPLICATION_JSON_VALUE)
    public JdbcPromotionApprovalAuthority.Approval approve(HttpServletRequest request,
            @PathVariable UUID environmentId, @PathVariable UUID approvalId, @RequestBody Approve body) {
        var workspace = workspaces.resolve(request);
        var existing = approvals.find(workspace, approvalId).orElseThrow(LifecycleNotFoundException::new);
        if (!environmentId.equals(existing.environmentId())) throw new LifecycleNotFoundException();
        if (body.releaseId() == null) throw LifecycleValidation.invalid("/releaseId", "is required");
        if (body.policyDigest() == null || body.policyDigest().isBlank()) {
            throw LifecycleValidation.invalid("/policyDigest", "is required");
        }
        if (!body.releaseId().equals(existing.releaseId()) || !body.policyDigest().equals(existing.policyDigest())) {
            throw LifecycleValidation.invalid("/approval", "does not match the requested release and policy");
        }
        return approvals.approve(workspace, approvalId, CurrentPrincipal.actor());
    }
}
