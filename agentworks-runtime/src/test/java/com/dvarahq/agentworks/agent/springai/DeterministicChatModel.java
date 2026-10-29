package com.dvarahq.agentworks.agent.springai;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

import java.util.List;

final class DeterministicChatModel implements ChatModel {

    @Override
    public ChatResponse call(Prompt prompt) {
        boolean toolResultPresent = prompt.getInstructions().stream()
                .anyMatch(message -> message.getMessageType() == MessageType.TOOL);
        if (toolResultPresent) {
            return response("tool-normalized:5");
        }
        if (prompt.getContents().contains("use tool")) {
            AssistantMessage message = AssistantMessage.builder()
                    .content("")
                    .toolCalls(List.of(new AssistantMessage.ToolCall(
                            "call-1", "function", "add", "{\"left\":2,\"right\":3}")))
                    .build();
            return new ChatResponse(List.of(new Generation(message,
                    ChatGenerationMetadata.builder().finishReason("tool_calls").build())));
        }
        return response("normalized:" + prompt.getContents());
    }

    @Override
    public Flux<ChatResponse> stream(Prompt prompt) {
        return Flux.just(response("alpha"), response("beta"), response("gamma"));
    }

    private static ChatResponse response(String content) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(content))));
    }
}
