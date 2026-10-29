package com.dvarahq.agentworks.runplane.exposure;

import com.dvarahq.agentworks.exposure.ExposureRateLimiter;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Bounded node-local limiter; installations may replace it with a shared policy authority. */
public final class FixedWindowExposureRateLimiter implements ExposureRateLimiter {
    private static final int MAX_KEYS = 100_000;
    private final ConcurrentHashMap<Key, Counter> counters = new ConcurrentHashMap<>();

    @Override
    public synchronized boolean acquire(UUID workspaceId, String exposureId, String principalId,
            long requestsPerMinute, Instant now) {
        Objects.requireNonNull(now, "now");
        long minute = Math.floorDiv(now.getEpochSecond(), 60);
        Key key = new Key(workspaceId, exposureId, principalId);
        Counter existing = counters.get(key);
        if (existing == null && counters.size() >= MAX_KEYS) {
            counters.entrySet().removeIf(entry -> entry.getValue().minute < minute - 1);
            if (counters.size() >= MAX_KEYS) return false;
        }
        Counter counter = counters.compute(key, (ignored, current) -> {
            if (current == null || current.minute != minute) return new Counter(minute, 1);
            return new Counter(minute, current.count + 1);
        });
        return counter.count <= requestsPerMinute;
    }

    private record Key(UUID workspaceId, String exposureId, String principalId) {}
    private record Counter(long minute, long count) {}
}
