package com.dvarahq.agentworks.controlplane.lifecycle.web;

import com.dvarahq.agentworks.controlplane.agent.web.WorkspaceScopeResolver;
import com.dvarahq.agentworks.controlplane.deployment.DeploymentLifecycleService;
import com.dvarahq.agentworks.controlplane.lifecycle.LifecycleModel.CohortCommand;
import com.dvarahq.agentworks.controlplane.lifecycle.LifecycleModel.CohortResult;
import com.dvarahq.agentworks.controlplane.lifecycle.LifecycleModel.DeploymentConfiguration;
import com.dvarahq.agentworks.controlplane.lifecycle.LifecycleModel.EvidenceReference;
import com.dvarahq.agentworks.controlplane.lifecycle.LifecycleModel.Idempotent;
import com.dvarahq.agentworks.controlplane.lifecycle.LifecycleModel.PromotionCommand;
import com.dvarahq.agentworks.controlplane.lifecycle.LifecycleModel.PromotionResult;
import com.dvarahq.agentworks.controlplane.lifecycle.LifecycleModel.OverrideRequest;
import com.dvarahq.agentworks.controlplane.lifecycle.LifecycleModel.QuiescenceReport;
import com.dvarahq.agentworks.controlplane.lifecycle.LifecycleModel.RetirementResult;
import com.dvarahq.agentworks.controlplane.lifecycle.LifecycleModel.RollbackCommand;
import com.dvarahq.agentworks.domain.lifecycle.state.LifecycleRows.CohortRow;
import com.dvarahq.agentworks.domain.lifecycle.state.LifecycleRows.DecisionRow;
import com.dvarahq.agentworks.domain.lifecycle.state.LifecycleRows.DeploymentRow;
import com.dvarahq.agentworks.domain.lifecycle.state.LifecycleRows.RevisionRow;
import com.dvarahq.agentworks.domain.lifecycle.state.LifecycleRows.RoutingGenerationRow;
import com.dvarahq.agentworks.domain.lifecycle.state.LifecycleRows.RoutingTargetRow;
import com.dvarahq.agentworks.identity.web.CurrentPrincipal;
import com.dvarahq.agentworks.identity.ControlActionAudit;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import com.dvarahq.agentworks.api.web.Page;
import com.dvarahq.agentworks.api.web.PageCursor;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * REST surface for promotion, deployments, cohorts, routing, and decisions (issues #31 and #35).
 * Mutations accept {@code Idempotency-Key}; replays answer {@code Idempotent-Replayed: true}. Deployment ETags are
 * revision generations and cohort ETags are transition versions; {@code If-Match} carries the expected value.
 * Decisions and cohort transitions record the authenticated principal's actor (subject and authentication method).
 */
@RestController
@RequestMapping(path = "/api/v1", produces = MediaType.APPLICATION_JSON_VALUE)
public final class DeploymentController {
    public record Promote(String deployment, UUID releaseId, DeploymentConfiguration configuration, String reason,
            List<EvidenceReference> evidence, OverrideRequest override) {
        public Promote(String deployment, UUID releaseId, DeploymentConfiguration configuration, String reason) {
            this(deployment, releaseId, configuration, reason, List.of(), null);
        }
    }
    public record Rollback(UUID releaseId, String reason, List<EvidenceReference> evidence,
            OverrideRequest override) {
        public Rollback(UUID releaseId, String reason) {
            this(releaseId, reason, List.of(), null);
        }
    }
    public record Command(String reason) {}
    public record DeploymentDetail(DeploymentRow deployment, List<RevisionRow> revisions, List<CohortRow> cohorts,
            List<RoutingGenerationRow> routingGenerations) {}

    private final DeploymentLifecycleService deployments;
    private final WorkspaceScopeResolver workspaces;
    private final ControlActionAudit audit;

    public DeploymentController(DeploymentLifecycleService deployments, WorkspaceScopeResolver workspaces) {
        this(deployments, workspaces, ControlActionAudit.NONE);
    }

    /** @param audit records deployment decisions and cohort administration in the immutable workspace audit */
    public DeploymentController(DeploymentLifecycleService deployments, WorkspaceScopeResolver workspaces,
            ControlActionAudit audit) {
        this.deployments = Objects.requireNonNull(deployments, "deployments");
        this.workspaces = Objects.requireNonNull(workspaces, "workspaces");
        this.audit = Objects.requireNonNull(audit, "audit");
    }

    /** {@code If-Match: "0"} requires a new deployment; omitting it promotes against any current generation. */
    @PostMapping(path = "/environments/{environmentId}/promotions", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<PromotionResult> promote(HttpServletRequest request, @PathVariable UUID environmentId,
            @RequestHeader(name = "Idempotency-Key", required = false) String key,
            @RequestHeader(name = "If-Match", required = false) String ifMatch, @RequestBody Promote body) {
        Idempotent<PromotionResult> result = audit.record("DEPLOYMENT_PROMOTED",
                () -> deployments.promote(workspaces.resolve(request), new PromotionCommand(environmentId,
                        body.deployment(), body.releaseId(), body.configuration(),
                        ifMatch == null ? null : ETags.version(ifMatch), key, CurrentPrincipal.actor(), body.reason(),
                        body.evidence(), body.override(), overrideAuthorized(body.override()))),
                completed -> decisionTarget(completed));
        return decision(result);
    }

    @PostMapping(path = "/deployments/{id}/rollback", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<PromotionResult> rollback(HttpServletRequest request, @PathVariable UUID id,
            @RequestHeader(name = "Idempotency-Key", required = false) String key,
            @RequestHeader("If-Match") String ifMatch, @RequestBody Rollback body) {
        return decision(audit.record("DEPLOYMENT_ROLLED_BACK",
                () -> deployments.rollback(workspaces.resolve(request),
                        new RollbackCommand(id, body.releaseId(), ETags.version(ifMatch), key, CurrentPrincipal.actor(),
                                body.reason(), body.evidence(), body.override(), overrideAuthorized(body.override()))),
                completed -> decisionTarget(completed)));
    }

    /** Deployments ordered by creation time, then id, optionally of one environment, in keyset pages (issue #35). */
    @GetMapping("/deployments")
    public Page<DeploymentRow> deployments(HttpServletRequest request,
            @RequestParam(name = "environmentId", required = false) UUID environmentId,
            @RequestParam(name = "cursor", required = false) String cursor,
            @RequestParam(name = "limit", required = false) Integer limit) {
        var workspace = workspaces.resolve(request);
        int size = PageCursor.limit(limit);
        List<String> position = PageCursor.decode(cursor, 2);
        return Page.of(deployments.deployments(workspace, environmentId,
                        position == null ? null : PageCursor.instant(position.get(0)),
                        position == null ? null : PageCursor.uuid(position.get(1)), size + 1), size,
                deployment -> PageCursor.encode(PageCursor.instant(deployment.createdAt()), deployment.id().toString()));
    }

    @GetMapping("/deployments/{id}")
    public ResponseEntity<DeploymentDetail> deployment(HttpServletRequest request, @PathVariable UUID id) {
        var workspace = workspaces.resolve(request);
        DeploymentRow deployment = deployments.deployment(workspace, id);
        return ResponseEntity.ok().eTag(ETags.of(deployment.generation())).body(new DeploymentDetail(deployment,
                deployments.revisions(workspace, id), deployments.cohorts(workspace, id),
                deployments.routingGenerations(workspace, id)));
    }

    @GetMapping("/routing-generations/{id}/targets")
    public Page<RoutingTargetRow> targets(HttpServletRequest request, @PathVariable UUID id) {
        // One routing generation's targets are a small, immutable set; the collection is never paged.
        return Page.of(deployments.routingTargets(workspaces.resolve(request), id));
    }

    @GetMapping("/decisions/{id}")
    public DecisionRow decision(HttpServletRequest request, @PathVariable UUID id) {
        return deployments.decision(workspaces.resolve(request), id);
    }

    @GetMapping("/cohorts/{id}")
    public ResponseEntity<CohortRow> cohort(HttpServletRequest request, @PathVariable UUID id) {
        CohortRow cohort = deployments.cohort(workspaces.resolve(request), id);
        return ResponseEntity.ok().eTag(ETags.of(cohort.transitionVersion())).body(cohort);
    }

    @PostMapping(path = "/cohorts/{id}/{action:suspend|resume|revoke}", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<CohortResult> transition(HttpServletRequest request, @PathVariable UUID id,
            @PathVariable String action, @RequestHeader(name = "Idempotency-Key", required = false) String key,
            @RequestHeader("If-Match") String ifMatch, @RequestBody Command body) {
        var workspace = workspaces.resolve(request);
        CohortCommand command = new CohortCommand(id, ETags.version(ifMatch), key, CurrentPrincipal.actor(), body.reason());
        String eventType = switch (action) {
            case "suspend" -> "COHORT_SUSPENDED";
            case "resume" -> "COHORT_RESUMED";
            default -> "COHORT_REVOKED";
        };
        Idempotent<CohortResult> result = audit.record(eventType, () -> switch (action) {
            case "suspend" -> deployments.suspend(workspace, command);
            case "resume" -> deployments.resume(workspace, command);
            default -> deployments.revoke(workspace, command);
        }, completed -> completed.replayed() ? null : new ControlActionAudit.Target("RUNTIME_COHORT",
                completed.value().cohortId(), completed.value().state()));
        return replayed(ResponseEntity.ok(), result.replayed()).eTag(ETags.of(result.value().transitionVersion()))
                .body(result.value());
    }

    /** 200 when retired; 409 with typed blockers when durable work remains. */
    @PostMapping(path = "/cohorts/{id}/retire", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<RetirementResult> retire(HttpServletRequest request, @PathVariable UUID id,
            @RequestHeader(name = "Idempotency-Key", required = false) String key,
            @RequestHeader("If-Match") String ifMatch, @RequestBody Command body) {
        Idempotent<RetirementResult> result = audit.record("COHORT_RETIREMENT_REQUESTED",
                () -> deployments.retire(workspaces.resolve(request),
                        new CohortCommand(id, ETags.version(ifMatch), key, CurrentPrincipal.actor(), body.reason())),
                completed -> completed.replayed() ? null : new ControlActionAudit.Target("RUNTIME_COHORT",
                        completed.value().cohortId(), completed.value().outcome()));
        HttpStatus status = "RETIRED".equals(result.value().outcome()) ? HttpStatus.OK : HttpStatus.CONFLICT;
        return replayed(ResponseEntity.status(status), result.replayed())
                .eTag(ETags.of(result.value().transitionVersion())).body(result.value());
    }

    @PostMapping("/cohorts/{id}/quiescence")
    public ResponseEntity<QuiescenceReport> quiescence(HttpServletRequest request, @PathVariable UUID id) {
        QuiescenceReport report = audit.record("COHORT_QUIESCENCE_OBSERVED",
                () -> deployments.observeQuiescence(workspaces.resolve(request), id, CurrentPrincipal.actor()),
                completed -> new ControlActionAudit.Target("RUNTIME_COHORT", completed.cohortId(), completed.state()));
        return ResponseEntity.ok().eTag(ETags.of(report.transitionVersion())).body(report);
    }

    private static ResponseEntity<PromotionResult> decision(Idempotent<PromotionResult> result) {
        PromotionResult value = result.value();
        var response = replayed(ResponseEntity.status(HttpStatus.CREATED), result.replayed())
                .location(URI.create("/api/v1/decisions/" + value.decisionId()));
        if (value.generation() != null) response.eTag(ETags.of(value.generation()));
        return response.body(value);
    }

    private static ControlActionAudit.Target decisionTarget(Idempotent<PromotionResult> result) {
        return result.replayed() ? null : new ControlActionAudit.Target("DEPLOYMENT_DECISION",
                result.value().decisionId(), result.value().outcome());
    }

    private static ResponseEntity.BodyBuilder replayed(ResponseEntity.BodyBuilder builder, boolean replayed) {
        return replayed ? builder.header("Idempotent-Replayed", "true") : builder;
    }

    /** Overrides deliberately require the policy-authoring permission, separate from ordinary deployment operation. */
    private static boolean overrideAuthorized(OverrideRequest override) {
        boolean authorized = CurrentPrincipal.require().can(
                com.dvarahq.agentworks.domain.identity.WorkspacePermission.ENVIRONMENT_ADMIN);
        if (override != null && !authorized) throw new AccessDeniedException("promotion override is not authorized");
        return authorized;
    }
}
