package com.dvarahq.agentworks.gateway;

import java.util.List;
import java.util.Objects;

/**
 * One provider-neutral conversation message. {@link #toString()} never renders content, so logging a request cannot
 * leak prompt text.
 *
 * @param toolCalls tool calls an earlier assistant turn made (assistant messages only)
 * @param toolCallId the call a tool message answers (tool messages only)
 */
public record ModelMessage(Role role, String content, List<ToolCall> toolCalls, String toolCallId) {

    public enum Role { SYSTEM, USER, ASSISTANT, TOOL }

    public ModelMessage {
        Objects.requireNonNull(role, "role");
        toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
        if (role != Role.ASSISTANT && !toolCalls.isEmpty()) {
            throw new IllegalArgumentException("only assistant messages carry tool calls");
        }
        if ((role == Role.TOOL) != (toolCallId != null)) {
            throw new IllegalArgumentException("exactly tool messages carry a toolCallId");
        }
        if (content == null && !(role == Role.ASSISTANT && !toolCalls.isEmpty())) {
            throw new IllegalArgumentException("content is required unless an assistant message carries tool calls");
        }
    }

    public static ModelMessage system(String content) {
        return new ModelMessage(Role.SYSTEM, content, List.of(), null);
    }

    public static ModelMessage user(String content) {
        return new ModelMessage(Role.USER, content, List.of(), null);
    }

    public static ModelMessage assistant(String content, List<ToolCall> toolCalls) {
        return new ModelMessage(Role.ASSISTANT, content, toolCalls, null);
    }

    public static ModelMessage tool(String toolCallId, String content) {
        return new ModelMessage(Role.TOOL, content, List.of(), toolCallId);
    }

    @Override
    public String toString() {
        return "ModelMessage[role=" + role + ", content=<" + (content == null ? 0 : content.length())
                + " chars redacted>, toolCalls=" + toolCalls.size() + "]";
    }
}
