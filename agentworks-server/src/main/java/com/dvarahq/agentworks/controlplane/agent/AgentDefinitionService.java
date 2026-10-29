package com.dvarahq.agentworks.controlplane.agent;

import com.dvarahq.agentworks.controlplane.mutation.ControlPlaneMutationAuthority;
import com.dvarahq.agentworks.domain.lifecycle.AgentDefinitionId;
import com.dvarahq.agentworks.domain.lifecycle.AgentVersionId;
import com.dvarahq.agentworks.domain.tenant.WorkspaceId;
import com.dvarahq.agentworks.spec.AgentDefinition;
import com.dvarahq.agentworks.spec.AgentSpecCodec;
import com.dvarahq.agentworks.spec.AgentSpecValidationException;
import com.dvarahq.agentworks.spec.AgentVersion;
import com.dvarahq.agentworks.spec.CanonicalAgentSpec;
import com.dvarahq.agentworks.spec.DiagnosticCode;
import com.dvarahq.agentworks.spec.SpecDiagnostic;
import com.dvarahq.agentworks.spec.SpecFormat;
import com.dvarahq.agentworks.storage.JdbcAgentDefinitionRepository;
import com.dvarahq.agentworks.storage.JdbcAgentDefinitionRepository.DefinitionRow;
import com.dvarahq.agentworks.storage.JdbcAgentDefinitionRepository.VersionRow;
import com.dvarahq.agentworks.storage.TenantTransactionTemplate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Control Plane authority for AgentDefinition lineage and immutable AgentVersion publication. Every operation runs in
 * a transaction bound to the caller's workspace; document content never selects tenancy.
 */
public final class AgentDefinitionService implements ControlPlaneMutationAuthority {
    private static final Logger log = LoggerFactory.getLogger(AgentDefinitionService.class);
    private static final int MAX_DESCRIPTION_LENGTH = 1024;

    private final TenantTransactionTemplate tenants;
    private final JdbcAgentDefinitionRepository repository;
    private final AgentSpecCodec codec;
    private final Clock clock;

    public AgentDefinitionService(TenantTransactionTemplate tenants, JdbcAgentDefinitionRepository repository,
            AgentSpecCodec codec, Clock clock) {
        this.tenants = Objects.requireNonNull(tenants, "tenants");
        this.repository = Objects.requireNonNull(repository, "repository");
        this.codec = Objects.requireNonNull(codec, "codec");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public AgentDefinition createDefinition(WorkspaceId workspace, String name, String description) {
        List<SpecDiagnostic> diagnostics = new ArrayList<>();
        if (name == null) {
            diagnostics.add(SpecDiagnostic.at(DiagnosticCode.REQUIRED_FIELD_MISSING, "/name", "required field is missing"));
        } else if (!AgentSpecCodec.isValidAgentName(name)) {
            diagnostics.add(SpecDiagnostic.at(DiagnosticCode.INVALID_VALUE, "/name",
                    "must match pattern " + AgentSpecCodec.AGENT_NAME_PATTERN + " and be at most 63 characters"));
        }
        if (description != null && description.length() > MAX_DESCRIPTION_LENGTH) {
            diagnostics.add(SpecDiagnostic.at(DiagnosticCode.OUT_OF_RANGE, "/description", "length must be <= 1024"));
        }
        if (!diagnostics.isEmpty()) throw new AgentSpecValidationException(diagnostics);
        try {
            DefinitionRow row = tenants.execute(workspace, () -> {
                if (repository.findDefinitionByName(name).isPresent()) throw definitionNameConflict();
                return repository.insertDefinition(UUID.randomUUID(), name, description, clock.instant());
            });
            log.info("agent definition created workspace={} definition={}", workspace.value(), row.id());
            return definition(row);
        } catch (DuplicateKeyException exception) {
            throw definitionNameConflict();
        }
    }

    public List<AgentDefinition> listDefinitions(WorkspaceId workspace) {
        return tenants.execute(workspace, () -> repository.listDefinitions().stream().map(this::definition).toList());
    }

    /** One keyset page of definitions by name, after {@code (afterName, afterId)} when given (issue #35). */
    public List<AgentDefinition> listDefinitions(WorkspaceId workspace, String afterName, UUID afterId, int limit) {
        return tenants.execute(workspace, () -> repository.listDefinitions(afterName, afterId, limit).stream()
                .map(this::definition).toList());
    }

    public AgentDefinition getDefinition(WorkspaceId workspace, AgentDefinitionId definition) {
        return tenants.execute(workspace, () -> repository.findDefinition(definition.value())
                .map(this::definition).orElseThrow(AgentResourceNotFoundException::new));
    }

    /** Parses authored YAML or JSON, then publishes it. */
    public PublishedAgentVersion publishVersion(WorkspaceId workspace, AgentDefinitionId definition, byte[] document,
            SpecFormat format) {
        return publishVersion(workspace, definition, codec.read(document, format));
    }

    /**
     * Publishes an immutable version. Re-publishing byte-identical canonical content under the same label is an
     * idempotent no-op; different content under an existing label is a conflict, so edits require a new version.
     */
    public PublishedAgentVersion publishVersion(WorkspaceId workspace, AgentDefinitionId definition,
            CanonicalAgentSpec candidate) {
        Objects.requireNonNull(candidate, "spec");
        // Recompute canonical bytes and digest from the typed value; a caller-built record is never trusted.
        CanonicalAgentSpec spec = codec.canonicalize(candidate.spec());
        String label = spec.spec().metadata().version();
        try {
            PublishedAgentVersion published = tenants.execute(workspace, () -> {
                DefinitionRow owner = repository.lockDefinition(definition.value())
                        .orElseThrow(AgentResourceNotFoundException::new);
                if (!owner.name().equals(spec.spec().metadata().name())) {
                    throw new AgentSpecValidationException(List.of(SpecDiagnostic.at(DiagnosticCode.INVALID_VALUE,
                            "/metadata/name", "must equal the agent definition name")));
                }
                var existing = repository.findVersion(definition.value(), label);
                if (existing.isPresent()) {
                    if (existing.get().specDigest().equals(spec.digest())) {
                        return new PublishedAgentVersion(version(existing.get()), false);
                    }
                    throw versionConflict();
                }
                VersionRow row = repository.insertVersion(UUID.randomUUID(), definition.value(),
                        repository.nextSequence(definition.value()), label, spec.spec().apiVersion(),
                        spec.canonicalJson(), spec.digest(), clock.instant());
                return new PublishedAgentVersion(version(row), true);
            });
            if (published.created()) {
                log.info("agent version published workspace={} definition={} version={} sequence={} digest={}",
                        workspace.value(), definition.value(), published.version().id().value(),
                        published.version().sequence(), published.version().digest());
            }
            return published;
        } catch (DuplicateKeyException exception) {
            throw versionConflict();
        }
    }

    public List<AgentVersion> listVersions(WorkspaceId workspace, AgentDefinitionId definition) {
        return tenants.execute(workspace, () -> {
            repository.findDefinition(definition.value()).orElseThrow(AgentResourceNotFoundException::new);
            return repository.listVersions(definition.value()).stream().map(this::version).toList();
        });
    }

    /** One keyset page of a definition's versions in publication order (issue #35). */
    public List<AgentVersion> listVersions(WorkspaceId workspace, AgentDefinitionId definition, long afterSequence,
            int limit) {
        return tenants.execute(workspace, () -> {
            repository.findDefinition(definition.value()).orElseThrow(AgentResourceNotFoundException::new);
            return repository.listVersions(definition.value(), afterSequence, limit).stream().map(this::version).toList();
        });
    }

    public AgentVersion getVersion(WorkspaceId workspace, AgentDefinitionId definition, String version) {
        return tenants.execute(workspace, () -> repository.findVersion(definition.value(), version)
                .map(this::version).orElseThrow(AgentResourceNotFoundException::new));
    }

    private AgentDefinition definition(DefinitionRow row) {
        return new AgentDefinition(new AgentDefinitionId(row.id()), new WorkspaceId(row.workspaceId()), row.name(),
                row.description(), row.createdAt());
    }

    /** Re-validates stored canonical bytes and fails closed if they no longer match the pinned digest. */
    private AgentVersion version(VersionRow row) {
        CanonicalAgentSpec spec = codec.read(row.specJson().getBytes(StandardCharsets.UTF_8), SpecFormat.JSON);
        if (!spec.digest().equals(row.specDigest()) || !spec.canonicalJson().equals(row.specJson())
                || !spec.spec().metadata().version().equals(row.versionLabel())
                || !spec.spec().apiVersion().equals(row.apiVersion())) {
            throw new IllegalStateException("stored agent version " + row.id() + " failed integrity verification");
        }
        return new AgentVersion(new AgentVersionId(row.id()), new AgentDefinitionId(row.definitionId()),
                new WorkspaceId(row.workspaceId()), row.sequence(), spec, row.createdAt());
    }

    private static AgentLifecycleConflictException definitionNameConflict() {
        return new AgentLifecycleConflictException("AGENT_DEFINITION_EXISTS",
                "an agent definition with this name already exists in the workspace");
    }

    private static AgentLifecycleConflictException versionConflict() {
        return new AgentLifecycleConflictException("AGENT_VERSION_EXISTS",
                "this version is already published with different content; publish edits as a new version");
    }
}
