package com.dvarahq.agentworks.agent.remote;

public final class ExternalProtocolException extends RuntimeException {
    public ExternalProtocolException(String message) {
        super(message);
    }

    public ExternalProtocolException(String message, Throwable cause) {
        super(message, cause);
    }
}
