package com.dvarahq.agentworks.runplane.operation.web;

import com.dvarahq.agentworks.api.web.ApiRequestException;
import com.dvarahq.agentworks.api.web.Page;
import com.dvarahq.agentworks.api.web.PageCursor;
import com.dvarahq.agentworks.controlplane.agent.web.WorkspaceScopeResolver;
import com.dvarahq.agentworks.domain.tenant.WorkspaceId;
import com.dvarahq.agentworks.identity.web.CurrentPrincipal;
import com.dvarahq.agentworks.operation.DurableOperationAuthorityException;
import com.dvarahq.agentworks.operation.DurableOperationStatus;
import com.dvarahq.agentworks.runplane.execution.DurableOperationCoordinator;
import com.dvarahq.agentworks.runplane.operation.web.OperationApiResources.CallbackRequest;
import com.dvarahq.agentworks.runplane.operation.web.OperationApiResources.OperationResource;
import com.dvarahq.agentworks.storage.operation.JdbcDurableOperationAuthority;
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
import java.util.Objects;
import java.util.UUID;

/** Authenticated operation visibility/cancellation plus token-authenticated provider callbacks. */
@RestController
@RequestMapping("/api/v1")
public final class DurableOperationController {
    public static final String CALLBACK_TOKEN = "Dvara-Operation-Token";
    private final JdbcDurableOperationAuthority operations;
    private final DurableOperationCoordinator coordinator;
    private final WorkspaceScopeResolver workspaces;

    public DurableOperationController(JdbcDurableOperationAuthority operations,
            DurableOperationCoordinator coordinator, WorkspaceScopeResolver workspaces) {
        this.operations = Objects.requireNonNull(operations, "operations");
        this.coordinator = coordinator;
        this.workspaces = Objects.requireNonNull(workspaces, "workspaces");
    }

    @GetMapping(path = "/operations", produces = MediaType.APPLICATION_JSON_VALUE)
    public Page<OperationResource> list(HttpServletRequest request,
            @RequestParam(name = "sessionId", required = false) UUID sessionId,
            @RequestParam(name = "status", required = false) String status,
            @RequestParam(name = "cursor", required = false) String cursor,
            @RequestParam(name = "limit", required = false) Integer limit) {
        int size = PageCursor.limit(limit);
        List<String> position = PageCursor.decode(cursor, 2);
        return Page.of(operations.list(workspaces.resolve(request), sessionId, status(status),
                        position == null ? null : PageCursor.instant(position.get(0)),
                        position == null ? null : PageCursor.uuid(position.get(1)), size + 1), size,
                value -> PageCursor.encode(PageCursor.instant(value.createdAt()), value.id().toString()))
                .map(OperationResource::of);
    }

    @GetMapping(path = "/operations/{operationId}", produces = MediaType.APPLICATION_JSON_VALUE)
    public OperationResource get(HttpServletRequest request, @PathVariable UUID operationId) {
        return OperationResource.of(operations.find(workspaces.resolve(request), operationId).orElseThrow(() ->
                new DurableOperationAuthorityException(DurableOperationAuthorityException.Code.NOT_FOUND,
                        "operation does not exist")));
    }

    @PostMapping(path = "/operations/{operationId}/cancel", produces = MediaType.APPLICATION_JSON_VALUE)
    public OperationResource cancel(HttpServletRequest request, @PathVariable UUID operationId) {
        return OperationResource.of(coordinator().cancel(workspaces.resolve(request), operationId,
                CurrentPrincipal.actor()).operation());
    }

    /** The callback token is compared by digest and is never logged, returned, or persisted in clear text. */
    @PostMapping(path = "/operation-callbacks/{workspaceId}/{operationId}",
            consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Void> callback(@PathVariable UUID workspaceId, @PathVariable UUID operationId,
            @RequestHeader(name = CALLBACK_TOKEN) String callbackToken, @RequestBody CallbackRequest body) {
        if (body == null || body.status() == null || !body.status().terminal() || body.code() == null
                || body.code().isBlank() || body.code().length() > 128) {
            throw new ApiRequestException(HttpStatus.UNPROCESSABLE_CONTENT, "INVALID_OPERATION_RESULT",
                    "A callback requires a terminal status and result code.");
        }
        coordinator().callback(new WorkspaceId(workspaceId), operationId, callbackToken, body.result());
        return ResponseEntity.noContent().build();
    }

    private DurableOperationCoordinator coordinator() {
        if (coordinator == null) throw new ApiRequestException(HttpStatus.UNPROCESSABLE_CONTENT,
                "IDEMPOTENCY_UNAVAILABLE", "Durable operation callback keys are not configured.");
        return coordinator;
    }

    private static DurableOperationStatus status(String value) {
        if (value == null || value.isBlank()) return null;
        try { return DurableOperationStatus.valueOf(value); }
        catch (IllegalArgumentException invalid) {
            throw new ApiRequestException(HttpStatus.BAD_REQUEST, "INVALID_FILTER",
                    "status is not a supported operation status.");
        }
    }
}
