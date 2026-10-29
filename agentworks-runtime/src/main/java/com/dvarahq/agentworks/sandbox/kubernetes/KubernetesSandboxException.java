package com.dvarahq.agentworks.sandbox.kubernetes;

public class KubernetesSandboxException extends RuntimeException {
    public KubernetesSandboxException(String message) {
        super(message);
    }

    public KubernetesSandboxException(String message, Throwable cause) {
        super(message, cause);
    }
}
