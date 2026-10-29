package com.dvarahq.agentworks.agent.springai;

/** Lets explicit adapter registration verify the same offline, bounded schema contract used by the executor. */
public final class ToolSchemaContract {
    private ToolSchemaContract() {}

    public static void validate(String inputSchema) {
        ToolInputSchema.compile(inputSchema);
    }
}
