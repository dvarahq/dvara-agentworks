package com.dvarahq.agentworks.agent.springai;

import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;

/** A callback whose declared credential slot is supplied only by the runtime's JIT boundary. */
public interface CredentialedToolCallback extends ToolCallback {
    String call(String toolInput, ToolContext toolContext, char[] credentialMaterial);

    @Override
    default String call(String toolInput) {
        throw new IllegalStateException("credentialed tool callback requires JIT invocation");
    }

    @Override
    default String call(String toolInput, ToolContext toolContext) {
        throw new IllegalStateException("credentialed tool callback requires JIT invocation");
    }
}
