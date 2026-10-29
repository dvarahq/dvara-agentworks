package com.dvarahq.agentworks.controlplane.agent.web;

import com.dvarahq.agentworks.controlplane.agent.AgentDefinitionService;
import com.dvarahq.agentworks.controlplane.agent.PublishedAgentVersion;
import com.dvarahq.agentworks.controlplane.agent.web.AgentApiResources.AgentDefinitionResource;
import com.dvarahq.agentworks.controlplane.agent.web.AgentApiResources.AgentVersionResource;
import com.dvarahq.agentworks.controlplane.agent.web.AgentApiResources.CreateAgentDefinitionRequest;
import com.dvarahq.agentworks.api.web.Page;
import com.dvarahq.agentworks.api.web.PageCursor;
import com.dvarahq.agentworks.domain.lifecycle.AgentDefinitionId;
import com.dvarahq.agentworks.identity.ControlActionAudit;
import com.dvarahq.agentworks.spec.AgentSpecCodec;
import com.dvarahq.agentworks.spec.AgentSpecValidationException;
import com.dvarahq.agentworks.spec.DiagnosticCode;
import com.dvarahq.agentworks.spec.SpecDiagnostic;
import com.dvarahq.agentworks.spec.SpecFormat;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
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
import org.springframework.web.util.UriComponentsBuilder;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Versioned REST surface for AgentDefinition lineage and immutable AgentVersions. */
@RestController
@RequestMapping("/api/v1/agents")
public final class AgentDefinitionController {
    static final String APPLICATION_YAML = "application/yaml";
    private static final List<MediaType> YAML_TYPES = List.of(MediaType.parseMediaType(APPLICATION_YAML),
            MediaType.parseMediaType("application/x-yaml"), MediaType.parseMediaType("text/yaml"));

    private final AgentDefinitionService agents;
    private final WorkspaceScopeResolver workspaces;
    private final ControlActionAudit audit;

    public AgentDefinitionController(AgentDefinitionService agents, WorkspaceScopeResolver workspaces) {
        this(agents, workspaces, ControlActionAudit.NONE);
    }

    /** @param audit records the authenticated actor of definition creation and version publication */
    public AgentDefinitionController(AgentDefinitionService agents, WorkspaceScopeResolver workspaces,
            ControlActionAudit audit) {
        this.agents = Objects.requireNonNull(agents, "agents");
        this.workspaces = Objects.requireNonNull(workspaces, "workspaces");
        this.audit = Objects.requireNonNull(audit, "audit");
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<AgentDefinitionResource> create(HttpServletRequest request,
            @RequestBody CreateAgentDefinitionRequest body) {
        var workspace = workspaces.resolve(request);
        var definition = audit.record("AGENT_DEFINITION_CREATED", () -> agents.createDefinition(workspace, body.name(),
                body.description()), created -> new ControlActionAudit.Target("AGENT_DEFINITION", created.id().value(),
                null));
        return ResponseEntity.created(UriComponentsBuilder.fromPath("/api/v1/agents/{id}")
                .build(definition.id().value())).body(AgentDefinitionResource.of(definition));
    }

    /** Definitions ordered by name, then id, in keyset pages (issue #35). */
    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public Page<AgentDefinitionResource> list(HttpServletRequest request,
            @RequestParam(name = "cursor", required = false) String cursor,
            @RequestParam(name = "limit", required = false) Integer limit) {
        var workspace = workspaces.resolve(request);
        int size = PageCursor.limit(limit);
        List<String> position = PageCursor.decode(cursor, 2);
        return Page.of(agents.listDefinitions(workspace, position == null ? null : position.get(0),
                        position == null ? null : PageCursor.uuid(position.get(1)), size + 1), size,
                definition -> PageCursor.encode(definition.name(), definition.id().value().toString()))
                .map(AgentDefinitionResource::of);
    }

    @GetMapping(path = "/{definitionId}", produces = MediaType.APPLICATION_JSON_VALUE)
    public AgentDefinitionResource get(HttpServletRequest request, @PathVariable UUID definitionId) {
        return AgentDefinitionResource.of(agents.getDefinition(workspaces.resolve(request),
                new AgentDefinitionId(definitionId)));
    }

    /**
     * Publishes an authored AgentSpec as YAML or JSON. Returns 201 for a new version and 200 when identical content
     * is already published under the same version label.
     */
    @PostMapping(path = "/{definitionId}/versions", produces = MediaType.APPLICATION_JSON_VALUE, consumes = {
            MediaType.APPLICATION_JSON_VALUE, APPLICATION_YAML, "application/x-yaml", "text/yaml"})
    public ResponseEntity<AgentVersionResource> publish(HttpServletRequest request, @PathVariable UUID definitionId,
            @RequestHeader(HttpHeaders.CONTENT_TYPE) MediaType contentType, InputStream body) throws IOException {
        var workspace = workspaces.resolve(request);
        long declaredLength = request.getContentLengthLong();
        if (declaredLength > AgentSpecCodec.MAX_DOCUMENT_BYTES) throw tooLarge();
        byte[] document = body.readNBytes(AgentSpecCodec.MAX_DOCUMENT_BYTES + 1);
        if (document.length > AgentSpecCodec.MAX_DOCUMENT_BYTES) throw tooLarge();
        SpecFormat format = YAML_TYPES.stream().anyMatch(type -> type.includes(contentType)) ? SpecFormat.YAML
                : SpecFormat.JSON;
        PublishedAgentVersion published = audit.record("AGENT_VERSION_PUBLISHED", () -> agents.publishVersion(workspace,
                new AgentDefinitionId(definitionId), document, format), result -> result.created()
                ? new ControlActionAudit.Target("AGENT_VERSION", result.version().id().value(), null) : null);
        AgentVersionResource resource = AgentVersionResource.of(published.version());
        if (!published.created()) return ResponseEntity.ok(resource);
        return ResponseEntity.status(HttpStatus.CREATED).location(UriComponentsBuilder
                        .fromPath("/api/v1/agents/{id}/versions/{version}").build(definitionId, resource.version()))
                .body(resource);
    }

    /** Versions in publication order, in keyset pages (issue #35). */
    @GetMapping(path = "/{definitionId}/versions", produces = MediaType.APPLICATION_JSON_VALUE)
    public Page<AgentVersionResource> versions(HttpServletRequest request, @PathVariable UUID definitionId,
            @RequestParam(name = "cursor", required = false) String cursor,
            @RequestParam(name = "limit", required = false) Integer limit) {
        var workspace = workspaces.resolve(request);
        int size = PageCursor.limit(limit);
        List<String> position = PageCursor.decode(cursor, 1);
        return Page.of(agents.listVersions(workspace, new AgentDefinitionId(definitionId),
                        position == null ? 0 : PageCursor.sequence(position.get(0)), size + 1), size,
                version -> PageCursor.encode(Long.toString(version.sequence()))).map(AgentVersionResource::of);
    }

    @GetMapping(path = "/{definitionId}/versions/{version}", produces = MediaType.APPLICATION_JSON_VALUE)
    public AgentVersionResource version(HttpServletRequest request, @PathVariable UUID definitionId,
            @PathVariable String version) {
        return AgentVersionResource.of(agents.getVersion(workspaces.resolve(request),
                new AgentDefinitionId(definitionId), version));
    }

    private static AgentSpecValidationException tooLarge() {
        return new AgentSpecValidationException(List.of(SpecDiagnostic.at(DiagnosticCode.DOCUMENT_TOO_LARGE, "",
                "document exceeds " + AgentSpecCodec.MAX_DOCUMENT_BYTES + " bytes")));
    }
}
