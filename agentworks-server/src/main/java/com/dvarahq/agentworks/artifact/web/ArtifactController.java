package com.dvarahq.agentworks.artifact.web;

import com.dvarahq.agentworks.api.web.ApiRequestException;
import com.dvarahq.agentworks.api.web.Page;
import com.dvarahq.agentworks.api.web.PageCursor;
import com.dvarahq.agentworks.artifact.ArtifactLifecycle;
import com.dvarahq.agentworks.artifact.ArtifactStore;
import com.dvarahq.agentworks.artifact.web.ArtifactApiResources.ArtifactResource;
import com.dvarahq.agentworks.artifact.web.ArtifactApiResources.PinRequest;
import com.dvarahq.agentworks.artifact.web.ArtifactApiResources.PinResource;
import com.dvarahq.agentworks.artifact.web.ArtifactApiResources.ValidationRequest;
import com.dvarahq.agentworks.controlplane.agent.web.WorkspaceScopeResolver;
import com.dvarahq.agentworks.domain.artifact.ArtifactClassification;
import com.dvarahq.agentworks.domain.artifact.ArtifactContent;
import com.dvarahq.agentworks.domain.artifact.ArtifactDigest;
import com.dvarahq.agentworks.domain.artifact.ArtifactOwner;
import com.dvarahq.agentworks.domain.artifact.ArtifactProvenance;
import com.dvarahq.agentworks.domain.artifact.ArtifactRead;
import com.dvarahq.agentworks.domain.artifact.ArtifactRef;
import com.dvarahq.agentworks.domain.artifact.ArtifactSearchRequest;
import com.dvarahq.agentworks.domain.artifact.ArtifactWrite;
import com.dvarahq.agentworks.identity.ControlActionAudit;
import com.dvarahq.agentworks.identity.web.CurrentPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;
import org.springframework.web.util.UriComponentsBuilder;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Authenticated tenant-scoped artifact upload, discovery, transfer, lineage, and lifecycle API. */
@RestController
@RequestMapping("/api/v1/artifacts")
public final class ArtifactController {
    private final ObjectProvider<ArtifactStore> stores;
    private final ObjectProvider<ArtifactLifecycle> lifecycles;
    private final WorkspaceScopeResolver workspaces;
    private final ObjectProvider<ControlActionAudit> audits;

    public ArtifactController(ObjectProvider<ArtifactStore> stores, ObjectProvider<ArtifactLifecycle> lifecycles,
            WorkspaceScopeResolver workspaces,
            ObjectProvider<ControlActionAudit> audits) {
        this.stores = Objects.requireNonNull(stores, "stores");
        this.lifecycles = Objects.requireNonNull(lifecycles, "lifecycles");
        this.workspaces = Objects.requireNonNull(workspaces, "workspaces");
        this.audits = Objects.requireNonNull(audits, "audits");
    }

    @PostMapping(consumes = MediaType.ALL_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ArtifactResource> upload(HttpServletRequest request,
            @RequestHeader(name = "X-Artifact-SHA256", required = false) String expectedDigest,
            @RequestParam(name = "classification", defaultValue = "INTERNAL") ArtifactClassification classification,
            @RequestParam(name = "redacted", defaultValue = "false") boolean redacted,
            @RequestParam(name = "expiresAt", required = false) Instant expiresAt) throws IOException {
        long length = request.getContentLengthLong();
        if (length < 0) throw new ApiRequestException(HttpStatus.LENGTH_REQUIRED, "CONTENT_LENGTH_REQUIRED",
                "Artifact uploads require Content-Length.");
        MediaType mediaType;
        try {
            mediaType = MediaType.parseMediaType(request.getContentType());
        } catch (RuntimeException invalid) {
            throw new ApiRequestException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "UNSUPPORTED_MEDIA_TYPE",
                    "The artifact media type is invalid.");
        }
        ArtifactOwner owner = owner(request);
        String actor = CurrentPrincipal.actor();
        ArtifactDigest expected = digest(expectedDigest);
        ArtifactWrite write = new ArtifactWrite(owner, mediaType.toString(), length, expected, expiresAt,
                classification, redacted, ArtifactProvenance.upload(actor), request.getInputStream());
        // Publication owns its filesystem/database commit boundary; the store records UPLOADED in its lifecycle
        // ledger atomically with metadata, avoiding an outer transaction that could roll back after bytes publish.
        ArtifactRef reference = store().put(write);
        ArtifactResource resource = ArtifactResource.of(lifecycle().metadata(owner, reference));
        return ResponseEntity.created(UriComponentsBuilder.fromPath("/api/v1/artifacts/{id}")
                .build(reference.value())).body(resource);
    }

    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public Page<ArtifactResource> list(HttpServletRequest request,
            @RequestParam(name = "mediaType", required = false) List<String> mediaTypes,
            @RequestParam(name = "classification", required = false) ArtifactClassification classification,
            @RequestParam(name = "cursor", required = false) String cursor,
            @RequestParam(name = "limit", required = false) Integer limit) {
        ArtifactOwner owner = owner(request);
        int size = PageCursor.limit(limit);
        List<String> position = PageCursor.decode(cursor, 2);
        var result = store().search(new ArtifactSearchRequest(owner,
                mediaTypes == null ? Set.of() : Set.copyOf(mediaTypes), classification,
                position == null ? null : PageCursor.instant(position.get(0)),
                position == null ? null : PageCursor.uuid(position.get(1)), size));
        String next = result.hasMore() && !result.entries().isEmpty()
                ? PageCursor.encode(PageCursor.instant(result.entries().getLast().createdAt()),
                        result.entries().getLast().reference().value().toString()) : null;
        return new Page<>(result.entries().stream().map(ArtifactResource::of).toList(), next);
    }

    @GetMapping(path = "/{artifactId}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ArtifactResource metadata(HttpServletRequest request, @PathVariable UUID artifactId) {
        return ArtifactResource.of(lifecycle().metadata(owner(request), new ArtifactRef(artifactId)));
    }

    @GetMapping(path = "/{artifactId}/content")
    public ResponseEntity<StreamingResponseBody> content(HttpServletRequest request, @PathVariable UUID artifactId,
            @RequestHeader(name = HttpHeaders.RANGE, required = false) String requestedRange) {
        ArtifactOwner owner = owner(request);
        ArtifactRef reference = new ArtifactRef(artifactId);
        var metadata = lifecycle().metadata(owner, reference);
        Range range = range(requestedRange, metadata.size());
        ArtifactContent content = store().read(new ArtifactRead(owner, reference, range.offset(), range.length()));
        StreamingResponseBody body = output -> {
            try (content) {
                content.stream().transferTo(output);
            }
        };
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType(metadata.mediaType()));
        headers.setContentLength(content.length());
        headers.set(HttpHeaders.ACCEPT_RANGES, "bytes");
        headers.set(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"artifact-" + artifactId + "\"");
        headers.set("X-Content-Type-Options", "nosniff");
        headers.set("Content-Security-Policy", "sandbox; default-src 'none'");
        headers.setCacheControl("no-store");
        headers.setETag('"' + metadata.digest().value() + '"');
        if (range.partial()) {
            headers.set(HttpHeaders.CONTENT_RANGE, "bytes " + content.offset() + "-"
                    + (content.offset() + content.length() - 1) + "/" + metadata.size());
        }
        return new ResponseEntity<>(body, headers, range.partial() ? HttpStatus.PARTIAL_CONTENT : HttpStatus.OK);
    }

    @PostMapping(path = "/{artifactId}/expire", produces = MediaType.APPLICATION_JSON_VALUE)
    public ArtifactResource expire(HttpServletRequest request, @PathVariable UUID artifactId) {
        ArtifactOwner owner = owner(request);
        ArtifactRef reference = new ArtifactRef(artifactId);
        return ArtifactResource.of(audit().record("ARTIFACT_EXPIRED",
                () -> lifecycle().expire(owner, reference, CurrentPrincipal.actor()),
                result -> target(owner, reference, result.lifecycle().name())));
    }

    @PostMapping(path = "/{artifactId}/validation", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ArtifactResource validate(HttpServletRequest request, @PathVariable UUID artifactId,
            @RequestBody ValidationRequest body) {
        if (body == null || body.validation() == null) throw new IllegalArgumentException("validation is required");
        ArtifactOwner owner = owner(request);
        ArtifactRef reference = new ArtifactRef(artifactId);
        return ArtifactResource.of(audit().record("ARTIFACT_VALIDATION_CHANGED",
                () -> lifecycle().transitionValidation(owner, reference, body.validation(), body.reason(),
                        CurrentPrincipal.actor()), result -> target(owner, reference, result.validation().name())));
    }

    @GetMapping(path = "/{artifactId}/pins", produces = MediaType.APPLICATION_JSON_VALUE)
    public Page<PinResource> pins(HttpServletRequest request, @PathVariable UUID artifactId) {
        return new Page<>(lifecycle().pins(owner(request), new ArtifactRef(artifactId)).stream()
                .map(PinResource::of).toList(), null);
    }

    @PostMapping(path = "/{artifactId}/pins", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<PinResource> pin(HttpServletRequest request, @PathVariable UUID artifactId,
            @RequestBody PinRequest body) {
        if (body == null || body.kind() == null || body.subjectId() == null) {
            throw new IllegalArgumentException("pin kind and subject are required");
        }
        ArtifactOwner owner = owner(request);
        ArtifactRef reference = new ArtifactRef(artifactId);
        var pin = audit().record("ARTIFACT_PINNED", () -> lifecycle().pin(owner, reference, body.kind(),
                body.subjectId(), body.expiresAt(), CurrentPrincipal.actor()),
                result -> target(owner, reference, result.kind().name()));
        return ResponseEntity.created(UriComponentsBuilder.fromPath("/api/v1/artifacts/{artifactId}/pins/{pinId}")
                .build(artifactId, pin.id())).body(PinResource.of(pin));
    }

    @DeleteMapping(path = "/{artifactId}/pins/{pinId}")
    public ResponseEntity<Void> unpin(HttpServletRequest request, @PathVariable UUID artifactId,
            @PathVariable UUID pinId) {
        ArtifactOwner owner = owner(request);
        ArtifactRef reference = new ArtifactRef(artifactId);
        audit().record("ARTIFACT_UNPINNED", () -> {
            lifecycle().unpin(owner, reference, pinId, CurrentPrincipal.actor());
            return pinId;
        }, result -> target(owner, reference, result.toString()));
        return ResponseEntity.noContent().build();
    }

    private ArtifactOwner owner(HttpServletRequest request) {
        var workspace = workspaces.resolve(request);
        var principal = CurrentPrincipal.require();
        return new ArtifactOwner(principal.organizationId().value(), workspace.value());
    }

    private ArtifactStore store() {
        ArtifactStore available = stores.getIfAvailable();
        if (available == null) throw unavailable();
        return available;
    }

    private ArtifactLifecycle lifecycle() {
        ArtifactLifecycle available = lifecycles.getIfAvailable();
        if (available == null) throw unavailable();
        return available;
    }

    private ControlActionAudit audit() {
        return audits.getIfAvailable(() -> ControlActionAudit.NONE);
    }

    private static com.dvarahq.agentworks.artifact.ArtifactStoreException unavailable() {
        return new com.dvarahq.agentworks.artifact.ArtifactStoreException(
                com.dvarahq.agentworks.artifact.ArtifactStoreException.Reason.UNAVAILABLE);
    }

    private static ArtifactDigest digest(String hex) {
        if (hex == null || hex.isBlank()) return null;
        return new ArtifactDigest("sha256:" + hex.toLowerCase(java.util.Locale.ROOT));
    }

    private static Range range(String value, long size) {
        if (value == null) return new Range(0, null, false);
        if (!value.matches("bytes=[0-9]+-[0-9]*")) throw new com.dvarahq.agentworks.artifact.ArtifactStoreException(
                com.dvarahq.agentworks.artifact.ArtifactStoreException.Reason.INVALID_RANGE);
        String[] parts = value.substring("bytes=".length()).split("-", -1);
        try {
            long start = Long.parseLong(parts[0]);
            long end = parts[1].isEmpty() ? size - 1 : Long.parseLong(parts[1]);
            if (start > end || end >= size) throw new NumberFormatException();
            return new Range(start, end - start + 1, true);
        } catch (NumberFormatException invalid) {
            throw new com.dvarahq.agentworks.artifact.ArtifactStoreException(
                    com.dvarahq.agentworks.artifact.ArtifactStoreException.Reason.INVALID_RANGE);
        }
    }

    private static ControlActionAudit.Target target(ArtifactOwner owner, ArtifactRef reference, String detail) {
        return new ControlActionAudit.Target("ARTIFACT", reference.value(), detail);
    }

    private record Range(long offset, Long length, boolean partial) {}
}
