package com.dvarahq.agentworks.credential;

@FunctionalInterface
public interface CredentialAuditSink {
    void record(CredentialAuditEvent event);
}
