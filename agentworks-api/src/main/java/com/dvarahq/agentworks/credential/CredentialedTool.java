package com.dvarahq.agentworks.credential;

@FunctionalInterface
public interface CredentialedTool {
    String invoke(char[] credentialMaterial);
}
