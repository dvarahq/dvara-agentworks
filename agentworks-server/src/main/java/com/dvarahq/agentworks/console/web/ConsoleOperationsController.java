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

/** Durable-operation visibility and cancellation over the public REST controller. */
@Controller
public final class ConsoleOperationsController {
    private final ConsoleApi api;

    public ConsoleOperationsController(ConsoleApi api) {
        this.api = Objects.requireNonNull(api, "api");
    }

    @GetMapping("/console/operations")
    public String operations(HttpServletRequest request,
            @RequestParam(name = "session", required = false) String session,
            @RequestParam(name = "status", required = false) String status,
            @RequestParam(name = "cursor", required = false) String cursor, Model model) {
        UUID sessionId = ConsoleSupport.uuid(session, "session");
        model.addAttribute("operations", api.operations(request, sessionId, status, cursor));
        model.addAttribute("sessionFilter", sessionId);
        model.addAttribute("statusFilter", status);
        return "console/operations";
    }

    @GetMapping("/console/operations/{id}")
    public String operation(HttpServletRequest request, @PathVariable UUID id, Model model) {
        model.addAttribute("operation", api.operation(request, id));
        return "console/operation";
    }

    @PostMapping("/console/operations/{id}/cancel")
    public ResponseEntity<Void> cancel(HttpServletRequest request, @PathVariable UUID id) {
        api.cancelOperation(request, id);
        return ConsoleSupport.redirect(request, "/console/operations/" + id);
    }
}
