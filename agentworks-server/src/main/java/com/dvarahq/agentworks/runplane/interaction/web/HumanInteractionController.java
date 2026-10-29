package com.dvarahq.agentworks.runplane.interaction.web;

import com.dvarahq.agentworks.api.web.ApiRequestException;
import com.dvarahq.agentworks.api.web.Page;
import com.dvarahq.agentworks.api.web.PageCursor;
import com.dvarahq.agentworks.controlplane.agent.web.WorkspaceScopeResolver;
import com.dvarahq.agentworks.identity.web.CurrentPrincipal;
import com.dvarahq.agentworks.interaction.HumanInteractionAuthorityException;
import com.dvarahq.agentworks.interaction.HumanInteractionStatus;
import com.dvarahq.agentworks.runplane.execution.DurableHumanInteractionCoordinator;
import com.dvarahq.agentworks.runplane.interaction.web.HumanInteractionApiResources.InteractionResource;
import com.dvarahq.agentworks.runplane.interaction.web.HumanInteractionApiResources.ResponseRequest;
import com.dvarahq.agentworks.storage.interaction.JdbcHumanInteractionAuthority;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Tenant-scoped REST boundary for inspecting and answering durable human interactions. */
@RestController
@RequestMapping("/api/v1/interactions")
public final class HumanInteractionController {
    private final JdbcHumanInteractionAuthority interactions;
    private final DurableHumanInteractionCoordinator coordinator;
    private final WorkspaceScopeResolver workspaces;

    public HumanInteractionController(JdbcHumanInteractionAuthority interactions,
            DurableHumanInteractionCoordinator coordinator, WorkspaceScopeResolver workspaces) {
        this.interactions = Objects.requireNonNull(interactions, "interactions");
        this.coordinator = Objects.requireNonNull(coordinator, "coordinator");
        this.workspaces = Objects.requireNonNull(workspaces, "workspaces");
    }

    /** Interactions ordered newest first in stable keyset pages. */
    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public Page<InteractionResource> list(HttpServletRequest request,
            @RequestParam(name = "status", required = false) String status,
            @RequestParam(name = "runId", required = false) UUID runId,
            @RequestParam(name = "cursor", required = false) String cursor,
            @RequestParam(name = "limit", required = false) Integer limit) {
        var workspace = workspaces.resolve(request);
        HumanInteractionStatus filter = status(status);
        int size = PageCursor.limit(limit);
        List<String> position = PageCursor.decode(cursor, 2);
        return Page.of(interactions.list(workspace, filter, runId,
                        position == null ? null : PageCursor.instant(position.get(0)),
                        position == null ? null : PageCursor.uuid(position.get(1)), size + 1), size,
                interaction -> PageCursor.encode(PageCursor.instant(interaction.request().createdAt()),
                        interaction.request().id().toString())).map(InteractionResource::of);
    }

    @GetMapping(path = "/{interactionId}", produces = MediaType.APPLICATION_JSON_VALUE)
    public InteractionResource get(HttpServletRequest request, @PathVariable UUID interactionId) {
        var workspace = workspaces.resolve(request);
        return InteractionResource.of(interactions.find(workspace, interactionId).orElseThrow(() ->
                new HumanInteractionAuthorityException(HumanInteractionAuthorityException.Code.NOT_FOUND,
                        "interaction does not exist")));
    }

    @PostMapping(path = "/{interactionId}/response", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public InteractionResource respond(HttpServletRequest request, @PathVariable UUID interactionId,
            @RequestBody ResponseRequest body) {
        if (body == null) {
            throw new ApiRequestException(HttpStatus.UNPROCESSABLE_CONTENT, "INVALID_RESPONSE",
                    "The response does not match the interaction request.");
        }
        var workspace = workspaces.resolve(request);
        var current = interactions.find(workspace, interactionId).orElseThrow(() ->
                new HumanInteractionAuthorityException(HumanInteractionAuthorityException.Code.NOT_FOUND,
                        "interaction does not exist"));
        return InteractionResource.of(coordinator.respond(workspace, interactionId,
                CurrentPrincipal.interactionIdentity(workspace), body.value(current.request().prompt().kind()),
                CurrentPrincipal.actor()).interaction());
    }

    @PostMapping(path = "/{interactionId}/expire", produces = MediaType.APPLICATION_JSON_VALUE)
    public InteractionResource expire(HttpServletRequest request, @PathVariable UUID interactionId) {
        var workspace = workspaces.resolve(request);
        return InteractionResource.of(coordinator.expire(workspace, interactionId, CurrentPrincipal.actor())
                .interaction());
    }

    @PostMapping(path = "/{interactionId}/cancel", produces = MediaType.APPLICATION_JSON_VALUE)
    public InteractionResource cancel(HttpServletRequest request, @PathVariable UUID interactionId) {
        var workspace = workspaces.resolve(request);
        return InteractionResource.of(coordinator.cancel(workspace, interactionId, CurrentPrincipal.actor())
                .interaction());
    }

    @PostMapping(path = "/{interactionId}/revoke", produces = MediaType.APPLICATION_JSON_VALUE)
    public InteractionResource revoke(HttpServletRequest request, @PathVariable UUID interactionId) {
        var workspace = workspaces.resolve(request);
        return InteractionResource.of(coordinator.revoke(workspace, interactionId, CurrentPrincipal.actor())
                .interaction());
    }

    private static HumanInteractionStatus status(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return HumanInteractionStatus.valueOf(value);
        } catch (IllegalArgumentException invalid) {
            throw new ApiRequestException(HttpStatus.BAD_REQUEST, "INVALID_FILTER",
                    "status is not a supported interaction status.");
        }
    }
}
