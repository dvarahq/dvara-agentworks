package com.dvarahq.agentworks.testing.fake;

import java.util.*;

/** Run-local key/value database fake. It has no connection, transaction manager, or live persistence path. */
public final class FakeDatabase {
    private final Map<String, Object> values = new LinkedHashMap<>();
    public synchronized FakeDatabase put(String key, Object value) {
        if (key == null || key.isBlank()) throw new IllegalArgumentException("key is required");
        values.put(key, Objects.requireNonNull(value)); return this;
    }
    public synchronized Optional<Object> get(String key) { return Optional.ofNullable(values.get(key)); }
    public synchronized Map<String, Object> snapshot() { return Map.copyOf(values); }
    public synchronized FakeDatabase fresh() { FakeDatabase copy = new FakeDatabase(); values.forEach(copy::put); return copy; }
}
