package com.dvarahq.agentworks.console.web;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.Objects;
import java.util.UUID;

/**
 * Agents and versions (issue #36): list and create AgentDefinitions, publish AgentVersions from an AgentSpec
 * document (the same canonical YAML or JSON the API accepts), and build a release from a version.
 */
@Controller
public final class ConsoleAgentsController {
    /**
     * The AgentSpec a new version starts from; the author edits it before publishing. Its name is the definition's
     * (the API requires that), and its model names a gateway and model alias that this server must configure.
     */
    static final String SPEC_TEMPLATE = """
            apiVersion: dvarahq.com/v1alpha1
            kind: Agent
            metadata:
              name: %s
              version: 1.0.0
            spec:
              executor:
                type: spring-ai
              instructions:
                system: Answer in one line.
              models:
                # A gateway and model alias configured on this server.
                reasoning: {gateway: direct, model: coding-premium}
            """;

    static String specTemplate(String agentName) {
        return SPEC_TEMPLATE.formatted(agentName);
    }

    private final ConsoleApi api;

    public ConsoleAgentsController(ConsoleApi api) {
        this.api = Objects.requireNonNull(api, "api");
    }

    @GetMapping("/console/agents")
    public String agents(HttpServletRequest request, @RequestParam(name = "cursor", required = false) String cursor,
            Model model) {
        model.addAttribute("agents", api.agents(request, cursor));
        return "console/agents";
    }

    @PostMapping("/console/agents")
    public ResponseEntity<Void> create(HttpServletRequest request,
            @RequestParam(name = "name", required = false) String name,
            @RequestParam(name = "description", required = false) String description) {
        var created = api.createAgent(request, name, description == null || description.isBlank() ? null : description);
        return ConsoleSupport.redirect(request, "/console/agents/" + created.id());
    }

    @GetMapping("/console/agents/{id}")
    public String agent(HttpServletRequest request, @PathVariable UUID id,
            @RequestParam(name = "cursor", required = false) String cursor, Model model) {
        var agent = api.agent(request, id);
        model.addAttribute("agent", agent);
        model.addAttribute("versions", api.versions(request, id, cursor));
        model.addAttribute("specTemplate", specTemplate(agent.name()));
        return "console/agent";
    }

    @PostMapping("/console/agents/{id}/versions")
    public ResponseEntity<Void> publish(HttpServletRequest request, @PathVariable UUID id,
            @RequestParam(name = "document", required = false) String document) {
        api.publish(request, id, document);
        return ConsoleSupport.redirect(request, "/console/agents/" + id);
    }

    @PostMapping("/console/agent-versions/{versionId}/releases")
    public ResponseEntity<Void> release(HttpServletRequest request, @PathVariable UUID versionId,
            @RequestParam(name = "name", required = false) String name) {
        var release = api.createRelease(request, versionId, name);
        return ConsoleSupport.redirect(request, "/console/deployments?release=" + release.id());
    }
}
