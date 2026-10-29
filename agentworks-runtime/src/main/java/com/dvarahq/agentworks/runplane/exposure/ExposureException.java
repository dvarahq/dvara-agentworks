package com.dvarahq.agentworks.runplane.exposure;

public final class ExposureException extends RuntimeException {
    private final String code;
    public ExposureException(String code, String detail) { super(detail); this.code = code; }
    public String code() { return code; }
}
