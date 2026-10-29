package com.dvarahq.agentworks.a2a;

public final class ExternalProtocolException extends RuntimeException {
    private final ExternalProtocolError error;

    public ExternalProtocolException(ExternalProtocolError error, String message) {
        super(message);
        this.error = error;
    }

    public ExternalProtocolException(ExternalProtocolError error, String message, Throwable cause) {
        super(message, cause);
        this.error = error;
    }

    public ExternalProtocolError error() {
        return error;
    }
}
