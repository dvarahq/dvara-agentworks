package com.dvarahq.agentworks.identity.web;

import com.dvarahq.agentworks.api.web.Page;
import com.dvarahq.agentworks.api.web.PageCursor;
import com.dvarahq.agentworks.controlplane.agent.web.WorkspaceScopeResolver;
import com.dvarahq.agentworks.identity.AdministrativeAuditService;
import com.dvarahq.agentworks.storage.identity.IdentityRows.AuditEvent;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Read-only, redacted, workspace-scoped administrative audit API (issue #79). */
@RestController
@RequestMapping(path = "/api/v1/administrative-audit", produces = MediaType.APPLICATION_JSON_VALUE)
public final class AdministrativeAuditController {
    public record Resource(UUID id, Instant occurredAt, String eventType, String outcome, UUID actorId,
            String authenticationMethod, String targetType, UUID targetId, String detail, String integrityDigest) {
        static Resource of(AuditEvent event) {
            String canonical = String.join("\u0000", event.id().toString(), event.occurredAt().toString(),
                    value(event.eventType()), value(event.outcome()), value(event.workspaceId()), value(event.actorId()),
                    value(event.authenticationMethod()), value(event.targetType()), value(event.targetId()),
                    value(event.detail()));
            return new Resource(event.id(), event.occurredAt(), event.eventType(), event.outcome(), event.actorId(),
                    event.authenticationMethod(), event.targetType(), event.targetId(), event.detail(), sha256(canonical));
        }

        private static String value(Object value) { return value == null ? "" : value.toString(); }

        private static String sha256(String value) {
            try {
                return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                        .digest(value.getBytes(StandardCharsets.UTF_8)));
            } catch (java.security.NoSuchAlgorithmException impossible) {
                throw new IllegalStateException(impossible);
            }
        }
    }

    private final AdministrativeAuditService audit;
    private final WorkspaceScopeResolver workspaces;

    public AdministrativeAuditController(AdministrativeAuditService audit, WorkspaceScopeResolver workspaces) {
        this.audit = Objects.requireNonNull(audit, "audit");
        this.workspaces = Objects.requireNonNull(workspaces, "workspaces");
    }

    @GetMapping
    public Page<Resource> list(HttpServletRequest request,
            @RequestParam(name = "eventType", required = false) String eventType,
            @RequestParam(name = "outcome", required = false) String outcome,
            @RequestParam(name = "targetType", required = false) String targetType,
            @RequestParam(name = "targetId", required = false) UUID targetId,
            @RequestParam(name = "cursor", required = false) String cursor,
            @RequestParam(name = "limit", required = false) Integer limit) {
        int size = PageCursor.limit(limit);
        List<String> position = PageCursor.decode(cursor, 2);
        List<AuditEvent> rows = audit.list(workspaces.resolve(request), filter(eventType), filter(outcome),
                filter(targetType), targetId, position == null ? null : PageCursor.instant(position.get(0)),
                position == null ? null : PageCursor.uuid(position.get(1)), size + 1);
        return Page.of(rows.stream().map(Resource::of).toList(), size, resource -> PageCursor.encode(
                PageCursor.instant(resource.occurredAt()), resource.id().toString()));
    }

    private static String filter(String value) {
        if (value == null || value.isBlank()) return null;
        String stripped = value.strip();
        if (stripped.length() > 64 || !stripped.matches("[A-Z][A-Z0-9_]*")) {
            throw new com.dvarahq.agentworks.api.web.ApiRequestException(org.springframework.http.HttpStatus.BAD_REQUEST,
                    "INVALID_AUDIT_FILTER", "Audit filters must be uppercase stable codes.");
        }
        return stripped;
    }
}
