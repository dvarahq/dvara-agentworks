package com.dvarahq.agentworks.controlplane.lifecycle.web;

import com.dvarahq.agentworks.controlplane.agent.web.WorkspaceScopeResolver;
import com.dvarahq.agentworks.controlplane.deployment.EnvironmentService;
import com.dvarahq.agentworks.controlplane.lifecycle.LifecycleModel.AliasView;
import com.dvarahq.agentworks.controlplane.lifecycle.LifecycleModel.EnvironmentView;
import com.dvarahq.agentworks.controlplane.lifecycle.LifecycleModel.PromotionPolicy;
import com.dvarahq.agentworks.controlplane.lifecycle.LifecycleModel.ReleaseView;
import com.dvarahq.agentworks.controlplane.lifecycle.ReleaseService;
import com.dvarahq.agentworks.controlplane.evidence.ReleaseEvidence.EvidenceReference;
import com.dvarahq.agentworks.identity.ControlActionAudit;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import com.dvarahq.agentworks.api.web.Page;
import com.dvarahq.agentworks.api.web.PageCursor;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * REST surface for releases, aliases, and environments (issues #31 and #35). Creations answer {@code 201} with
 * {@code Location} and an {@code ETag}: the manifest digest of a release (re-creating an existing release name for
 * the same version returns the stored release) or the policy version of an environment, which {@code If-Match}
 * carries when the policy changes. Collections are keyset-paged {@code {items, nextCursor}} envelopes.
 */
@RestController
@RequestMapping(path = "/api/v1", produces = MediaType.APPLICATION_JSON_VALUE)
public final class ReleaseEnvironmentController {
    /** Evidence values are content-addressed references; raw reports and fixtures are intentionally not accepted. */
    public record CreateRelease(String name, List<EvidenceReference> evidence) {
        public CreateRelease(String name) { this(name, List.of()); }
    }
    public record SetAlias(UUID releaseId) {}
    public record CreateEnvironment(String name, String description, PromotionPolicy promotionPolicy) {}

    private final ReleaseService releases;
    private final EnvironmentService environments;
    private final WorkspaceScopeResolver workspaces;
    private final ControlActionAudit audit;

    public ReleaseEnvironmentController(ReleaseService releases, EnvironmentService environments,
            WorkspaceScopeResolver workspaces) {
        this(releases, environments, workspaces, ControlActionAudit.NONE);
    }

    /** @param audit records the authenticated actor of release, alias, environment, and policy changes */
    public ReleaseEnvironmentController(ReleaseService releases, EnvironmentService environments,
            WorkspaceScopeResolver workspaces, ControlActionAudit audit) {
        this.releases = Objects.requireNonNull(releases, "releases");
        this.environments = Objects.requireNonNull(environments, "environments");
        this.workspaces = Objects.requireNonNull(workspaces, "workspaces");
        this.audit = Objects.requireNonNull(audit, "audit");
    }

    @PostMapping(path = "/agent-versions/{versionId}/releases", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ReleaseView> createRelease(HttpServletRequest request, @PathVariable UUID versionId,
            @RequestBody CreateRelease body) {
        var workspace = workspaces.resolve(request);
        ReleaseView release = audit.record("RELEASE_CREATED", () -> releases.createRelease(workspace, versionId,
                body.name(), body.evidence()), created -> new ControlActionAudit.Target("RELEASE", created.id(), null));
        return ResponseEntity.created(UriComponentsBuilder.fromPath("/api/v1/releases/{id}").build(release.id()))
                .eTag(ETags.of(release.manifestDigest())).body(release);
    }

    /** Releases ordered by creation time, then id, in keyset pages (issue #35). */
    @GetMapping("/releases")
    public Page<ReleaseView> releases(HttpServletRequest request,
            @RequestParam(name = "cursor", required = false) String cursor,
            @RequestParam(name = "limit", required = false) Integer limit) {
        var workspace = workspaces.resolve(request);
        int size = PageCursor.limit(limit);
        List<String> position = PageCursor.decode(cursor, 2);
        return Page.of(releases.listReleases(workspace, position == null ? null : PageCursor.instant(position.get(0)),
                        position == null ? null : PageCursor.uuid(position.get(1)), size + 1), size,
                release -> PageCursor.encode(PageCursor.instant(release.createdAt()), release.id().toString()));
    }

    @GetMapping("/releases/{id}")
    public ResponseEntity<ReleaseView> release(HttpServletRequest request, @PathVariable UUID id) {
        ReleaseView release = releases.getRelease(workspaces.resolve(request), id);
        return ResponseEntity.ok().eTag(ETags.of(release.manifestDigest())).body(release);
    }

    /** The stored canonical manifest bytes; SHA-256 of the body equals the release manifest digest. */
    @GetMapping("/releases/{id}/manifest")
    public ResponseEntity<String> manifest(HttpServletRequest request, @PathVariable UUID id) {
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON)
                .body(releases.manifest(workspaces.resolve(request), id));
    }

    /** Creates an alias without {@code If-Match}; moving one requires the current version in {@code If-Match}. */
    @PutMapping(path = "/release-aliases/{name}", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<AliasView> setAlias(HttpServletRequest request, @PathVariable String name,
            @RequestHeader(name = "If-Match", required = false) String ifMatch, @RequestBody SetAlias body) {
        var workspace = workspaces.resolve(request);
        long expected = ifMatch == null ? 0 : ETags.version(ifMatch);
        AliasView alias = audit.record("RELEASE_ALIAS_SET", () -> releases.setAlias(workspace, name, body.releaseId(),
                expected), set -> new ControlActionAudit.Target("RELEASE", set.releaseId(), "ALIAS_VERSION_" + set.version()));
        return ResponseEntity.ok().eTag(ETags.of(alias.version())).body(alias);
    }

    @GetMapping("/release-aliases/{name}")
    public ResponseEntity<AliasView> alias(HttpServletRequest request, @PathVariable String name) {
        AliasView alias = releases.getAlias(workspaces.resolve(request), name);
        return ResponseEntity.ok().eTag(ETags.of(alias.version())).body(alias);
    }

    @PostMapping(path = "/environments", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<EnvironmentView> createEnvironment(HttpServletRequest request,
            @RequestBody CreateEnvironment body) {
        var workspace = workspaces.resolve(request);
        EnvironmentView environment = audit.record("ENVIRONMENT_CREATED", () -> environments.create(workspace,
                body.name(), body.description(), body.promotionPolicy()),
                created -> new ControlActionAudit.Target("ENVIRONMENT", created.id(), null));
        return ResponseEntity.created(UriComponentsBuilder.fromPath("/api/v1/environments/{id}").build(environment.id()))
                .eTag(ETags.of(environment.policyVersion())).body(environment);
    }

    /** Environments ordered by name, then id, in keyset pages (issue #35). */
    @GetMapping("/environments")
    public Page<EnvironmentView> environments(HttpServletRequest request,
            @RequestParam(name = "cursor", required = false) String cursor,
            @RequestParam(name = "limit", required = false) Integer limit) {
        var workspace = workspaces.resolve(request);
        int size = PageCursor.limit(limit);
        List<String> position = PageCursor.decode(cursor, 2);
        return Page.of(environments.list(workspace, position == null ? null : position.get(0),
                        position == null ? null : PageCursor.uuid(position.get(1)), size + 1), size,
                environment -> PageCursor.encode(environment.name(), environment.id().toString()));
    }

    @GetMapping("/environments/{id}")
    public ResponseEntity<EnvironmentView> environment(HttpServletRequest request, @PathVariable UUID id) {
        EnvironmentView environment = environments.get(workspaces.resolve(request), id);
        return ResponseEntity.ok().eTag(ETags.of(environment.policyVersion())).body(environment);
    }

    @GetMapping("/environments/{id}/promotion-policy")
    public ResponseEntity<PromotionPolicy> policy(HttpServletRequest request, @PathVariable UUID id) {
        EnvironmentView environment = environments.get(workspaces.resolve(request), id);
        return ResponseEntity.ok().eTag(ETags.of(environment.policyVersion())).body(environment.promotionPolicy());
    }

    @PutMapping(path = "/environments/{id}/promotion-policy", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<PromotionPolicy> updatePolicy(HttpServletRequest request, @PathVariable UUID id,
            @RequestHeader("If-Match") String ifMatch, @RequestBody PromotionPolicy policy) {
        var workspace = workspaces.resolve(request);
        long expected = ETags.version(ifMatch);
        EnvironmentView environment = audit.record("PROMOTION_POLICY_CHANGED", () -> environments.updatePolicy(workspace,
                id, expected, policy), changed -> new ControlActionAudit.Target("ENVIRONMENT", changed.id(),
                "POLICY_VERSION_" + changed.policyVersion()));
        return ResponseEntity.ok().eTag(ETags.of(environment.policyVersion())).body(environment.promotionPolicy());
    }
}
