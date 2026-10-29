package com.dvarahq.agentworks.interaction;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

final class InteractionValues {
    private static final Pattern DIGEST = Pattern.compile("^sha256:[0-9a-f]{64}$");
    private static final Pattern ID = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._:/@+-]{0,255}$");
    private static final int MAX_VALUE_DEPTH = 16;
    private static final int MAX_VALUE_NODES = 4_096;
    private static final int MAX_STRING_LENGTH = 1_048_576;
    private InteractionValues() {}

    static String text(String value, String name, int max) {
        if (value == null || value.isBlank() || value.length() > max) {
            throw new IllegalArgumentException(name + " must be 1 to " + max + " characters");
        }
        return value;
    }

    static String id(String value, String name) {
        text(value, name, 256);
        if (!ID.matcher(value).matches()) throw new IllegalArgumentException("invalid " + name);
        return value;
    }

    static String digest(String value) {
        if (value == null || !DIGEST.matcher(value).matches()) throw new IllegalArgumentException("invalid sha256 digest");
        return value;
    }

    static URI uri(URI value, boolean remoteOnly) {
        Objects.requireNonNull(value, "uri");
        String scheme = value.getScheme();
        if (scheme == null || value.getUserInfo() != null || value.getFragment() != null
                || (remoteOnly && !("https".equalsIgnoreCase(scheme) || "artifact".equalsIgnoreCase(scheme)))) {
            throw new IllegalArgumentException("unsafe content URI");
        }
        return value;
    }

    static Map<String, Object> map(Map<String, Object> value, String name) {
        Objects.requireNonNull(value, name);
        return immutableMap(value, name, false, 0, new Counter());
    }

    static Map<String, Object> uiMap(Map<String, Object> value, String name) {
        Objects.requireNonNull(value, name);
        return immutableMap(value, name, true, 0, new Counter());
    }

    private static Map<String, Object> immutableMap(Map<?, ?> value, String name, boolean ui,
            int depth, Counter counter) {
        checkDepth(depth, name);
        if (value.size() > 256) throw new IllegalArgumentException(name + " has too many properties");
        Map<String, Object> copy = new LinkedHashMap<>();
        value.forEach((rawKey, rawValue) -> {
            if (!(rawKey instanceof String key) || key.isBlank() || key.length() > 256) {
                throw new IllegalArgumentException(name + " contains an invalid property name");
            }
            if (ui && executableKey(key)) {
                throw new IllegalArgumentException("executable UI properties are forbidden");
            }
            copy.put(key, immutableValue(rawValue, name + "." + key, ui, depth + 1, counter));
        });
        return Collections.unmodifiableMap(copy);
    }

    private static Object immutableValue(Object value, String name, boolean ui, int depth, Counter counter) {
        checkDepth(depth, name);
        if (++counter.nodes > MAX_VALUE_NODES) throw new IllegalArgumentException(name + " has too many values");
        if (value instanceof String string) {
            if (string.length() > MAX_STRING_LENGTH) throw new IllegalArgumentException(name + " is too long");
            return string;
        }
        if (value instanceof Boolean || value instanceof Byte || value instanceof Short || value instanceof Integer
                || value instanceof Long || value instanceof Float || value instanceof Double
                || value instanceof BigInteger || value instanceof BigDecimal) return value;
        if (value instanceof Map<?, ?> map) return immutableMap(map, name, ui, depth, counter);
        if (value instanceof List<?> list) {
            if (list.size() > 1_024) throw new IllegalArgumentException(name + " has too many elements");
            List<Object> copy = new ArrayList<>(list.size());
            for (int index = 0; index < list.size(); index++) {
                copy.add(immutableValue(list.get(index), name + "[" + index + "]", ui, depth + 1, counter));
            }
            return List.copyOf(copy);
        }
        throw new IllegalArgumentException(name + " must contain only JSON scalar, object, or array values");
    }

    private static void checkDepth(int depth, String name) {
        if (depth > MAX_VALUE_DEPTH) throw new IllegalArgumentException(name + " is nested too deeply");
    }

    private static boolean executableKey(String key) {
        String normalized = key.toLowerCase(Locale.ROOT);
        return normalized.equals("html") || normalized.equals("script") || normalized.startsWith("on");
    }

    private static final class Counter {
        private int nodes;
    }
}
