package com.dvarahq.agentworks.controlplane.capability.web;

import com.dvarahq.agentworks.api.web.Page;
import com.dvarahq.agentworks.capability.AgentTool;
import com.dvarahq.agentworks.capability.CapabilityPack;
import com.dvarahq.agentworks.capability.ToolId;
import com.dvarahq.agentworks.capability.ToolRegistry;
import com.dvarahq.agentworks.capability.ToolType;
import com.dvarahq.agentworks.controlplane.agent.web.WorkspaceScopeResolver;
import com.dvarahq.agentworks.controlplane.capability.CapabilityCatalogService;
import com.dvarahq.agentworks.controlplane.capability.web.CapabilityApiResources.CapabilityPackResource;
import com.dvarahq.agentworks.controlplane.capability.web.CapabilityApiResources.CreatePackRequest;
import com.dvarahq.agentworks.controlplane.capability.web.CapabilityApiResources.CreateToolRequest;
import com.dvarahq.agentworks.controlplane.capability.web.CapabilityApiResources.LifecycleRequest;
import com.dvarahq.agentworks.controlplane.capability.web.CapabilityApiResources.LifecycleResource;
import com.dvarahq.agentworks.controlplane.capability.web.CapabilityApiResources.ToolDetailResource;
import com.dvarahq.agentworks.controlplane.capability.web.CapabilityApiResources.ToolSummaryResource;
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

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Authenticated, tenant-scoped management and discovery for versioned tools and capability packs. */
@RestController
@RequestMapping("/api/v1")
public final class CapabilityCatalogController {
    private final CapabilityCatalogService catalog;
    private final WorkspaceScopeResolver workspaces;
    private final ControlActionAudit audit;

    public CapabilityCatalogController(CapabilityCatalogService catalog, WorkspaceScopeResolver workspaces,
            ControlActionAudit audit) {
        this.catalog = Objects.requireNonNull(catalog, "catalog");
        this.workspaces = Objects.requireNonNull(workspaces, "workspaces");
        this.audit = Objects.requireNonNull(audit, "audit");
    }

    @PostMapping(path = "/tools", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ToolDetailResource> createTool(HttpServletRequest request,
            @RequestBody CreateToolRequest body) {
        WorkspaceId workspace = workspaces.resolve(request);
        AgentTool tool = body.value();
        AgentTool created = audit.record("TOOL_VERSION_CREATED", () -> catalog.createTool(workspace, tool),
                result -> target("TOOL_VERSION", workspace, result.id().value(), null));
        return ResponseEntity.created(UriComponentsBuilder.fromPath(
                        "/api/v1/tools/{namespace}/{name}/versions/{version}")
                .build(created.id().namespace(), created.id().name(), created.id().version()))
                .body(ToolDetailResource.of(workspace, created));
    }

    @GetMapping(path = "/tools", produces = MediaType.APPLICATION_JSON_VALUE)
    public Page<ToolSummaryResource> search(HttpServletRequest request,
            @RequestParam(name = "q", required = false) String query,
            @RequestParam(name = "type", required = false) List<ToolType> types,
            @RequestParam(name = "tag", required = false) List<String> tags,
            @RequestParam(name = "cursor", required = false) String cursor,
            @RequestParam(name = "limit", defaultValue = "50") int limit) {
        WorkspaceId workspace = workspaces.resolve(request);
        ToolRegistry.Page page = catalog.search(workspace, new ToolRegistry.Search(query,
                types == null ? Set.of() : Set.copyOf(types), tags == null ? Set.of() : Set.copyOf(tags), cursor, limit));
        return new Page<>(page.items().stream().map(tool -> ToolSummaryResource.of(workspace, tool)).toList(),
                page.nextCursor());
    }

    @GetMapping(path = "/tools/{namespace}/{name}/versions/{version}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ToolDetailResource detail(HttpServletRequest request, @PathVariable String namespace,
            @PathVariable String name, @PathVariable String version) {
        WorkspaceId workspace = workspaces.resolve(request);
        return ToolDetailResource.of(workspace, catalog.detail(workspace, new ToolId(namespace, name, version)));
    }

    @PostMapping(path = "/tools/{namespace}/{name}/versions/{version}/lifecycle",
            consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public LifecycleResource transitionTool(HttpServletRequest request, @PathVariable String namespace,
            @PathVariable String name, @PathVariable String version, @RequestBody LifecycleRequest body) {
        WorkspaceId workspace = workspaces.resolve(request);
        ToolId id = new ToolId(namespace, name, version);
        AgentTool.Availability state = body.toolState();
        audit.record("TOOL_LIFECYCLE_CHANGED", () -> {
            catalog.transitionTool(workspace, id, state);
            return state;
        }, result -> target("TOOL_VERSION", workspace, id.value(), result.name()));
        return new LifecycleResource(id.value(), CapabilityApiResources.LifecycleState.valueOf(state.name()));
    }

    @PostMapping(path = "/capability-packs", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<CapabilityPackResource> createPack(HttpServletRequest request,
            @RequestBody CreatePackRequest body) {
        WorkspaceId workspace = workspaces.resolve(request);
        CapabilityPack pack = body.value();
        CapabilityPack created = audit.record("CAPABILITY_PACK_CREATED", () -> catalog.createPack(workspace, pack),
                result -> target("CAPABILITY_PACK", workspace, result.identity(), null));
        return ResponseEntity.created(UriComponentsBuilder.fromPath("/api/v1/capability-packs/{id}/versions/{version}")
                .build(created.id(), created.version())).body(CapabilityPackResource.of(workspace, created));
    }

    @GetMapping(path = "/capability-packs/{id}/versions/{version}", produces = MediaType.APPLICATION_JSON_VALUE)
    public CapabilityPackResource pack(HttpServletRequest request, @PathVariable String id,
            @PathVariable String version) {
        WorkspaceId workspace = workspaces.resolve(request);
        return CapabilityPackResource.of(workspace, catalog.pack(workspace, id, version));
    }

    @PostMapping(path = "/capability-packs/{id}/versions/{version}/lifecycle",
            consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public LifecycleResource transitionPack(HttpServletRequest request, @PathVariable String id,
            @PathVariable String version, @RequestBody LifecycleRequest body) {
        WorkspaceId workspace = workspaces.resolve(request);
        CapabilityPack.State state = body.packState();
        String identity = id + ":" + version;
        audit.record("CAPABILITY_PACK_LIFECYCLE_CHANGED", () -> {
            catalog.transitionPack(workspace, id, version, state);
            return state;
        }, result -> target("CAPABILITY_PACK", workspace, identity, result.name()));
        return new LifecycleResource(identity, CapabilityApiResources.LifecycleState.valueOf(state.name()));
    }

    private static ControlActionAudit.Target target(String type, WorkspaceId workspace, String identity, String detail) {
        UUID id = UUID.nameUUIDFromBytes((workspace.value() + ":" + identity).getBytes(StandardCharsets.UTF_8));
        return new ControlActionAudit.Target(type, id, detail);
    }
}
