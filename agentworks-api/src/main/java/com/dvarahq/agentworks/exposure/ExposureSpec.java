package com.dvarahq.agentworks.exposure;

import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/** Authored deployment exposure. Descriptors are derived from this plus the pinned release manifest. */
public record ExposureSpec(String id, ExposureType type, String channelType, ExposurePolicy policy,
        Map<String, String> presentation) {
    private static final Pattern ID = Pattern.compile("^[a-z0-9]([a-z0-9._-]*[a-z0-9])?$");
    public ExposureSpec {
        if (id == null || id.length() > 128 || !ID.matcher(id).matches()) throw new IllegalArgumentException("invalid exposure id");
        Objects.requireNonNull(type, "type");
        if (type == ExposureType.INTERACTION_CHANNEL && (channelType == null || !ID.matcher(channelType).matches())) {
            throw new IllegalArgumentException("interaction channel type is required");
        }
        if (type != ExposureType.INTERACTION_CHANNEL && channelType != null) {
            throw new IllegalArgumentException("channel type applies only to interaction-channel exposure");
        }
        Objects.requireNonNull(policy, "policy");
        presentation = Map.copyOf(presentation == null ? Map.of() : presentation);
        if (presentation.size() > 64) throw new IllegalArgumentException("too many presentation settings");
    }
}
