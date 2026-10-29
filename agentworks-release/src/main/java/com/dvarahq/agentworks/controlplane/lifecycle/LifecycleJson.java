package com.dvarahq.agentworks.controlplane.lifecycle;

import com.fasterxml.jackson.annotation.JsonInclude;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

/** Deterministic JSON for stored manifests, policies, configurations, evidence, and idempotent results. */
public final class LifecycleJson {
    public static final JsonMapper MAPPER = JsonMapper.builder()
            .enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
            .changeDefaultPropertyInclusion(ignored -> JsonInclude.Value.construct(JsonInclude.Include.NON_NULL,
                    JsonInclude.Include.NON_NULL))
            .build();

    private LifecycleJson() {}

    public static String write(Object value) {
        return MAPPER.writeValueAsString(value);
    }

    public static <T> T read(String json, Class<T> type) {
        return MAPPER.readValue(json, type);
    }
}
