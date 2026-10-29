package com.dvarahq.agentworks.storage.session;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

import java.util.Map;

/** Canonical bounded-payload JSON used by inbox and durable-operation persistence. */
public final class DurablePayloadCodec {
    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {};
    private final JsonMapper mapper = JsonMapper.builder().enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS).build();

    public String write(Map<String, Object> value) {
        return mapper.writeValueAsString(value);
    }

    public Map<String, Object> read(String value) {
        return mapper.readValue(value, MAP);
    }
}
