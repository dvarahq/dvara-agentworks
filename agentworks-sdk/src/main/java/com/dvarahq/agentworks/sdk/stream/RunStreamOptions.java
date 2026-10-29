package com.dvarahq.agentworks.sdk.stream;

import java.time.Duration;
import java.util.Objects;
import java.util.regex.Pattern;

/** Bounded reconnect and ownership settings for a run event stream. */
public record RunStreamOptions(String clientId, long afterSequence, Duration reconnectDelay, int maxReconnects) {
    private static final Pattern CLIENT = Pattern.compile("[A-Za-z0-9-]{1,64}");

    public RunStreamOptions {
        if (clientId != null && !CLIENT.matcher(clientId).matches()) throw new IllegalArgumentException("clientId");
        if (afterSequence < 0) throw new IllegalArgumentException("afterSequence");
        Objects.requireNonNull(reconnectDelay, "reconnectDelay");
        if (reconnectDelay.isNegative() || reconnectDelay.compareTo(Duration.ofSeconds(30)) > 0) {
            throw new IllegalArgumentException("reconnectDelay must be between zero and 30 seconds");
        }
        if (maxReconnects < -1) throw new IllegalArgumentException("maxReconnects");
    }

    public static RunStreamOptions defaults() {
        return new RunStreamOptions(null, 0, Duration.ofMillis(250), -1);
    }
}
