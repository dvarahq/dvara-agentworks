package com.dvarahq.agentworks.domain.run;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * A run that the nodes of one runtime compatibility identity do not select for a while, or until an operator releases
 * it (issue #181). It is durable and shared by every node of that identity: their claim query skips the run by
 * predicate, so any number of quarantined runs is skipped and none of them can starve a healthy run behind it. Nodes of
 * another identity (another build, or other supported event, checkpoint, or manifest versions) ignore it and may claim
 * the run, so a refusal by an old or misconfigured node never takes a run away from nodes that can execute it. The row
 * is also the operator-visible signal; the administrative surface that lists and releases quarantines is #79.
 *
 * @param compatibility the runtime compatibility identity of the nodes that skip the run
 * @param reason why the run is quarantined
 * @param code the typed failure that caused the latest quarantine
 * @param quarantines how many times the run has been quarantined for this identity; each timed quarantine doubles
 * @param quarantinedBy the worker that recorded the latest quarantine
 * @param notBefore when those nodes may select the run again, in database time; {@code null} until released
 * @param closedAt when the run ended, which closes the quarantine; {@code null} while the run can still be claimed
 */
public record ClaimQuarantine(UUID workspaceId, UUID runId, String compatibility, Reason reason, String code,
        int quarantines, String quarantinedBy, Instant quarantinedAt, Instant notBefore, Instant closedAt) {

    public enum Reason {
        /** The node cannot interpret the run: its history, manifest, checkpoint, or recorded facts are refused. */
        UNINTERPRETABLE,
        /** The run's claim failed repeatedly for a reason of the run's own. */
        CLAIM_FAILING
    }

    /** A runtime compatibility identity: 1 to 128 characters of a restricted set. */
    public static final String COMPATIBILITY_PATTERN = "[A-Za-z0-9:._@/+=-]{1,128}";

    public ClaimQuarantine {
        Objects.requireNonNull(workspaceId, "workspaceId");
        Objects.requireNonNull(runId, "runId");
        ClaimQuarantine.compatibility(compatibility);
        Objects.requireNonNull(reason, "reason");
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(quarantinedBy, "quarantinedBy");
        Objects.requireNonNull(quarantinedAt, "quarantinedAt");
        if (quarantines < 1) throw new IllegalArgumentException("quarantines is positive");
    }

    /** Validates a runtime compatibility identity. */
    public static String compatibility(String value) {
        if (value == null || !value.matches(COMPATIBILITY_PATTERN)) {
            throw new IllegalArgumentException("a compatibility identity is 1 to 128 characters of [A-Za-z0-9:._@/+=-]");
        }
        return value;
    }

    /**
     * A run's quarantines are counted afresh once its latest timed quarantine ended this long ago: the count bounds the
     * attempts of one unbroken episode of failed claims, not of a run's whole life.
     */
    public static final Duration EPISODE_GAP = Duration.ofDays(1);

    /** The count the next quarantine of a run carries at {@code now}, given its previous quarantine if any. */
    public static int next(ClaimQuarantine previous, Instant now) {
        if (previous == null) return 1;
        if (previous.notBefore() != null && previous.notBefore().plus(EPISODE_GAP).isBefore(now)) return 1;
        return previous.quarantines() + 1;
    }

    /** True when the run stays excluded until an operator releases it. */
    public boolean indefinite() {
        return notBefore == null;
    }

    /** True when nodes of its identity skip the run at {@code now}: it is open and in force. */
    public boolean excludes(Instant now) {
        return closedAt == null && (notBefore == null || notBefore.isAfter(now));
    }

    /**
     * What a worker asks a store to record.
     *
     * @param compatibility the refusing node's runtime compatibility identity; only its nodes skip the run
     * @param base the first timed quarantine's length, doubled by each later one; {@code null} quarantines the run
     *     until an operator releases it
     * @param maxTimed how many timed quarantines a run gets; the next one lasts until an operator releases it, so the
     *     claim attempts any one run can cost the nodes of an identity are bounded
     * @param observed the run state the refused claim observed; the quarantine is recorded only while the run is still
     *     in it, so a delayed quarantine never applies to a later run state ({@code null} when the claim failed before
     *     the run could be read)
     */
    public record Request(String compatibility, Reason reason, String code, String worker, Duration base, int maxTimed,
            Observed observed) {
        public Request {
            ClaimQuarantine.compatibility(compatibility);
            Objects.requireNonNull(reason, "reason");
            if (code == null || !code.matches("[A-Z][A-Z0-9_]{0,63}")) {
                throw new IllegalArgumentException("a quarantine code is 1 to 64 characters of [A-Z0-9_]");
            }
            if (worker == null || worker.isEmpty() || worker.length() > 128) {
                throw new IllegalArgumentException("a worker id is 1 to 128 characters");
            }
            if (base != null && (base.isZero() || base.isNegative())) {
                throw new IllegalArgumentException("a timed quarantine is positive");
            }
            if (maxTimed < 1 || maxTimed > 16) throw new IllegalArgumentException("maxTimed is 1 to 16");
        }

        /**
         * When the {@code quarantines}-th quarantine of a run ends: {@code base * 2^(quarantines - 1)} after
         * {@code now}, or {@code null} (until released) for an indefinite request or beyond {@link #maxTimed()}.
         */
        public Instant notBefore(Instant now, int quarantines) {
            if (base == null || quarantines > maxTimed) return null;
            return now.plus(base.multipliedBy(1L << (quarantines - 1)));
        }
    }

    /** The run state a claim observed: its event sequence and lease fence. */
    public record Observed(long eventSequence, long leaseFence) {}
}
