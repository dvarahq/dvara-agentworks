package com.dvarahq.agentworks.starter;

/** Application-owned JIT resolution of a configured credential reference. Values are never stored by the starter. */
@FunctionalInterface
public interface CredentialReferenceResolver {
    char[] resolve(String reference);
}
