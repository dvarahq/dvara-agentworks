package com.dvarahq.agentworks.capability;

import java.util.Objects;
import java.util.regex.Pattern;

/** An immutable, versioned tool identity. */
public record ToolId(String namespace, String name, String version) implements Comparable<ToolId> {
    private static final Pattern PART = Pattern.compile("^[a-z][a-z0-9.-]{0,63}$");
    private static final Pattern VERSION = Pattern.compile("^(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)(?:-[0-9A-Za-z.-]+)?$");

    public ToolId {
        namespace = part(namespace, "namespace");
        name = part(name, "name");
        Objects.requireNonNull(version, "version");
        if (!VERSION.matcher(version).matches()) throw new IllegalArgumentException("invalid tool version");
    }

    private static String part(String value, String field) {
        Objects.requireNonNull(value, field);
        if (!PART.matcher(value).matches()) throw new IllegalArgumentException("invalid tool " + field);
        return value;
    }

    public String value() { return namespace + ":" + name + ":" + version; }

    @Override public int compareTo(ToolId other) { return value().compareTo(other.value()); }
}
