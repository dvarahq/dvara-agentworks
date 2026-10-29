package com.dvarahq.agentworks.controlplane.lifecycle.web;

import com.dvarahq.agentworks.api.web.ApiRequestException;
import com.dvarahq.agentworks.api.web.Page;
import com.dvarahq.agentworks.api.web.PageCursor;
import com.dvarahq.agentworks.controlplane.agent.web.WorkspaceScopeResolver;
import com.dvarahq.agentworks.controlplane.deployment.ReleaseLifecycleService;
import com.dvarahq.agentworks.controlplane.lifecycle.LifecycleModel.Idempotent;
import com.dvarahq.agentworks.controlplane.lifecycle.LifecycleModel.ReleaseLifecycleCommand;
import com.dvarahq.agentworks.controlplane.lifecycle.LifecycleModel.ReleaseLifecycleResult;
import com.dvarahq.agentworks.controlplane.lifecycle.LifecycleModel.ReleaseLifecycleView;
import com.dvarahq.agentworks.controlplane.lifecycle.LifecycleModel.ReleaseRevocationView;
import com.dvarahq.agentworks.identity.ControlActionAudit;
import com.dvarahq.agentworks.identity.web.CurrentPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

/** Release verification state and trust/key/dependency revocation REST authority (issue #79). */
@RestController
@RequestMapping(path = "/api/v1", produces = MediaType.APPLICATION_JSON_VALUE)
public final class ReleaseLifecycleController {
    public record Command(String reason, String subjectKind, String subjectIdentity, String evidenceDigest) {}

    private final ReleaseLifecycleService releases;
    private final WorkspaceScopeResolver workspaces;
    private final ControlActionAudit audit;

    public ReleaseLifecycleController(ReleaseLifecycleService releases, WorkspaceScopeResolver workspaces,
            ControlActionAudit audit) {
        this.releases = Objects.requireNonNull(releases, "releases");
        this.workspaces = Objects.requireNonNull(workspaces, "workspaces");
        this.audit = Objects.requireNonNull(audit, "audit");
    }

    @GetMapping("/releases/{id}/lifecycle")
    public ResponseEntity<ReleaseLifecycleView> get(HttpServletRequest request, @PathVariable UUID id) {
        ReleaseLifecycleView result = releases.get(workspaces.resolve(request), id);
        return ResponseEntity.ok().eTag(ETags.of(result.version())).body(result);
    }

    @PostMapping(path = "/releases/{id}/{action:suspend|resume|revoke|retire}",
            consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ReleaseLifecycleResult> transition(HttpServletRequest request, @PathVariable UUID id,
            @PathVariable String action, @RequestHeader("If-Match") String ifMatch,
            @RequestHeader(name = "Idempotency-Key", required = false) String key, @RequestBody Command body) {
        var workspace = workspaces.resolve(request);
        String event = "RELEASE_" + switch (action) {
            case "suspend" -> "SUSPENDED";
            case "resume" -> "RESUMED";
            case "revoke" -> "REVOKED";
            default -> "RETIRED";
        };
        Idempotent<ReleaseLifecycleResult> result = audit.record(event,
                () -> releases.transition(workspace, action, new ReleaseLifecycleCommand(id, ETags.version(ifMatch),
                        key, CurrentPrincipal.actor(), body.reason(), code(body.subjectKind()), body.subjectIdentity(),
                        body.evidenceDigest())),
                completed -> completed.replayed() ? null : new ControlActionAudit.Target("RELEASE", id,
                        completed.value().lifecycle().state()));
        var response = ResponseEntity.ok().eTag(ETags.of(result.value().lifecycle().version()));
        if (result.replayed()) response.header("Idempotent-Replayed", "true");
        return response.body(result.value());
    }

    @GetMapping("/release-revocations")
    public Page<ReleaseRevocationView> revocations(HttpServletRequest request,
            @RequestParam(name = "releaseId", required = false) UUID releaseId,
            @RequestParam(name = "subjectKind", required = false) String subjectKind,
            @RequestParam(name = "cursor", required = false) String cursor,
            @RequestParam(name = "limit", required = false) Integer limit) {
        int size = PageCursor.limit(limit);
        List<String> position = PageCursor.decode(cursor, 2);
        return Page.of(releases.revocations(workspaces.resolve(request), releaseId, code(subjectKind),
                        position == null ? null : PageCursor.instant(position.get(0)),
                        position == null ? null : PageCursor.uuid(position.get(1)), size + 1), size,
                item -> PageCursor.encode(PageCursor.instant(item.occurredAt()), item.id().toString()));
    }

    private static String code(String value) {
        if (value == null || value.isBlank()) return null;
        String result = value.strip().toUpperCase(Locale.ROOT);
        if (!result.matches("[A-Z][A-Z0-9_]{0,63}")) {
            throw new ApiRequestException(HttpStatus.BAD_REQUEST, "INVALID_REVOCATION_SUBJECT",
                    "Revocation subject kind must be a stable code.");
        }
        return result;
    }
}
