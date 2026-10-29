package com.dvarahq.agentworks.capability;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Exact capability evidence captured by an immutable release manifest. */
public record PinnedCapabilityPack(String id, String version, List<PinnedTool> tools) {
    public PinnedCapabilityPack {
        new CapabilityPack(id, version, CapabilityPack.State.ACTIVE, List.of());
        tools = List.copyOf(Objects.requireNonNull(tools, "tools"));
        if (tools.stream().map(PinnedTool::id).distinct().count() != tools.size()) {
            throw new IllegalArgumentException("duplicate pinned tool");
        }
    }

    public String identity() { return id + ":" + version; }

    public record PinnedTool(ToolId id, String schemaHash, Optional<ToolEffect> effect) {
        public PinnedTool(ToolId id, String schemaHash) {
            this(id, schemaHash, Optional.empty());
        }

        public PinnedTool(ToolId id, String schemaHash, ToolEffect effect) {
            this(id, schemaHash, Optional.of(Objects.requireNonNull(effect, "effect")));
        }

        public PinnedTool {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(schemaHash, "schemaHash");
            if (!schemaHash.matches("^sha256:[0-9a-f]{64}$")) {
                throw new IllegalArgumentException("invalid pinned schema hash");
            }
            effect = Objects.requireNonNull(effect, "effect");
        }
    }
}
