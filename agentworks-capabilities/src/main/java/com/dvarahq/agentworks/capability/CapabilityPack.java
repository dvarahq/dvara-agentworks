package com.dvarahq.agentworks.capability;

import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/** A versioned pack pins exact tool versions; other capability sources join in their owning issues. */
public record CapabilityPack(String id, String version, State state, List<ToolId> tools) {
    private static final Pattern PART = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$");
    public enum State { AVAILABLE, ACTIVE, DISABLED, DENIED }

    public CapabilityPack {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(version, "version");
        Objects.requireNonNull(state, "state");
        tools = List.copyOf(Objects.requireNonNull(tools, "tools"));
        if (!PART.matcher(id).matches() || !PART.matcher(version).matches()
                || tools.stream().distinct().count() != tools.size()) {
            throw new IllegalArgumentException("invalid capability pack");
        }
    }

    public String identity() { return id + ":" + version; }
}
