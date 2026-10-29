package com.dvarahq.agentworks.runplane.admission;

/** Typed admission refusal; no run was created. {@code code} is a stable machine value. */
public final class RunAdmissionRejectedException extends RuntimeException {
    private final String code;

    public RunAdmissionRejectedException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
