package com.dvarahq.agentworks.a2a;

/** Supplies short-lived signing material. The caller zeroizes the returned array immediately after signing. */
@FunctionalInterface
public interface RemoteExecutorCredentials {
    char[] resolve();
}
