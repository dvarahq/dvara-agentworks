package com.dvarahq.agentworks.console.web;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.Objects;
import java.util.UUID;

/** Read-only logical-session and durable-inbox visibility over the public REST controllers. */
@Controller
public final class ConsoleSessionsController {
    private final ConsoleApi api;

    public ConsoleSessionsController(ConsoleApi api) {
        this.api = Objects.requireNonNull(api, "api");
    }

    @GetMapping("/console/sessions")
    public String sessions(HttpServletRequest request,
            @RequestParam(name = "cursor", required = false) String cursor, Model model) {
        model.addAttribute("sessions", api.sessions(request, cursor));
        return "console/sessions";
    }

    @GetMapping("/console/sessions/{id}")
    public String session(HttpServletRequest request, @PathVariable UUID id,
            @RequestParam(name = "status", required = false) String status,
            @RequestParam(name = "cursor", required = false) String cursor, Model model) {
        model.addAttribute("session", api.session(request, id));
        model.addAttribute("messages", api.inbox(request, id, status, cursor));
        model.addAttribute("statusFilter", status);
        return "console/session-inbox";
    }
}
