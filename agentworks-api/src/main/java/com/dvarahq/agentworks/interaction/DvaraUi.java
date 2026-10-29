package com.dvarahq.agentworks.interaction;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Versioned, declarative UI data. It contains no executable script, markup, or arbitrary component names. */
public record DvaraUi(String schemaVersion, Component component) {
    public enum Type { TABLE, METRIC, FORM, CONFIRMATION, PROGRESS, CODE, DIFF, CHART, ARTIFACT, ACTIONS, LINKS }
    public record Action(String id, String label, URI target, String method) {
        public Action {
            id = InteractionValues.id(id, "action id");
            label = InteractionValues.text(label, "action label", 256);
            target = InteractionValues.uri(target, true);
            if (!Set.of("GET", "POST").contains(method)) throw new IllegalArgumentException("unsafe UI action method");
        }
    }
    public record Component(Type type, String id, Map<String, Object> properties, List<Action> actions) {
        public Component {
            Objects.requireNonNull(type, "type");
            id = InteractionValues.id(id, "component id");
            properties = InteractionValues.uiMap(properties, "properties");
            actions = List.copyOf(actions == null ? List.of() : actions);
            if (actions.size() > 64) throw new IllegalArgumentException("too many UI actions");
        }
    }
    public DvaraUi {
        if (!"dvaraui/v1".equals(schemaVersion)) throw new IllegalArgumentException("unsupported DvaraUI version");
        Objects.requireNonNull(component, "component");
    }
}
