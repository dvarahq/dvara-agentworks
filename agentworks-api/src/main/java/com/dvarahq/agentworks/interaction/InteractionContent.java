package com.dvarahq.agentworks.interaction;

/** Closed, provider-neutral content vocabulary used by channels and typed streams. */
public sealed interface InteractionContent permits TextContent, ImageContent, AudioContent, FileContent,
        StructuredContent, ToolRequestContent, ToolResultContent, ArtifactContent, UiContent, ReferenceContent {
    ContentType type();
    ContentMetadata metadata();
}
