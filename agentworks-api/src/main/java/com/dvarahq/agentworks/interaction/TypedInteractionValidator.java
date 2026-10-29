package com.dvarahq.agentworks.interaction;

import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Applies common size, classification, redaction, and destination rules before a channel sees content. */
public final class TypedInteractionValidator {
    private final long maxItemBytes;
    private final long maxEnvelopeBytes;

    public TypedInteractionValidator(long maxItemBytes, long maxEnvelopeBytes) {
        if (maxItemBytes < 1 || maxEnvelopeBytes < maxItemBytes) throw new IllegalArgumentException("invalid content limits");
        this.maxItemBytes = maxItemBytes;
        this.maxEnvelopeBytes = maxEnvelopeBytes;
    }

    public List<InteractionContent> validate(List<InteractionContent> content, Set<String> classifications,
            boolean toolContentAllowed) {
        Objects.requireNonNull(classifications, "classifications");
        List<InteractionContent> copy = List.copyOf(Objects.requireNonNull(content, "content"));
        if (copy.isEmpty() || copy.size() > 64) throw new IllegalArgumentException("content item count is out of range");
        long total = 0;
        for (InteractionContent item : copy) {
            Objects.requireNonNull(item, "content item");
            ContentMetadata metadata = item.metadata();
            if (metadata.sizeBytes() > maxItemBytes || maxEnvelopeBytes - total < metadata.sizeBytes()) {
                throw new IllegalArgumentException("content exceeds channel size policy");
            }
            total += metadata.sizeBytes();
            if (!classifications.contains(metadata.classification())) {
                throw new IllegalArgumentException("content classification is not permitted for this exposure");
            }
            if (!toolContentAllowed && (item.type() == ContentType.TOOL_REQUEST || item.type() == ContentType.TOOL_RESULT)) {
                throw new IllegalArgumentException("tool content is not permitted for this exposure");
            }
        }
        return copy;
    }

    /** Unknown future content is rendered as inert text, never interpreted as executable UI or a tool request. */
    public static TextContent degradeUnknown(String unknownType) {
        String type = unknownType == null || unknownType.isBlank() ? "unknown" : unknownType;
        return new TextContent("Unsupported content type: " + type,
                new ContentMetadata("PUBLIC", "SYSTEM", true, type.length()));
    }
}
