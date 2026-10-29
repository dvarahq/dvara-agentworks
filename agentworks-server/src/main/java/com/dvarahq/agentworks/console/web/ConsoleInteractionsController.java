package com.dvarahq.agentworks.console.web;

import com.dvarahq.agentworks.interaction.HumanInteractionKind;
import com.dvarahq.agentworks.runplane.interaction.web.HumanInteractionApiResources.ResponseRequest;
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

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Pending and resolved human interactions over the same tenant-scoped REST controller as API clients. */
@Controller
public final class ConsoleInteractionsController {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private final ConsoleApi api;

    public ConsoleInteractionsController(ConsoleApi api) {
        this.api = Objects.requireNonNull(api, "api");
    }

    @GetMapping("/console/interactions")
    public String interactions(HttpServletRequest request,
            @RequestParam(name = "status", required = false) String status,
            @RequestParam(name = "run", required = false) String run,
            @RequestParam(name = "cursor", required = false) String cursor, Model model) {
        UUID runId = ConsoleSupport.uuid(run, "run");
        String filter = status == null || status.isBlank() ? null : status;
        model.addAttribute("interactions", api.interactions(request, filter, runId, cursor));
        model.addAttribute("statusFilter", filter);
        model.addAttribute("runFilter", runId);
        return "console/interactions";
    }

    @GetMapping("/console/interactions/{id}")
    public String interaction(HttpServletRequest request, @PathVariable UUID id, Model model) {
        model.addAttribute("interaction", api.interaction(request, id));
        return "console/interaction";
    }

    @PostMapping("/console/interactions/{id}/response")
    public ResponseEntity<Void> respond(HttpServletRequest request, @PathVariable UUID id,
            @RequestParam(name = "approved", required = false) Boolean approved,
            @RequestParam(name = "answer", required = false) String answer,
            @RequestParam(name = "optionIds", required = false) List<String> optionIds,
            @RequestParam(name = "fields", required = false) String fields,
            @RequestParam(name = "confirmed", required = false) Boolean confirmed,
            @RequestParam(name = "granted", required = false) Boolean granted) {
        // An HTML checkbox group sends no parameter when nothing is selected; that is a real empty selection and
        // can be valid when the prompt's minimum is zero.
        if (optionIds == null && api.interaction(request, id).kind() == HumanInteractionKind.SELECTION) {
            optionIds = List.of();
        }
        Map<String, String> form = fields == null ? null : fields(fields);
        api.respond(request, id, new ResponseRequest(approved, answer, optionIds, form, confirmed, granted));
        return ConsoleSupport.redirect(request, "/console/interactions/" + id);
    }

    private static Map<String, String> fields(String source) {
        try {
            JsonNode document = JSON.readTree(source);
            if (!document.isObject()) throw invalidFields();
            Map<String, String> fields = new LinkedHashMap<>();
            for (Map.Entry<String, JsonNode> field : document.properties()) {
                if (!field.getValue().isString()) throw invalidFields();
                fields.put(field.getKey(), field.getValue().asString());
            }
            return fields;
        } catch (ConsoleProblemException invalid) {
            throw invalid;
        } catch (RuntimeException unreadable) {
            throw invalidFields();
        }
    }

    private static ConsoleProblemException invalidFields() {
        return ConsoleSupport.invalid("INVALID_RESPONSE", "Form fields must be a JSON object of string values.");
    }
}
