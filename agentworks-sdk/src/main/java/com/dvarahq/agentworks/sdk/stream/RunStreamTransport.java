package com.dvarahq.agentworks.sdk.stream;

import java.io.BufferedReader;

/** Injectable streaming transport. Connections are closed on cancel, reconnect, or terminal delivery. */
@FunctionalInterface
public interface RunStreamTransport {
    Connection open(String runId, RunStreamOptions options, long afterSequence);

    interface Connection extends AutoCloseable {
        int status();
        BufferedReader reader();
        @Override void close();
    }
}
