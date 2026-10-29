package com.dvarahq.agentworks.testing.fake;

import java.time.*;
import java.util.Objects;

/** Mutable only by the owning test; never observes wall-clock time. */
public final class FakeClock extends Clock {
    private Instant instant;
    private final ZoneId zone;
    public FakeClock(Instant instant) { this(instant, ZoneOffset.UTC); }
    private FakeClock(Instant instant, ZoneId zone) { this.instant = Objects.requireNonNull(instant); this.zone = Objects.requireNonNull(zone); }
    @Override public ZoneId getZone() { return zone; }
    @Override public Clock withZone(ZoneId requested) { return new FakeClock(instant, requested); }
    @Override public synchronized Instant instant() { return instant; }
    public synchronized FakeClock advance(Duration duration) {
        if (duration.isNegative()) throw new IllegalArgumentException("time cannot move backward");
        instant = instant.plus(duration); return this;
    }
    public synchronized FakeClock fresh() { return new FakeClock(instant, zone); }
}
