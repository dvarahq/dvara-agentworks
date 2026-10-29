package com.dvarahq.agentworks.runplane.session.web;

import com.dvarahq.agentworks.api.web.ApiRequestException;
import com.dvarahq.agentworks.api.web.Page;
import com.dvarahq.agentworks.api.web.PageCursor;
import com.dvarahq.agentworks.controlplane.agent.web.WorkspaceScopeResolver;
import com.dvarahq.agentworks.identity.web.CurrentPrincipal;
import com.dvarahq.agentworks.runplane.execution.SessionDeliveryCoordinator;
import com.dvarahq.agentworks.runplane.session.web.SessionApiResources.CreateSessionRequest;
import com.dvarahq.agentworks.runplane.session.web.SessionApiResources.DeliverMessageRequest;
import com.dvarahq.agentworks.runplane.session.web.SessionApiResources.InboxResource;
import com.dvarahq.agentworks.runplane.session.web.SessionApiResources.SessionResource;
import com.dvarahq.agentworks.session.InboxMessageStatus;
import com.dvarahq.agentworks.session.SessionAuthorityException;
import com.dvarahq.agentworks.storage.session.JdbcSessionInboxAuthority;
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
import org.springframework.web.util.UriComponentsBuilder;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Workspace-scoped logical-session and durable-inbox REST boundary. */
@RestController
@RequestMapping("/api/v1/sessions")
public final class SessionController {
    private final JdbcSessionInboxAuthority inbox;
    private final SessionDeliveryCoordinator deliveries;
    private final WorkspaceScopeResolver workspaces;

    public SessionController(JdbcSessionInboxAuthority inbox, SessionDeliveryCoordinator deliveries,
            WorkspaceScopeResolver workspaces) {
        this.inbox = Objects.requireNonNull(inbox, "inbox");
        this.deliveries = Objects.requireNonNull(deliveries, "deliveries");
        this.workspaces = Objects.requireNonNull(workspaces, "workspaces");
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<SessionResource> create(HttpServletRequest request, @RequestBody CreateSessionRequest body) {
        if (body == null || body.deploymentId() == null) throw invalid("deploymentId is required");
        var session = inbox.create(workspaces.resolve(request), UUID.randomUUID(), body.deploymentId());
        return ResponseEntity.created(UriComponentsBuilder.fromPath("/api/v1/sessions/{id}").build(session.id()))
                .body(SessionResource.of(session));
    }

    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public Page<SessionResource> list(HttpServletRequest request,
            @RequestParam(name = "cursor", required = false) String cursor,
            @RequestParam(name = "limit", required = false) Integer limit) {
        int size = PageCursor.limit(limit);
        List<String> position = PageCursor.decode(cursor, 2);
        return Page.of(inbox.listSessions(workspaces.resolve(request),
                        position == null ? null : PageCursor.instant(position.get(0)),
                        position == null ? null : PageCursor.uuid(position.get(1)), size + 1), size,
                value -> PageCursor.encode(PageCursor.instant(value.createdAt()), value.id().toString()))
                .map(SessionResource::of);
    }

    @GetMapping(path = "/{sessionId}", produces = MediaType.APPLICATION_JSON_VALUE)
    public SessionResource get(HttpServletRequest request, @PathVariable UUID sessionId) {
        return SessionResource.of(inbox.findSession(workspaces.resolve(request), sessionId).orElseThrow(() ->
                new SessionAuthorityException(SessionAuthorityException.Code.NOT_FOUND, "session does not exist")));
    }

    @PostMapping(path = "/{sessionId}/messages", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<InboxResource> deliver(HttpServletRequest request, @PathVariable UUID sessionId,
            @RequestHeader(name = "Idempotency-Key") String idempotencyKey,
            @RequestBody DeliverMessageRequest body) {
        if (body == null || body.type() == null || body.correlationId() == null || body.payload() == null) {
            throw invalid("type, correlationId, and payload are required");
        }
        if (idempotencyKey == null || idempotencyKey.isBlank() || idempotencyKey.length() > 128) {
            throw invalid("Idempotency-Key must be 1 to 128 characters");
        }
        var workspace = workspaces.resolve(request);
        var delivery = deliveries.deliver(workspace, sessionId, body.runId(), body.type(), body.correlationId(),
                idempotencyKey, Map.copyOf(body.payload()), null, CurrentPrincipal.actor(),
                CurrentPrincipal.invoker(workspace));
        var response = ResponseEntity.status(delivery.replayed() ? HttpStatus.OK : HttpStatus.CREATED)
                .location(UriComponentsBuilder.fromPath("/api/v1/sessions/{sessionId}/messages/{id}")
                        .build(sessionId, delivery.message().id()));
        if (delivery.replayed()) response.header("Idempotent-Replayed", "true");
        return response.body(InboxResource.of(delivery.message()));
    }

    @GetMapping(path = "/{sessionId}/messages", produces = MediaType.APPLICATION_JSON_VALUE)
    public Page<InboxResource> messages(HttpServletRequest request, @PathVariable UUID sessionId,
            @RequestParam(name = "status", required = false) String status,
            @RequestParam(name = "cursor", required = false) String cursor,
            @RequestParam(name = "limit", required = false) Integer limit) {
        int size = PageCursor.limit(limit);
        Long before = cursor == null ? null : PageCursor.sequence(cursor);
        InboxMessageStatus filter = null;
        if (status != null && !status.isBlank()) {
            try { filter = InboxMessageStatus.valueOf(status); }
            catch (IllegalArgumentException invalid) { throw invalid("unsupported inbox status"); }
        }
        return Page.of(inbox.list(workspaces.resolve(request), sessionId, filter, before, size + 1), size,
                value -> Long.toString(value.sequence())).map(InboxResource::of);
    }

    @GetMapping(path = "/{sessionId}/messages/{messageId}", produces = MediaType.APPLICATION_JSON_VALUE)
    public InboxResource message(HttpServletRequest request, @PathVariable UUID sessionId,
            @PathVariable UUID messageId) {
        var value = inbox.find(workspaces.resolve(request), messageId).orElseThrow(() ->
                new SessionAuthorityException(SessionAuthorityException.Code.NOT_FOUND,
                        "inbox message does not exist"));
        if (!value.sessionId().equals(sessionId)) throw new SessionAuthorityException(
                SessionAuthorityException.Code.NOT_FOUND, "inbox message does not exist");
        return InboxResource.of(value);
    }

    private static ApiRequestException invalid(String detail) {
        return new ApiRequestException(HttpStatus.UNPROCESSABLE_CONTENT, "INVALID_SESSION_REQUEST", detail);
    }
}
