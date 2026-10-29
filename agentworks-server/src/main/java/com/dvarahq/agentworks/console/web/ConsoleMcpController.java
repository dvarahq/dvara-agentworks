package com.dvarahq.agentworks.console.web;

import com.dvarahq.agentworks.mcp.web.McpConnectionController;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

import java.util.Objects;

@Controller
public final class ConsoleMcpController {
    private final McpConnectionController connections;
    public ConsoleMcpController(McpConnectionController connections) { this.connections = Objects.requireNonNull(connections); }

    @GetMapping("/console/mcp")
    public String connections(HttpServletRequest request, Model model) {
        model.addAttribute("connections", connections.connections(request).items());
        return "console/mcp";
    }
}
