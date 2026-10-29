package com.dvarahq.agentworks.controlplane.security.web;

import com.dvarahq.agentworks.controlplane.agent.web.WorkspaceScopeResolver;
import com.dvarahq.agentworks.controlplane.security.RememberedPermissionService;
import com.dvarahq.agentworks.controlplane.security.web.RememberedPermissionApiResources.CreateRequest;
import com.dvarahq.agentworks.controlplane.security.web.RememberedPermissionApiResources.Resource;
import com.dvarahq.agentworks.api.web.Page;
import com.dvarahq.agentworks.domain.tenant.WorkspaceId;
import com.dvarahq.agentworks.identity.ControlActionAudit;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.Objects;
import java.util.UUID;

/** Authenticated control-plane API for remembered tool permissions. */
@RestController
@RequestMapping("/api/v1/remembered-permissions")
public final class RememberedPermissionController {
    private final RememberedPermissionService permissions;
    private final WorkspaceScopeResolver workspaces;
    private final ControlActionAudit audit;

    public RememberedPermissionController(RememberedPermissionService permissions, WorkspaceScopeResolver workspaces,
            ControlActionAudit audit) {
        this.permissions = Objects.requireNonNull(permissions, "permissions");
        this.workspaces = Objects.requireNonNull(workspaces, "workspaces");
        this.audit = Objects.requireNonNull(audit, "audit");
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Resource> create(HttpServletRequest request, @RequestBody CreateRequest body) {
        WorkspaceId workspace = workspaces.resolve(request);
        var created = audit.record("REMEMBERED_PERMISSION_CREATED",
                () -> permissions.create(workspace, body.value()),
                result -> result.changed() ? new ControlActionAudit.Target("REMEMBERED_PERMISSION",
                        result.rule().id(), result.rule().effect().name()) : null);
        Resource resource = Resource.of(created.rule());
        if (!created.changed()) return ResponseEntity.ok(resource);
        return ResponseEntity.created(UriComponentsBuilder.fromPath("/api/v1/remembered-permissions/{id}")
                .build(created.rule().id())).body(resource);
    }

    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public Page<Resource> list(HttpServletRequest request,
            @RequestParam(name = "cursor", required = false) String cursor,
            @RequestParam(name = "limit", defaultValue = "50") int limit) {
        WorkspaceId workspace = workspaces.resolve(request);
        var page = permissions.list(workspace, cursor, limit);
        return new Page<>(page.items().stream().map(Resource::of).toList(), page.nextCursor());
    }

    @GetMapping(path = "/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
    public Resource get(HttpServletRequest request, @PathVariable UUID id) {
        return Resource.of(permissions.get(workspaces.resolve(request), id));
    }

    @PostMapping(path = "/{id}/revoke", produces = MediaType.APPLICATION_JSON_VALUE)
    public Resource revoke(HttpServletRequest request, @PathVariable UUID id) {
        WorkspaceId workspace = workspaces.resolve(request);
        var result = audit.record("REMEMBERED_PERMISSION_REVOKED", () -> permissions.revoke(workspace, id),
                revoked -> revoked.changed()
                        ? new ControlActionAudit.Target("REMEMBERED_PERMISSION", revoked.rule().id(), "REVOKED") : null);
        return Resource.of(result.rule());
    }
}
