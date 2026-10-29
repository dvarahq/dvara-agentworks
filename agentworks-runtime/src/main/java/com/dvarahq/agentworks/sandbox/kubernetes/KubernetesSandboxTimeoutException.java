package com.dvarahq.agentworks.sandbox.kubernetes;

final class KubernetesSandboxTimeoutException extends KubernetesSandboxException {
    KubernetesSandboxTimeoutException(String message) {
        super(message);
    }
}
