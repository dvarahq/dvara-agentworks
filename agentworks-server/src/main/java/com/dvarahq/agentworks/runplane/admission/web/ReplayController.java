package com.dvarahq.agentworks.runplane.admission.web;

import com.dvarahq.agentworks.controlplane.agent.web.WorkspaceScopeResolver;
import com.dvarahq.agentworks.replay.ReplayMode;
import com.dvarahq.agentworks.replay.ReplayService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Objects;
import java.util.UUID;

/** Safe, tenant-scoped replay planning and execution (issue #52). */
@RestController
@RequestMapping(path = "/api/v1/runs/{id}/replay", produces = MediaType.APPLICATION_JSON_VALUE)
public final class ReplayController {
    private final ReplayService replays;
    private final WorkspaceScopeResolver workspaces;

    public ReplayController(ReplayService replays, WorkspaceScopeResolver workspaces) {
        this.replays = Objects.requireNonNull(replays, "replays");
        this.workspaces = Objects.requireNonNull(workspaces, "workspaces");
    }

    @GetMapping("/plan")
    public ReplayService.ReplayPlan plan(HttpServletRequest request, @PathVariable UUID id,
            @org.springframework.web.bind.annotation.RequestParam ReplayMode mode) {
        return replays.plan(workspaces.resolve(request), id, mode);
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public ReplayService.ReplayResult replay(HttpServletRequest request, @PathVariable UUID id,
            @RequestBody tools.jackson.databind.JsonNode body) {
        if (body == null || !body.isObject() || body.size() != 1 || !body.has("mode")
                || !body.path("mode").isString()) {
            throw new com.dvarahq.agentworks.replay.ReplayException("INVALID_REPLAY_MODE",
                    "mode must be EXACT or MODEL_REPLAY");
        }
        ReplayMode mode;
        try {
            mode = ReplayMode.valueOf(body.path("mode").stringValue());
        } catch (IllegalArgumentException invalid) {
            throw new com.dvarahq.agentworks.replay.ReplayException("INVALID_REPLAY_MODE",
                    "mode must be EXACT or MODEL_REPLAY");
        }
        return replays.replay(workspaces.resolve(request), id, mode);
    }
}
