package com.dvarahq.agentworks.interaction;

import com.dvarahq.agentworks.domain.security.CredentialRef;
import com.dvarahq.agentworks.domain.security.InvocationIdentity;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HumanInteractionContractTest {
    private static final UUID WORKSPACE = UUID.randomUUID();
    private static final UUID USER = UUID.randomUUID();
    private static final UUID SESSION = UUID.randomUUID();
    private static final UUID SUBJECT = UUID.randomUUID();
    private static final InvocationIdentity IDENTITY = new InvocationIdentity(UUID.randomUUID(), WORKSPACE, USER,
            SESSION, SUBJECT);
    private static final Instant NOW = Instant.parse("2026-10-08T14:00:00Z");

    @Test
    void allSixKindsAcceptOnlyTheirOwnResponseType() {
        List<HumanInteractionPrompt> prompts = List.of(
                new HumanInteractionPrompt.Approval("Deploy", "Proceed?", "Changes production"),
                new HumanInteractionPrompt.Question("Name", "What name?", "{\"type\":\"string\"}"),
                new HumanInteractionPrompt.Selection("Region", "Choose", List.of(
                        new HumanInteractionPrompt.Option("us", "US"),
                        new HumanInteractionPrompt.Option("eu", "EU")), 1, 1),
                new HumanInteractionPrompt.Form("Contact", "Complete", "{\"type\":\"object\"}"),
                new HumanInteractionPrompt.Confirmation("Delete", "Confirm", "Cannot be undone"),
                new HumanInteractionPrompt.Authorization("Calendar", "Authorize access",
                        new CredentialRef("env://calendar-token"), Set.of("calendar.read")));
        List<HumanInteractionResponse> responses = List.of(new HumanInteractionResponse.Approval(true),
                new HumanInteractionResponse.Answer("Agent One"),
                new HumanInteractionResponse.Selection(List.of("us")),
                new HumanInteractionResponse.Form(Map.of("email", "person@example.test")),
                new HumanInteractionResponse.Confirmation(true), new HumanInteractionResponse.Authorization(true));

        for (int prompt = 0; prompt < prompts.size(); prompt++) {
            HumanInteractionRequest request = request(prompts.get(prompt));
            for (int response = 0; response < responses.size(); response++) {
                assertThat(request.accepts(responses.get(response))).isEqualTo(prompt == response);
            }
        }
    }

    @Test
    void responderAssignmentsAreExactAndWorkspaceBound() {
        assertThat(HumanInteractionAssignment.workspace(WORKSPACE).permits(IDENTITY)).isTrue();
        assertThat(HumanInteractionAssignment.user(WORKSPACE, USER).permits(IDENTITY)).isTrue();
        assertThat(HumanInteractionAssignment.session(WORKSPACE, SESSION).permits(IDENTITY)).isTrue();
        assertThat(HumanInteractionAssignment.delegatedSubject(WORKSPACE, SUBJECT).permits(IDENTITY)).isTrue();

        assertThat(HumanInteractionAssignment.workspace(UUID.randomUUID()).permits(IDENTITY)).isFalse();
        assertThat(HumanInteractionAssignment.user(WORKSPACE, UUID.randomUUID()).permits(IDENTITY)).isFalse();
        assertThat(HumanInteractionAssignment.session(WORKSPACE, UUID.randomUUID()).permits(IDENTITY)).isFalse();
        assertThat(HumanInteractionAssignment.delegatedSubject(WORKSPACE, UUID.randomUUID()).permits(IDENTITY))
                .isFalse();
    }

    @Test
    void requestPinsDeadlineAndIdentifiersFailClosed() {
        HumanInteractionPrompt prompt = new HumanInteractionPrompt.Confirmation("Confirm", "Continue?", "May charge");
        assertThatThrownBy(() -> new HumanInteractionRequest(UUID.randomUUID(), WORKSPACE, UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "bad key", "key", prompt,
                HumanInteractionAssignment.workspace(WORKSPACE), NOW, NOW.plusSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new HumanInteractionRequest(UUID.randomUUID(), WORKSPACE, UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "correlation", "key",
                prompt, HumanInteractionAssignment.workspace(UUID.randomUUID()), NOW, NOW.plusSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new HumanInteractionRequest(UUID.randomUUID(), WORKSPACE, UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "correlation", "key",
                prompt, HumanInteractionAssignment.workspace(WORKSPACE), NOW, NOW))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void terminalOutcomesNeverReopenAndSensitiveDiagnosticsAreRedacted() {
        assertThat(HumanInteractionStatus.OPEN.mayTransitionTo(HumanInteractionStatus.RESPONDED)).isTrue();
        assertThat(HumanInteractionStatus.OPEN.mayTransitionTo(HumanInteractionStatus.EXPIRED)).isTrue();
        assertThat(HumanInteractionStatus.RESPONDED.mayTransitionTo(HumanInteractionStatus.OPEN)).isFalse();
        assertThat(HumanInteractionStatus.CANCELLED.mayTransitionTo(HumanInteractionStatus.RESPONDED)).isFalse();

        String secret = "interaction-secret-canary-781f";
        HumanInteractionResponse.Form form = new HumanInteractionResponse.Form(Map.of("token", secret));
        HumanInteractionPrompt.Authorization authorization = new HumanInteractionPrompt.Authorization("Access",
                "Authorize", new CredentialRef(secret), Set.of("read"));
        assertThat(form.toString() + authorization).doesNotContain(secret);
        assertThat(authorization.policyId())
                .isEqualTo(HumanInteractionPrompt.Authorization.DEFAULT_POLICY_ID);
    }

    @Test
    void promptAndResponseShapesRejectAmbiguousValues() {
        assertThatThrownBy(() -> new HumanInteractionPrompt.Selection("Select", "Choose",
                List.of(new HumanInteractionPrompt.Option("a", "A"),
                        new HumanInteractionPrompt.Option("a", "Another label")), 1, 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new HumanInteractionResponse.Selection(List.of("a", "a")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new HumanInteractionPrompt.Authorization("Access", "Authorize",
                new CredentialRef("env://token"), Set.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new HumanInteractionPrompt.Authorization("Access", "Authorize",
                new CredentialRef("env://token"), Set.of("read"), " "))
                .isInstanceOf(IllegalArgumentException.class);

        HumanInteractionRequest selection = request(new HumanInteractionPrompt.Selection("Select", "Choose",
                List.of(new HumanInteractionPrompt.Option("a", "A"),
                        new HumanInteractionPrompt.Option("b", "B")), 1, 1));
        assertThat(selection.accepts(new HumanInteractionResponse.Selection(List.of()))).isFalse();
        assertThat(selection.accepts(new HumanInteractionResponse.Selection(List.of("outside")))).isFalse();
        assertThat(selection.accepts(new HumanInteractionResponse.Selection(List.of("a", "b")))).isFalse();
        assertThat(selection.accepts(new HumanInteractionResponse.Selection(List.of("b")))).isTrue();
    }

    private static HumanInteractionRequest request(HumanInteractionPrompt prompt) {
        return new HumanInteractionRequest(UUID.randomUUID(), WORKSPACE, UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "corr-1", "idem-1", prompt,
                HumanInteractionAssignment.user(WORKSPACE, USER), NOW, NOW.plusSeconds(300));
    }
}
