package com.dvarahq.agentworks.console.web;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

import com.dvarahq.agentworks.api.web.PageCursor;
import com.dvarahq.agentworks.controlplane.lifecycle.LifecycleModel.ReleaseView;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Releases, environments, and deployments (issue #36): list releases, create an environment, promote a release into
 * a deployment, and inspect a deployment's exact current revision, routing generation, and cohorts. Promotion is the
 * API's atomic promotion; the Console shows the committed result and never keeps routing state of its own.
 */
@Controller
public final class ConsoleDeploymentsController {
    public record CohortReportView(String outcome, String state,
            List<com.dvarahq.agentworks.controlplane.lifecycle.LifecycleModel.Blocker> blockers) {}

    private final ConsoleApi api;

    public ConsoleDeploymentsController(ConsoleApi api) {
        this.api = Objects.requireNonNull(api, "api");
    }

    @GetMapping("/console/releases")
    public String releases(HttpServletRequest request, @RequestParam(name = "cursor", required = false) String cursor,
            Model model) {
        model.addAttribute("releases", api.releases(request, cursor));
        return "console/releases";
    }

    @GetMapping("/console/deployments")
    public String deployments(HttpServletRequest request,
            @RequestParam(name = "environment", required = false) String environment,
            @RequestParam(name = "release", required = false) String release,
            @RequestParam(name = "cursor", required = false) String cursor, Model model) {
        UUID environmentId = ConsoleSupport.uuid(environment, "environment");
        model.addAttribute("environments", api.environments(request).items());
        model.addAttribute("deployments", api.deployments(request, environmentId, cursor));
        UUID requestedRelease = ConsoleSupport.uuid(release, "release");
        // The largest page the API serves. A release chosen elsewhere (a newly built one, or the Releases page's
        // Promote link) is always offered, even beyond that page; with none chosen, the form offers no default.
        List<ReleaseView> releases = new ArrayList<>(api.releases(request, null, PageCursor.MAX_LIMIT).items());
        UUID selectedRelease = requestedRelease;
        if (requestedRelease != null && releases.stream().noneMatch(candidate -> candidate.id().equals(requestedRelease))) {
            try {
                releases.addFirst(api.release(request, requestedRelease));
            } catch (ConsoleProblemException unreadable) {
                // Not a release of this workspace: the form offers no preselection rather than failing the page.
                selectedRelease = null;
            }
        }
        model.addAttribute("releases", releases);
        model.addAttribute("selectedEnvironment", environmentId);
        model.addAttribute("selectedRelease", selectedRelease);
        return "console/deployments";
    }

    @PostMapping("/console/environments")
    public ResponseEntity<Void> createEnvironment(HttpServletRequest request,
            @RequestParam(name = "name", required = false) String name,
            @RequestParam(name = "description", required = false) String description) {
        var created = api.createEnvironment(request, name,
                description == null || description.isBlank() ? null : description);
        return ConsoleSupport.redirect(request, "/console/deployments?environment=" + created.id());
    }

    /**
     * Promotes a release into a deployment of an environment. The committed decision decides what happens next: a
     * promotion that the environment's gates refuse is shown with its gate results, not as an error of the Console.
     */
    @PostMapping("/console/promotions")
    public Object promote(HttpServletRequest request,
            @RequestParam(name = "environmentId", required = false) String environment,
            @RequestParam(name = "deployment", required = false) String deployment,
            @RequestParam(name = "releaseId", required = false) String releaseId,
            @RequestParam(name = "reason", required = false) String reason, Model model) {
        UUID environmentId = ConsoleSupport.uuid(environment, "environmentId");
        UUID release = ConsoleSupport.uuid(releaseId, "releaseId");
        if (environmentId == null) throw ConsoleSupport.invalid("INVALID_REQUEST", "Choose an environment.");
        if (release == null) throw ConsoleSupport.invalid("INVALID_REQUEST", "Choose a release to promote.");
        var result = api.promote(request, environmentId, deployment, release,
                reason == null || reason.isBlank() ? null : reason);
        if (result.deploymentId() != null && result.revisionId() != null) {
            return ConsoleSupport.redirect(request, "/console/deployments/" + result.deploymentId());
        }
        model.addAttribute("promotion", result);
        return "console/fragments :: promotion";
    }

    @GetMapping("/console/deployments/{id}")
    public String deployment(HttpServletRequest request, @PathVariable UUID id, Model model) {
        return deployment(request, id, model, null);
    }

    @PostMapping("/console/deployments/{deploymentId}/cohorts/{cohortId}/{action:suspend|resume|revoke}")
    public ResponseEntity<Void> transitionCohort(HttpServletRequest request, @PathVariable UUID deploymentId,
            @PathVariable UUID cohortId, @PathVariable String action, @RequestParam long version,
            @RequestParam String idempotencyKey, @RequestParam(required = false) String reason) {
        api.transitionCohort(request, cohortId, action, version, idempotencyKey, reason);
        return ConsoleSupport.redirect(request, "/console/deployments/" + deploymentId);
    }

    @PostMapping("/console/deployments/{deploymentId}/cohorts/{cohortId}/retire")
    public Object retireCohort(HttpServletRequest request, @PathVariable UUID deploymentId,
            @PathVariable UUID cohortId, @RequestParam long version, @RequestParam String idempotencyKey,
            @RequestParam(required = false) String reason, Model model) {
        var result = api.retireCohort(request, cohortId, version, idempotencyKey, reason);
        if ("RETIRED".equals(result.outcome())) {
            return ConsoleSupport.redirect(request, "/console/deployments/" + deploymentId);
        }
        return deployment(request, deploymentId, model,
                new CohortReportView(result.outcome(), result.state(), result.blockers()));
    }

    @PostMapping("/console/deployments/{deploymentId}/cohorts/{cohortId}/quiescence")
    public String quiescence(HttpServletRequest request, @PathVariable UUID deploymentId,
            @PathVariable UUID cohortId, Model model) {
        var report = api.observeQuiescence(request, cohortId);
        return deployment(request, deploymentId, model,
                new CohortReportView(null, report.state(), report.blockers()));
    }

    private String deployment(HttpServletRequest request, UUID id, Model model, CohortReportView cohortReport) {
        var detail = api.deployment(request, id);
        model.addAttribute("detail", detail);
        UUID current = detail.deployment().desiredReleaseId();
        model.addAttribute("release", current == null ? null : api.release(request, current));
        model.addAttribute("runs", api.runs(request, id, null, null));
        model.addAttribute("cohortReport", cohortReport);
        model.addAttribute("actionKey", UUID.randomUUID().toString());
        return "console/deployment";
    }
}
