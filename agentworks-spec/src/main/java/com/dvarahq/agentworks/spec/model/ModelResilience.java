package com.dvarahq.agentworks.spec.model;

import java.util.List;

/** Optional authored model-call policy. Omitted values receive bounded conservative release defaults. */
public record ModelResilience(Integer maxAttempts, Long initialBackoffMillis, Long maxBackoffMillis, Double jitter,
        Integer maxConcurrent, Long bulkheadWaitMillis, Integer breakerFailureThreshold, Long breakerOpenMillis,
        Long requestTimeoutMillis, Long streamIdleTimeoutMillis, List<ModelFallback> fallbacks) { }
