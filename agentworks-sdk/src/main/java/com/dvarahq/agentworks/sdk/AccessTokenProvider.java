package com.dvarahq.agentworks.sdk;

/** Resolves an access token just in time. Implementations must return a fresh caller-owned array. */
@FunctionalInterface
public interface AccessTokenProvider {
    char[] resolve();
}
