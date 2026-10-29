package com.dvarahq.agentworks.controlplane.lifecycle.web;

import com.dvarahq.agentworks.controlplane.agent.web.WorkspaceScopeResolver;
import com.dvarahq.agentworks.controlplane.evidence.EvidenceAnchor;
import com.dvarahq.agentworks.controlplane.evidence.ReleaseEvidence.Bundle;
import com.dvarahq.agentworks.controlplane.evidence.ReleaseEvidence.Verification;
import com.dvarahq.agentworks.controlplane.evidence.ReleaseEvidenceAuthority;
import com.dvarahq.agentworks.identity.ControlActionAudit;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Objects;
import java.util.UUID;

/** Authenticated portable release-bundle API. Verification never invokes signing or deployment authority. */
@RestController
@RequestMapping(path = "/api/v1", produces = MediaType.APPLICATION_JSON_VALUE)
public final class ReleaseEvidenceController {
    public record PortableBundle(String bundle) {}

    private final ReleaseEvidenceAuthority evidence;
    private final WorkspaceScopeResolver workspaces;
    private final ControlActionAudit audit;

    public ReleaseEvidenceController(ReleaseEvidenceAuthority evidence, WorkspaceScopeResolver workspaces,
            ControlActionAudit audit) {
        this.evidence = Objects.requireNonNull(evidence, "evidence");
        this.workspaces = Objects.requireNonNull(workspaces, "workspaces");
        this.audit = Objects.requireNonNull(audit, "audit");
    }

    @PostMapping("/releases/{id}/bundle")
    public ResponseEntity<Bundle> sign(HttpServletRequest request, @PathVariable UUID id) {
        Bundle bundle = audit.record("RELEASE_EVIDENCE_SIGNED", () -> evidence.sign(workspaces.resolve(request), id),
                ignored -> new ControlActionAudit.Target("RELEASE", id, "SIGNED_EVIDENCE"));
        return ResponseEntity.ok().eTag(ETags.of(bundle.digest())).body(bundle);
    }

    @GetMapping("/releases/{id}/bundle")
    public ResponseEntity<Bundle> exportBundle(HttpServletRequest request, @PathVariable UUID id) {
        Bundle bundle = evidence.exportBundle(workspaces.resolve(request), id);
        return ResponseEntity.ok().eTag(ETags.of(bundle.digest())).body(bundle);
    }

    @PostMapping(path = "/release-bundles/verify", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Verification verify(@RequestBody PortableBundle request) {
        return evidence.verify(request.bundle());
    }

    @PostMapping(path = "/release-bundles/import", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Bundle> importBundle(HttpServletRequest request, @RequestBody PortableBundle body) {
        Bundle bundle = audit.record("RELEASE_EVIDENCE_IMPORTED",
                () -> evidence.importBundle(workspaces.resolve(request), body.bundle()), imported ->
                        new ControlActionAudit.Target("RELEASE", imported.payload().releaseId(), "SIGNED_EVIDENCE"));
        return ResponseEntity.ok().eTag(ETags.of(bundle.digest())).body(bundle);
    }

    @PostMapping("/releases/{id}/bundle/anchor")
    public EvidenceAnchor.Receipt anchor(HttpServletRequest request, @PathVariable UUID id) {
        return audit.record("RELEASE_EVIDENCE_ANCHORED", () -> evidence.anchor(workspaces.resolve(request), id),
                ignored -> new ControlActionAudit.Target("RELEASE", id, "EVIDENCE_ANCHOR"));
    }
}
