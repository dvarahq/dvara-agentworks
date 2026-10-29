package com.dvarahq.agentworks.runplane.execution;

/** Recoverable, pre-effect local saturation. The worker abandons its claim for normal lease recovery. */
public final class RunBackpressureException extends RuntimeException {
    private final String code;

    public RunBackpressureException(String code, String message) {
        super(message);
        if (code == null || !code.matches("[A-Z][A-Z0-9_]{0,63}")) throw new IllegalArgumentException("invalid code");
        this.code = code;
    }

    public String code() { return code; }
}
