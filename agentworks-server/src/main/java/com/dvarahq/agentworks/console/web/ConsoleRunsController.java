package com.dvarahq.agentworks.console.web;

import com.dvarahq.agentworks.domain.run.RunEventType;
import com.dvarahq.agentworks.domain.run.RunLifecycle;
import com.dvarahq.agentworks.runplane.admission.web.RunAdmissionController.RunView;
import com.dvarahq.agentworks.runplane.admission.web.RunEventView;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Runs (issue #36): launch a run of a deployment, list runs, and inspect one run: its exact pins (release, deployment
 * revision, cohort, routing generation, and the concrete models of its release manifest), lifecycle, trace, usage,
 * failures, steps, and events, followed live over the #35 stream by the page's script.
 */
@Controller
public final class ConsoleRunsController {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    /** The events the run page renders before the live stream continues after the last of them. */
    static final int FIRST_EVENTS = 100;
    /** The terminal lifecycles, as the page's script needs them for runs it reads from the API. */
    static final String TERMINAL_LIFECYCLES = Arrays.stream(RunLifecycle.values()).filter(RunLifecycle::terminal)
            .map(Enum::name).collect(Collectors.joining(","));
    /** Every run event type, so that the page's script listens for each named SSE event. */
    static final String EVENT_TYPES = Arrays.stream(RunEventType.values()).map(Enum::name)
            .collect(Collectors.joining(","));

    private final ConsoleApi api;

    public ConsoleRunsController(ConsoleApi api) {
        this.api = Objects.requireNonNull(api, "api");
    }

    @GetMapping("/console/runs")
    public String runs(HttpServletRequest request,
            @RequestParam(name = "deployment", required = false) String deployment,
            @RequestParam(name = "lifecycle", required = false) String lifecycle,
            @RequestParam(name = "cursor", required = false) String cursor, Model model) {
        UUID deploymentId = ConsoleSupport.uuid(deployment, "deployment");
        String filter = lifecycle == null || lifecycle.isBlank() ? null : lifecycle;
        model.addAttribute("runs", api.runs(request, deploymentId, filter, cursor));
        model.addAttribute("deploymentFilter", deploymentId);
        model.addAttribute("lifecycleFilter", filter);
        return "console/runs";
    }

    /** Launches a run with a JSON input document, exactly as {@code POST /deployments/{id}/runs}. */
    @PostMapping("/console/deployments/{deploymentId}/runs")
    public ResponseEntity<Void> launch(HttpServletRequest request, @PathVariable UUID deploymentId,
            @RequestParam(name = "input", required = false) String input) {
        JsonNode document;
        try {
            document = input == null || input.isBlank() ? JSON.createObjectNode() : JSON.readTree(input);
        } catch (RuntimeException unreadable) {
            throw ConsoleSupport.invalid("INVALID_INPUT", "The run input must be a JSON document.");
        }
        RunView run = api.launch(request, deploymentId, document);
        return ConsoleSupport.redirect(request, "/console/runs/" + run.id());
    }

    @GetMapping("/console/runs/{id}")
    public String run(HttpServletRequest request, @PathVariable UUID id, Model model) {
        RunView run = summary(request, id, model);
        var page = api.events(request, id, null, FIRST_EVENTS);
        List<EventRow> events = page.items().stream().map(EventRow::of).toList();
        model.addAttribute("events", events);
        long cursor = events.isEmpty() ? 0 : events.getLast().sequence();
        model.addAttribute("cursor", cursor);
        model.addAttribute("moreEvents", page.nextCursor() != null);
        // A terminal run whose every event is on the page opens no stream.
        model.addAttribute("complete", terminal(run) && page.nextCursor() == null && cursor >= run.eventSequence());
        model.addAttribute("eventTypes", EVENT_TYPES);
        model.addAttribute("terminalLifecycles", TERMINAL_LIFECYCLES);
        model.addAttribute("models", models(request, run));
        model.addAttribute("context", api.context(request, id));
        return "console/run";
    }

    /** The run's summary alone, which the page refreshes as the live stream moves on; its pins never change. */
    @GetMapping("/console/runs/{id}/summary")
    public String summaryFragment(HttpServletRequest request, @PathVariable UUID id, Model model) {
        summary(request, id, model);
        return "console/run :: summary";
    }

    /** The run's actions alone, which the page refreshes once the run has ended. */
    @GetMapping("/console/runs/{id}/actions")
    public String actionsFragment(HttpServletRequest request, @PathVariable UUID id, Model model) {
        RunView run = api.run(request, id);
        model.addAttribute("run", run);
        model.addAttribute("terminal", terminal(run));
        return "console/run :: actions";
    }

    @PostMapping("/console/runs/{id}/cancel")
    public ResponseEntity<Void> cancel(HttpServletRequest request, @PathVariable UUID id,
            @RequestParam(name = "reason", required = false) String reason) {
        api.cancel(request, id, reason);
        return ConsoleSupport.redirect(request, "/console/runs/" + id);
    }

    @PostMapping("/console/runs/{id}/pause")
    public ResponseEntity<Void> pause(HttpServletRequest request, @PathVariable UUID id,
            @RequestParam(name = "reason", required = false) String reason) {
        api.pause(request, id, reason);
        return ConsoleSupport.redirect(request, "/console/runs/" + id);
    }

    @PostMapping("/console/runs/{id}/resume")
    public ResponseEntity<Void> resume(HttpServletRequest request, @PathVariable UUID id) {
        api.resume(request, id);
        return ConsoleSupport.redirect(request, "/console/runs/" + id);
    }

    private RunView summary(HttpServletRequest request, UUID id, Model model) {
        RunView run = api.run(request, id);
        model.addAttribute("run", run);
        model.addAttribute("terminal", terminal(run));
        model.addAttribute("steps", api.steps(request, id).items());
        return run;
    }

    private static boolean terminal(RunView run) {
        return RunLifecycle.valueOf(run.lifecycle()).terminal();
    }

    /**
     * The concrete model pinned for each model role by the run's release manifest; an empty list when the manifest
     * pins none (an executor without models) or cannot be read now, which the page says rather than failing.
     */
    private List<ModelPin> models(HttpServletRequest request, RunView run) {
        if (run.releaseId() == null) return List.of();
        JsonNode manifest;
        try {
            manifest = api.manifest(request, run.releaseId());
        } catch (ConsoleProblemException unavailable) {
            return List.of();
        }
        List<ModelPin> pins = new ArrayList<>();
        for (Map.Entry<String, JsonNode> role : manifest.path("models").properties()) {
            List<Field> fields = new ArrayList<>();
            for (Map.Entry<String, JsonNode> field : role.getValue().properties()) {
                fields.add(new Field(field.getKey(), field.getValue().isValueNode() ? field.getValue().asString()
                        : field.getValue().toString()));
            }
            pins.add(new ModelPin(role.getKey(), fields));
        }
        return pins;
    }

    /** One model role and the fields of its pinned model, all rendered escaped. */
    public record ModelPin(String role, List<Field> fields) {}

    public record Field(String name, String value) {}

    /** One run event as the page renders it; the payload is pretty-printed JSON text, rendered escaped. */
    public record EventRow(long sequence, String type, String category, String createdAt, String actor,
            String payload) {
        static EventRow of(RunEventView event) {
            return new EventRow(event.sequence(), event.type(), event.category(),
                    event.createdAt() == null ? null : event.createdAt().toString(), event.actor(),
                    event.payload() == null ? "" : JSON.writerWithDefaultPrettyPrinter().writeValueAsString(
                            event.payload()));
        }
    }
}
