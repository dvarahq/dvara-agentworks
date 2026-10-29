package com.dvarahq.agentworks.console.web;

import com.dvarahq.agentworks.domain.identity.WorkspacePermission;
import com.dvarahq.agentworks.identity.web.CurrentPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Complete read and operator workflows layered over the canonical REST controllers (issue #58). */
@Controller
public final class ConsoleWorkspaceController {
    private final ConsoleApi api;

    public ConsoleWorkspaceController(ConsoleApi api) {
        this.api = Objects.requireNonNull(api, "api");
    }

    @GetMapping("/console/dashboard")
    public String dashboard(HttpServletRequest request, Model model) {
        model.addAttribute("releases", api.releases(request, null, 5).items());
        model.addAttribute("deployments", api.deployments(request, null, null).items().stream().limit(5).toList());
        model.addAttribute("runs", api.runs(request, null, null, null).items().stream().limit(5).toList());
        model.addAttribute("interactions", api.interactions(request, "OPEN", null, null).items().stream().limit(5).toList());
        model.addAttribute("operations", api.operations(request, null, null, null).items().stream().limit(5).toList());
        return "console/dashboard";
    }

    @GetMapping("/console/releases/{id}")
    public String release(HttpServletRequest request, @PathVariable UUID id, Model model) {
        var release = api.release(request, id);
        model.addAttribute("release", release);
        model.addAttribute("manifest", ConsoleApi.prettyJson(api.manifest(request, id)));
        model.addAttribute("lifecycle", api.releaseLifecycle(request, id));
        model.addAttribute("runs", api.runs(request, null, id, null, null));
        try {
            model.addAttribute("bundle", api.releaseBundle(request, id));
        } catch (ConsoleProblemException missing) {
            if (missing.problem().status() != 404) throw missing;
            model.addAttribute("bundle", null);
        }
        model.addAttribute("actionKey", UUID.randomUUID().toString());
        return "console/release";
    }

    @PostMapping("/console/releases/{id}/bundle")
    public ResponseEntity<Void> signBundle(HttpServletRequest request, @PathVariable UUID id) {
        api.signReleaseBundle(request, id);
        return ConsoleSupport.redirect(request, "/console/releases/" + id);
    }

    @PostMapping("/console/releases/{id}/{action:suspend|resume|revoke|retire}")
    public ResponseEntity<Void> transitionRelease(HttpServletRequest request, @PathVariable UUID id,
            @PathVariable String action, @RequestParam long version,
            @RequestParam(name = "idempotencyKey", required = false) String key,
            @RequestParam(name = "reason", required = false) String reason) {
        api.transitionRelease(request, id, action, version, key, reason);
        return ConsoleSupport.redirect(request, "/console/releases/" + id);
    }

    @GetMapping("/console/environments/{id}")
    public String environment(HttpServletRequest request, @PathVariable UUID id, Model model) {
        var environment = api.environment(request, id);
        model.addAttribute("environment", environment);
        model.addAttribute("policy", ConsoleApi.prettyJson(environment.promotionPolicy()));
        model.addAttribute("deployments", api.deployments(request, id, null));
        return "console/environment";
    }

    @PostMapping("/console/environments/{id}/policy")
    public ResponseEntity<Void> policy(HttpServletRequest request, @PathVariable UUID id,
            @RequestParam long version, @RequestParam String policy) {
        api.updateEnvironmentPolicy(request, id, version, ConsoleApi.promotionPolicy(policy));
        return ConsoleSupport.redirect(request, "/console/environments/" + id);
    }

    @GetMapping("/console/audit")
    public String audit(HttpServletRequest request,
            @RequestParam(name = "eventType", required = false) String eventType,
            @RequestParam(name = "outcome", required = false) String outcome,
            @RequestParam(name = "targetType", required = false) String targetType,
            @RequestParam(name = "targetId", required = false) UUID targetId,
            @RequestParam(name = "cursor", required = false) String cursor, Model model) {
        model.addAttribute("audit", api.administrativeAudit(request, eventType, outcome, targetType, targetId, cursor));
        model.addAttribute("eventType", eventType);
        model.addAttribute("outcome", outcome);
        model.addAttribute("targetType", targetType);
        model.addAttribute("targetId", targetId);
        return "console/audit";
    }

    @GetMapping("/console/features")
    public String features(HttpServletRequest request,
            @RequestParam(name = "cursor", required = false) String cursor, Model model) {
        model.addAttribute("datasets", api.evaluationDatasets(request).items());
        model.addAttribute("evaluators", api.evaluators(request).items());
        model.addAttribute("baselines", api.evaluationBaselines(request).items());
        model.addAttribute("tools", api.tools(request, cursor));
        return "console/features";
    }

    @GetMapping("/console/settings")
    public String settings(HttpServletRequest request, Model model) {
        var principal = CurrentPrincipal.require();
        model.addAttribute("members", principal.permissions().contains(WorkspacePermission.MEMBER_ADMIN)
                ? api.members(request, principal).items() : List.of());
        model.addAttribute("tokens", principal.permissions().contains(WorkspacePermission.TOKEN_SELF)
                ? api.tokens(request, principal).items() : List.of());
        return "console/settings";
    }

    @GetMapping("/console/diagnostics")
    public String diagnostics(HttpServletRequest request, Model model) {
        model.addAttribute("recentAudit", api.administrativeAudit(request, null, null, null, null, null).items()
                .stream().limit(20).toList());
        model.addAttribute("recentRuns", api.runs(request, null, null, null).items().stream().limit(20).toList());
        return "console/diagnostics";
    }
}
