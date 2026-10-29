package com.dvarahq.agentworks.identity;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Objects;

/**
 * Authentication policy bound from {@code agentworks.security.*}. Every value has a secure default; the
 * {@code local} development profile relaxes only the cookie {@code Secure} attribute for plain-HTTP loopback use.
 */
public record SecurityPolicy(Session session, Tokens tokens, Login login, Bootstrap bootstrap, Passwords passwords,
        Requests requests) {

    /**
     * @param idleTimeout a session unused for this long is rejected
     * @param absoluteTimeout a session is rejected this long after sign-in, however active
     * @param maxConcurrent the number of live sessions per user; signing in beyond it revokes the oldest
     * @param touchInterval how stale {@code last_seen_at} may become before a request refreshes it
     * @param cookieSecure whether the cookie is {@code Secure} and uses the {@code __Host-} prefix
     * @param sameSite the cookie {@code SameSite} attribute: {@code Strict} or {@code Lax}
     */
    public record Session(Duration idleTimeout, Duration absoluteTimeout, int maxConcurrent, Duration touchInterval,
            boolean cookieSecure, String sameSite) {
        public Session {
            positive(idleTimeout, "session idle timeout");
            positive(absoluteTimeout, "session absolute timeout");
            positive(touchInterval, "session touch interval");
            if (maxConcurrent < 1) throw new IllegalArgumentException("at least one concurrent session is required");
            if (!"Strict".equals(sameSite) && !"Lax".equals(sameSite)) {
                throw new IllegalArgumentException("session SameSite must be Strict or Lax");
            }
        }

        public String cookieName() {
            return cookieSecure ? "__Host-agentworks-session" : "agentworks-session";
        }
    }

    public record Tokens(Duration defaultLifetime, Duration maxLifetime, Duration touchInterval) {
        public Tokens {
            positive(defaultLifetime, "token default lifetime");
            positive(maxLifetime, "token maximum lifetime");
            positive(touchInterval, "token touch interval");
            if (defaultLifetime.compareTo(maxLifetime) > 0) {
                throw new IllegalArgumentException("the default token lifetime exceeds the maximum");
            }
        }
    }

    /**
     * @param maxFailures consecutive failed passwords that lock an account
     * @param lockout how long a locked account refuses every password
     * @param attemptsPerWindow sign-in and bootstrap attempts accepted per client address and window
     * @param maxConcurrentVerifications password verifications hashing at once on this node; further attempts wait
     *                                   briefly and are then refused with 429, bounding Argon2 memory
     * @param attemptsPerAccountWindow sign-in attempts accepted per username and window, from any clients
     */
    public record Login(int maxFailures, Duration lockout, int attemptsPerWindow, Duration window,
            int maxConcurrentVerifications, int attemptsPerAccountWindow) {
        public Login {
            if (maxFailures < 1 || attemptsPerWindow < 1 || maxConcurrentVerifications < 1
                    || attemptsPerAccountWindow < 1) {
                throw new IllegalArgumentException("login limits must be positive");
            }
            positive(lockout, "lockout");
            positive(window, "attempt window");
        }
    }

    /**
     * @param tokenFile where the one-time bootstrap token is written with owner-only permissions
     * @param reissue an explicit, single-use operator override for recovering a token whose file was lost (#177,
     *     #185): a one-time value of the operator's choosing (for example the current time). The first node to start
     *     with a value not used before, and with its {@code reissueConfirm} code (#206), replaces a live token that its
     *     own file does not hold, invalidating the old one
     *     (audited as {@code BOOTSTRAP_REISSUED}), and records the value as consumed; no later start of any node with
     *     that value replaces the token again. A value replaces only the token that was live when a node first saw it
     *     (#201), never one issued later by another node. {@code null}, an empty value, and the values of {@link #DISABLED} (every
     *     value Spring reads as a boolean {@code false}, and {@code n}, in any case) are no override, so a setting
     *     written in the earlier boolean form to disable the override never replaces a token.
     * @param reissueConfirm the operator's confirmation of a reissue value (#206): the confirmation code that a start
     *     with the value logged, which names the value and the token it replaces. A value replaces a live token only on a
     *     start that also has its code, so neither another replica nor a restart that only has the value replaces the token (the code is derivable from the database, so it is not a secret against a database reader). Codes
     *     are 16 hexadecimal digits, compared ignoring case; {@code null} or empty is no confirmation.
     */
    public record Bootstrap(boolean enabled, Duration lifetime, Path tokenFile, String reissue, String reissueConfirm) {
        /** A reissue value: 1 to 128 characters of a restricted set, such as {@code 2026-09-26T10:00Z}. */
        public static final String REISSUE_PATTERN = "[A-Za-z0-9:._@/+=-]{1,128}";

        /** A reissue confirmation code, as a start logs it: 16 hexadecimal digits (#206). */
        public static final String REISSUE_CONFIRM_PATTERN = "[0-9A-Fa-f]{16}";

        /**
         * The values that disable the override (compared ignoring case): those Spring's boolean conversion reads as
         * {@code false} ({@code false}, {@code off}, {@code no}, {@code 0}), which disabled it when the setting was a
         * boolean (#185), and also {@code n}. Spring never read {@code n} as a boolean, but an operator who writes it
         * means "no", so it disables the override rather than being taken as a one-time value that replaces the token
         * (#189).
         */
        public static final java.util.Set<String> DISABLED = java.util.Set.of("false", "off", "no", "n", "0");

        public Bootstrap {
            positive(lifetime, "bootstrap lifetime");
            Objects.requireNonNull(tokenFile, "tokenFile");
            reissue = reissue == null || reissue.isBlank()
                    || DISABLED.contains(reissue.strip().toLowerCase(java.util.Locale.ROOT)) ? null : reissue.strip();
            if (reissue != null && !reissue.matches(REISSUE_PATTERN)) {
                throw new IllegalArgumentException("agentworks.security.bootstrap.reissue is a one-time value of 1 to "
                        + "128 characters of [A-Za-z0-9:._@/+=-], for example the current time");
            }
            reissueConfirm = reissueConfirm == null || reissueConfirm.isBlank() ? null
                    : reissueConfirm.strip().toLowerCase(java.util.Locale.ROOT);
            if (reissueConfirm != null && !reissueConfirm.matches(REISSUE_CONFIRM_PATTERN)) {
                throw new IllegalArgumentException("agentworks.security.bootstrap.reissue-confirm is the 16-digit "
                        + "hexadecimal confirmation code that a start with agentworks.security.bootstrap.reissue logged");
            }
        }

        public Bootstrap(boolean enabled, Duration lifetime, Path tokenFile, String reissue) {
            this(enabled, lifetime, tokenFile, reissue, null);
        }

        public Bootstrap(boolean enabled, Duration lifetime, Path tokenFile) {
            this(enabled, lifetime, tokenFile, null, null);
        }
    }

    public record Passwords(int minLength, int maxLength) {
        public Passwords {
            if (minLength < 8 || maxLength < minLength || maxLength > 1024) {
                throw new IllegalArgumentException("password length bounds are invalid");
            }
        }
    }

    /** Maximum body accepted by ordinary endpoints. Artifact uploads enforce their own store bound. */
    public record Requests(long maxBodySize) {
        public Requests {
            if (maxBodySize < 1 || maxBodySize > 64L * 1024 * 1024) {
                throw new IllegalArgumentException("request body maximum must be between 1 byte and 64 MiB");
            }
        }
    }

    public static SecurityPolicy defaults() {
        return new SecurityPolicy(
                new Session(Duration.ofMinutes(30), Duration.ofHours(12), 5, Duration.ofMinutes(1), true, "Strict"),
                new Tokens(Duration.ofDays(30), Duration.ofDays(365), Duration.ofMinutes(1)),
                new Login(5, Duration.ofMinutes(15), 20, Duration.ofMinutes(1), 16, 10),
                new Bootstrap(true, Duration.ofHours(1), Path.of("agentworks-bootstrap-token")),
                new Passwords(12, 128),
                new Requests(1024 * 1024));
    }

    private static void positive(Duration duration, String name) {
        if (duration == null || duration.isZero() || duration.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
    }
}
