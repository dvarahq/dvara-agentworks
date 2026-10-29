package com.dvarahq.agentworks.runplane.execution;

import com.dvarahq.agentworks.domain.run.RunEventType;
import tools.jackson.databind.node.ObjectNode;

import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.UnaryOperator;

/**
 * Event payload schema versions and upcasters (ADR-003). Every event type is written at its current version. A
 * stored event at an older version is read only through a registered chain of upcasters that lifts it one version
 * at a time; a stored version newer than the current one, or an older one without an upcaster, fails safely with
 * {@code UNSUPPORTED_EVENT_VERSION} rather than being guessed at. Support for a version must be kept for as long as
 * a retained run carries it.
 */
public final class EventSchemas {
    /** Version 1 of every payload shape: the versions issue #32 writes. */
    public static final EventSchemas CURRENT = new Builder().build();

    private final Map<RunEventType, Integer> versions;
    private final Map<String, UnaryOperator<ObjectNode>> upcasters;

    private EventSchemas(Map<RunEventType, Integer> versions, Map<String, UnaryOperator<ObjectNode>> upcasters) {
        this.versions = Map.copyOf(versions);
        this.upcasters = Map.copyOf(upcasters);
    }

    public int currentVersion(RunEventType type) {
        return versions.getOrDefault(type, 1);
    }

    /** Lifts {@code payload} from {@code version} to the current version of {@code type}. */
    ObjectNode upcast(RunEventType type, int version, ObjectNode payload) {
        int current = currentVersion(type);
        if (version > current) {
            throw new RunEngineException("UNSUPPORTED_EVENT_VERSION",
                    type + " version " + version + " is newer than this node supports (" + current + ")");
        }
        ObjectNode lifted = payload;
        for (int from = version; from < current; from++) {
            UnaryOperator<ObjectNode> upcaster = upcasters.get(key(type, from));
            if (upcaster == null) {
                throw new RunEngineException("UNSUPPORTED_EVENT_VERSION", type + " version " + from + " has no upcaster");
            }
            lifted = Objects.requireNonNull(upcaster.apply(lifted.deepCopy()), "upcaster result");
        }
        return lifted;
    }

    public static Builder builder() {
        return new Builder();
    }

    private static String key(RunEventType type, int from) {
        return type.name() + "@" + from;
    }

    public static final class Builder {
        private final Map<RunEventType, Integer> versions = new EnumMap<>(RunEventType.class);
        private final Map<String, UnaryOperator<ObjectNode>> upcasters = new HashMap<>();

        public Builder current(RunEventType type, int version) {
            if (version < 1) throw new IllegalArgumentException("versions start at 1");
            versions.put(type, version);
            return this;
        }

        /** Registers the upcaster from {@code fromVersion} to {@code fromVersion + 1}. */
        public Builder upcaster(RunEventType type, int fromVersion, UnaryOperator<ObjectNode> upcaster) {
            upcasters.put(key(type, fromVersion), Objects.requireNonNull(upcaster, "upcaster"));
            return this;
        }

        public EventSchemas build() {
            return new EventSchemas(versions, upcasters);
        }
    }
}
