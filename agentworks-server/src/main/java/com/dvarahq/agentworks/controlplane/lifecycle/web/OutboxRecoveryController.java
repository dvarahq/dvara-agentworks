package com.dvarahq.agentworks.controlplane.lifecycle.web;

import com.dvarahq.agentworks.autoconfigure.OutboxReconciliationLifecycle;
import com.dvarahq.agentworks.controlplane.agent.web.WorkspaceScopeResolver;
import com.dvarahq.agentworks.controlplane.deployment.ControlPlaneOutboxDispatcher;
import com.dvarahq.agentworks.controlplane.deployment.ControlPlaneOutboxDispatcher.Quarantined;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Workspace-scoped operator recovery for poison desired-state notifications (#44). */
@RestController
@RequestMapping(path = "/api/v1/outbox-quarantines", produces = MediaType.APPLICATION_JSON_VALUE)
public final class OutboxRecoveryController {
    private final ControlPlaneOutboxDispatcher dispatcher;
    private final WorkspaceScopeResolver workspaces;
    private final OutboxReconciliationLifecycle lifecycle;

    public OutboxRecoveryController(ControlPlaneOutboxDispatcher dispatcher, WorkspaceScopeResolver workspaces,
            OutboxReconciliationLifecycle lifecycle) {
        this.dispatcher = Objects.requireNonNull(dispatcher, "dispatcher");
        this.workspaces = Objects.requireNonNull(workspaces, "workspaces");
        this.lifecycle = Objects.requireNonNull(lifecycle, "lifecycle");
    }

    @GetMapping
    public List<Quarantined> quarantined(HttpServletRequest request) {
        return dispatcher.quarantined(workspaces.resolve(request), lifecycle.consumer());
    }

    @PostMapping("/{outboxId}/release")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void release(HttpServletRequest request, @PathVariable UUID outboxId) {
        if (!dispatcher.release(workspaces.resolve(request), lifecycle.consumer(), outboxId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "outbox quarantine not found");
        }
    }
}
