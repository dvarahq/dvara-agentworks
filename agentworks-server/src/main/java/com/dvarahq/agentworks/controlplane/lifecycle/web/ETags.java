package com.dvarahq.agentworks.controlplane.lifecycle.web;

import com.dvarahq.agentworks.controlplane.lifecycle.LifecycleConflictException;

/** Strong ETags carrying optimistic-concurrency versions or content digests. */
final class ETags {
    private ETags() {}

    static String of(Object value) {
        return "\"" + value + "\"";
    }

    static long version(String ifMatch) {
        String value = ifMatch.strip();
        if (value.startsWith("W/")) value = value.substring(2);
        value = value.replace("\"", "");
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException invalid) {
            throw new LifecycleConflictException("INVALID_PRECONDITION", "If-Match must carry a numeric version ETag");
        }
    }
}
