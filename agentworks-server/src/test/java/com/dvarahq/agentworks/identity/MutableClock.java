package com.dvarahq.agentworks.identity;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;

/**
 * A test clock that starts at the current instant and only moves when a test advances it. It carries nanoseconds,
 * as the Linux system clock does, so code that stores microsecond timestamps is exercised on every platform.
 */
public final class MutableClock extends Clock {
    private final AtomicReference<Instant> now = new AtomicReference<>(Instant.now()
            .truncatedTo(java.time.temporal.ChronoUnit.MICROS).plusNanos(123));

    public void advance(Duration duration) {
        now.updateAndGet(instant -> instant.plus(duration));
    }

    @Override
    public ZoneId getZone() {
        return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        return this;
    }

    @Override
    public Instant instant() {
        return now.get();
    }
}
