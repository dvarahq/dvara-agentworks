package com.dvarahq.agentworks.a2a;

/** Resolves tenant-scoped verification material just in time; callers zeroize the returned array. */
@FunctionalInterface
public interface InboundKeyResolver {
    char[] resolve(String keyId);
}
