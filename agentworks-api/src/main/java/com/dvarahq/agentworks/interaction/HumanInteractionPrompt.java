package com.dvarahq.agentworks.interaction;

import com.dvarahq.agentworks.domain.security.CredentialRef;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Typed request body. Values are display/schema/reference metadata, never credential material. */
public sealed interface HumanInteractionPrompt {
    HumanInteractionKind kind();
    String title();
    String prompt();

    record Approval(String title, String prompt, String consequence) implements HumanInteractionPrompt {
        public Approval { common(title, prompt); requireText(consequence, "consequence"); }
        @Override public HumanInteractionKind kind() { return HumanInteractionKind.APPROVAL; }
    }

    record Question(String title, String prompt, String answerSchema) implements HumanInteractionPrompt {
        public Question { common(title, prompt); requireText(answerSchema, "answerSchema"); }
        @Override public HumanInteractionKind kind() { return HumanInteractionKind.QUESTION; }
    }

    record Selection(String title, String prompt, List<Option> options, int minimum, int maximum)
            implements HumanInteractionPrompt {
        public Selection {
            common(title, prompt);
            options = List.copyOf(Objects.requireNonNull(options, "options"));
            if (options.isEmpty() || options.stream().map(Option::id).distinct().count() != options.size()) {
                throw new IllegalArgumentException("selection options must be nonempty and unique");
            }
            if (minimum < 0 || maximum < 1 || minimum > maximum || maximum > options.size()) {
                throw new IllegalArgumentException("selection bounds are invalid");
            }
        }
        @Override public HumanInteractionKind kind() { return HumanInteractionKind.SELECTION; }
    }

    record Form(String title, String prompt, String responseSchema) implements HumanInteractionPrompt {
        public Form { common(title, prompt); requireText(responseSchema, "responseSchema"); }
        @Override public HumanInteractionKind kind() { return HumanInteractionKind.FORM; }
    }

    record Confirmation(String title, String prompt, String consequence) implements HumanInteractionPrompt {
        public Confirmation { common(title, prompt); requireText(consequence, "consequence"); }
        @Override public HumanInteractionKind kind() { return HumanInteractionKind.CONFIRMATION; }
    }

    record Authorization(String title, String prompt, CredentialRef credentialRef, Set<String> scopes,
            String policyId)
            implements HumanInteractionPrompt {
        public static final String DEFAULT_POLICY_ID = "core:delegated-authorization:1";

        public Authorization(String title, String prompt, CredentialRef credentialRef, Set<String> scopes) {
            this(title, prompt, credentialRef, scopes, DEFAULT_POLICY_ID);
        }

        public Authorization {
            common(title, prompt);
            Objects.requireNonNull(credentialRef, "credentialRef");
            scopes = Set.copyOf(Objects.requireNonNull(scopes, "scopes"));
            if (scopes.isEmpty() || scopes.stream().anyMatch(scope -> scope == null || scope.isBlank())) {
                throw new IllegalArgumentException("authorization scopes must be nonempty");
            }
            requireText(policyId, "authorization policyId");
        }
        @Override public HumanInteractionKind kind() { return HumanInteractionKind.AUTHORIZATION; }
        @Override public String toString() {
            return "Authorization[title=" + title + ", prompt=" + prompt
                    + ", credentialRef=<redacted>, scopes=" + scopes + ", policyId=" + policyId + "]";
        }
    }

    record Option(String id, String label) {
        public Option { requireText(id, "option id"); requireText(label, "option label"); }
    }

    private static void common(String title, String prompt) {
        requireText(title, "title");
        requireText(prompt, "prompt");
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " is required");
    }
}
