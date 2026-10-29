package com.dvarahq.agentworks.agent.springai;

import org.springframework.ai.tool.ToolCallback;

/** Binds an immutable adapter to one continuation. Binding must not allocate or perform effects. */
public interface RunBoundToolCallback extends ToolCallback {
    ToolCallback bind(ToolRunScope scope);
}
