package com.dvarahq.agentworks.exposure;

import java.time.Instant;
import java.util.UUID;

/** Installation-supplied rate authority; denial happens before session or route mutation. */
@FunctionalInterface
public interface ExposureRateLimiter {
    boolean acquire(UUID workspaceId, String exposureId, String principalId, long requestsPerMinute, Instant now);
}
