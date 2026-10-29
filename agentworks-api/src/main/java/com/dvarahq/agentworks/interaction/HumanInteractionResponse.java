package com.dvarahq.agentworks.interaction;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Typed response body. Form values are deliberately absent from diagnostic strings. */
public sealed interface HumanInteractionResponse {
    HumanInteractionKind kind();

    record Approval(boolean approved) implements HumanInteractionResponse {
        @Override public HumanInteractionKind kind() { return HumanInteractionKind.APPROVAL; }
    }

    record Answer(String value) implements HumanInteractionResponse {
        public Answer { Objects.requireNonNull(value, "value"); }
        @Override public HumanInteractionKind kind() { return HumanInteractionKind.QUESTION; }
    }

    record Selection(List<String> optionIds) implements HumanInteractionResponse {
        public Selection {
            optionIds = List.copyOf(Objects.requireNonNull(optionIds, "optionIds"));
            if (optionIds.stream().anyMatch(id -> id == null || id.isBlank())
                    || optionIds.stream().distinct().count() != optionIds.size()) {
                throw new IllegalArgumentException("selected option ids must be unique nonblank values");
            }
        }
        @Override public HumanInteractionKind kind() { return HumanInteractionKind.SELECTION; }
    }

    record Form(Map<String, String> fields) implements HumanInteractionResponse {
        public Form {
            fields = Map.copyOf(Objects.requireNonNull(fields, "fields"));
            if (fields.keySet().stream().anyMatch(key -> key == null || key.isBlank())) {
                throw new IllegalArgumentException("form field names must be nonblank");
            }
        }
        @Override public HumanInteractionKind kind() { return HumanInteractionKind.FORM; }
        @Override public String toString() { return "Form[fields=<redacted>]"; }
    }

    record Confirmation(boolean confirmed) implements HumanInteractionResponse {
        @Override public HumanInteractionKind kind() { return HumanInteractionKind.CONFIRMATION; }
    }

    record Authorization(boolean granted) implements HumanInteractionResponse {
        @Override public HumanInteractionKind kind() { return HumanInteractionKind.AUTHORIZATION; }
    }
}
