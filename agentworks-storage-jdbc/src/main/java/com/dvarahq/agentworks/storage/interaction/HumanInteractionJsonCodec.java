package com.dvarahq.agentworks.storage.interaction;

import com.dvarahq.agentworks.domain.security.CredentialRef;
import com.dvarahq.agentworks.interaction.HumanInteractionKind;
import com.dvarahq.agentworks.interaction.HumanInteractionPrompt;
import com.dvarahq.agentworks.interaction.HumanInteractionResponse;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/** Minimal closed codec for the six protocol variants; no polymorphic type names or credential material are accepted. */
final class HumanInteractionJsonCodec {
    private final JsonMapper json = JsonMapper.builder().build();

    String prompt(HumanInteractionPrompt prompt) {
        return prompt(prompt, true);
    }

    String prompt(HumanInteractionPrompt prompt, boolean includeAuthorizationPolicy) {
        Map<String, Object> value = common(prompt.title(), prompt.prompt());
        switch (prompt) {
            case HumanInteractionPrompt.Approval p -> value.put("consequence", p.consequence());
            case HumanInteractionPrompt.Question p -> value.put("answerSchema", p.answerSchema());
            case HumanInteractionPrompt.Selection p -> {
                value.put("options", p.options().stream()
                        .map(option -> Map.of("id", option.id(), "label", option.label())).toList());
                value.put("minimum", p.minimum());
                value.put("maximum", p.maximum());
            }
            case HumanInteractionPrompt.Form p -> value.put("responseSchema", p.responseSchema());
            case HumanInteractionPrompt.Confirmation p -> value.put("consequence", p.consequence());
            case HumanInteractionPrompt.Authorization p -> {
                value.put("credentialRef", p.credentialRef().id());
                value.put("scopes", new TreeSet<>(p.scopes()));
                if (includeAuthorizationPolicy) value.put("policyId", p.policyId());
            }
        }
        return json.writeValueAsString(value);
    }

    HumanInteractionPrompt prompt(HumanInteractionKind kind, String payload) {
        JsonNode value = json.readTree(payload);
        String title = text(value, "title");
        String prompt = text(value, "prompt");
        return switch (kind) {
            case APPROVAL -> new HumanInteractionPrompt.Approval(title, prompt, text(value, "consequence"));
            case QUESTION -> new HumanInteractionPrompt.Question(title, prompt, text(value, "answerSchema"));
            case SELECTION -> {
                List<HumanInteractionPrompt.Option> options = new ArrayList<>();
                value.get("options").forEach(option -> options.add(
                        new HumanInteractionPrompt.Option(text(option, "id"), text(option, "label"))));
                yield new HumanInteractionPrompt.Selection(title, prompt, options,
                        value.get("minimum").asInt(), value.get("maximum").asInt());
            }
            case FORM -> new HumanInteractionPrompt.Form(title, prompt, text(value, "responseSchema"));
            case CONFIRMATION -> new HumanInteractionPrompt.Confirmation(title, prompt, text(value, "consequence"));
            case AUTHORIZATION -> {
                Set<String> scopes = new TreeSet<>();
                value.get("scopes").forEach(scope -> scopes.add(scope.asString()));
                yield new HumanInteractionPrompt.Authorization(title, prompt,
                        new CredentialRef(text(value, "credentialRef")), scopes,
                        value.has("policyId") ? text(value, "policyId")
                                : HumanInteractionPrompt.Authorization.DEFAULT_POLICY_ID);
            }
        };
    }

    String response(HumanInteractionResponse response) {
        Object value = switch (response) {
            case HumanInteractionResponse.Approval r -> Map.of("approved", r.approved());
            case HumanInteractionResponse.Answer r -> Map.of("value", r.value());
            case HumanInteractionResponse.Selection r -> Map.of("optionIds", r.optionIds());
            case HumanInteractionResponse.Form r -> Map.of("fields", r.fields());
            case HumanInteractionResponse.Confirmation r -> Map.of("confirmed", r.confirmed());
            case HumanInteractionResponse.Authorization r -> Map.of("granted", r.granted());
        };
        return json.writeValueAsString(value);
    }

    HumanInteractionResponse response(HumanInteractionKind kind, String payload) {
        JsonNode value = json.readTree(payload);
        return switch (kind) {
            case APPROVAL -> new HumanInteractionResponse.Approval(value.get("approved").asBoolean());
            case QUESTION -> new HumanInteractionResponse.Answer(text(value, "value"));
            case SELECTION -> {
                List<String> ids = new ArrayList<>();
                value.get("optionIds").forEach(id -> ids.add(id.asString()));
                yield new HumanInteractionResponse.Selection(ids);
            }
            case FORM -> {
                Map<String, String> fields = new LinkedHashMap<>();
                value.get("fields").properties().forEach(entry -> fields.put(entry.getKey(), entry.getValue().asString()));
                yield new HumanInteractionResponse.Form(fields);
            }
            case CONFIRMATION -> new HumanInteractionResponse.Confirmation(value.get("confirmed").asBoolean());
            case AUTHORIZATION -> new HumanInteractionResponse.Authorization(value.get("granted").asBoolean());
        };
    }

    private static Map<String, Object> common(String title, String prompt) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("title", title);
        value.put("prompt", prompt);
        return value;
    }

    private static String text(JsonNode value, String field) {
        JsonNode found = value.get(field);
        if (found == null || !found.isString()) throw new IllegalArgumentException("invalid interaction JSON");
        return found.asString();
    }
}
