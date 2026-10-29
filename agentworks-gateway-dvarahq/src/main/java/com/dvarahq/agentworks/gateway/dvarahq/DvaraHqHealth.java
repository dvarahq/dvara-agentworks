package com.dvarahq.agentworks.gateway.dvarahq;

import java.time.Instant;

/** Secret-free process-local observation only; it is never lifecycle or quiescence authority. */
public record DvaraHqHealth(Status status, String code, Instant observedAt, long consecutiveFailures) {
    public enum Status { NEW, HEALTHY, DEGRADED, FAILED }
    public static DvaraHqHealth initial() { return new DvaraHqHealth(Status.NEW, "DVARAHQ_NOT_PROBED", Instant.EPOCH, 0); }
}
