package com.dvarahq.agentworks.runplane.interaction.web;

import com.dvarahq.agentworks.api.web.ApiRequestException;
import com.dvarahq.agentworks.interaction.HumanInteractionKind;
import com.dvarahq.agentworks.interaction.HumanInteractionPrompt;
import com.dvarahq.agentworks.interaction.HumanInteractionRecord;
import com.dvarahq.agentworks.interaction.HumanInteractionResponse;
import org.springframework.http.HttpStatus;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** JSON resources for the credential-free human-interaction read and response contract. */
public final class HumanInteractionApiResources {
    private HumanInteractionApiResources() {}

    public record ResponseRequest(Boolean approved, String answer, List<String> optionIds, Map<String, String> fields,
            Boolean confirmed, Boolean granted) {
        HumanInteractionResponse value(HumanInteractionKind kind) {
            int supplied = (approved != null ? 1 : 0) + (answer != null ? 1 : 0) + (optionIds != null ? 1 : 0)
                    + (fields != null ? 1 : 0) + (confirmed != null ? 1 : 0) + (granted != null ? 1 : 0);
            if (supplied != 1) throw invalid();
            try {
                return switch (kind) {
                    case APPROVAL -> approved == null ? reject() : new HumanInteractionResponse.Approval(approved);
                    case QUESTION -> answer == null ? reject() : new HumanInteractionResponse.Answer(answer);
                    case SELECTION -> optionIds == null ? reject() : new HumanInteractionResponse.Selection(optionIds);
                    case FORM -> fields == null ? reject() : new HumanInteractionResponse.Form(fields);
                    case CONFIRMATION -> confirmed == null ? reject()
                            : new HumanInteractionResponse.Confirmation(confirmed);
                    case AUTHORIZATION -> granted == null ? reject()
                            : new HumanInteractionResponse.Authorization(granted);
                };
            } catch (IllegalArgumentException | NullPointerException invalid) {
                throw invalid();
            }
        }

        private static <T> T reject() {
            throw invalid();
        }

        private static ApiRequestException invalid() {
            return new ApiRequestException(HttpStatus.UNPROCESSABLE_CONTENT, "INVALID_RESPONSE",
                    "The response does not match the interaction request.");
        }
    }

    public record InteractionResource(UUID id, UUID runId, UUID releaseId, UUID deploymentId, UUID revisionId,
            UUID cohortId, long cohortTransitionVersion, String correlationId, HumanInteractionKind kind,
            PromptResource prompt, AssignmentResource assignment, String status, Instant createdAt, Instant deadline,
            OutcomeResource outcome) {
        static InteractionResource of(HumanInteractionRecord record) {
            var request = record.request();
            return new InteractionResource(request.id(), request.runId(), request.releaseId(), request.deploymentId(),
                    request.revisionId(), request.cohortId(), record.cohortTransitionVersion(),
                    request.correlationId(), request.prompt().kind(), PromptResource.of(request.prompt()),
                    new AssignmentResource(request.assignment().level().name(),
                            request.assignment().principalId().orElse(null)),
                    record.status().name(), request.createdAt(), request.deadline(),
                    record.status().terminal() ? new OutcomeResource(record.resolvedBy().orElseThrow(),
                            record.resolvedAt().orElseThrow(), record.response().map(ResponseResource::of).orElse(null))
                            : null);
        }
    }

    public record AssignmentResource(String level, UUID principalId) {}

    public record PromptResource(String title, String prompt, String consequence, String answerSchema,
            List<HumanInteractionPrompt.Option> options, Integer minimum, Integer maximum, String responseSchema,
            String credentialRef, Set<String> scopes, String policyId) {
        static PromptResource of(HumanInteractionPrompt prompt) {
            if (prompt instanceof HumanInteractionPrompt.Approval value) {
                return new PromptResource(value.title(), value.prompt(), value.consequence(), null, null, null, null,
                        null, null, null, null);
            }
            if (prompt instanceof HumanInteractionPrompt.Question value) {
                return new PromptResource(value.title(), value.prompt(), null, value.answerSchema(), null, null, null,
                        null, null, null, null);
            }
            if (prompt instanceof HumanInteractionPrompt.Selection value) {
                return new PromptResource(value.title(), value.prompt(), null, null, value.options(), value.minimum(),
                        value.maximum(), null, null, null, null);
            }
            if (prompt instanceof HumanInteractionPrompt.Form value) {
                return new PromptResource(value.title(), value.prompt(), null, null, null, null, null,
                        value.responseSchema(), null, null, null);
            }
            if (prompt instanceof HumanInteractionPrompt.Confirmation value) {
                return new PromptResource(value.title(), value.prompt(), value.consequence(), null, null, null, null,
                        null, null, null, null);
            }
            HumanInteractionPrompt.Authorization value = (HumanInteractionPrompt.Authorization) prompt;
            return new PromptResource(value.title(), value.prompt(), null, null, null, null, null, null,
                    value.credentialRef().id(), value.scopes(), value.policyId());
        }
    }

    public record OutcomeResource(String resolvedBy, Instant resolvedAt, ResponseResource response) {}

    /** Question and form values are deliberately represented only as redaction markers. */
    public record ResponseResource(Boolean approved, Boolean answerRedacted, List<String> optionIds,
            Boolean formRedacted, Boolean confirmed, Boolean granted) {
        static ResponseResource of(HumanInteractionResponse response) {
            return switch (response) {
                case HumanInteractionResponse.Approval value ->
                        new ResponseResource(value.approved(), null, null, null, null, null);
                case HumanInteractionResponse.Answer ignored ->
                        new ResponseResource(null, true, null, null, null, null);
                case HumanInteractionResponse.Selection value ->
                        new ResponseResource(null, null, value.optionIds(), null, null, null);
                case HumanInteractionResponse.Form ignored ->
                        new ResponseResource(null, null, null, true, null, null);
                case HumanInteractionResponse.Confirmation value ->
                        new ResponseResource(null, null, null, null, value.confirmed(), null);
                case HumanInteractionResponse.Authorization value ->
                        new ResponseResource(null, null, null, null, null, value.granted());
            };
        }
    }
}
