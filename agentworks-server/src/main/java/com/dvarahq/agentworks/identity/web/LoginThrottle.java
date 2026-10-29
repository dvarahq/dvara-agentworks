package com.dvarahq.agentworks.identity.web;

import com.dvarahq.agentworks.identity.PasswordPolicy;
import com.dvarahq.agentworks.identity.SecurityPolicy;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * Fixed-window limits on sign-in and bootstrap attempts, complementing the per-account lockout that the database
 * enforces across nodes:
 * <ul>
 *   <li><b>per client:</b> an IPv4 address, or an IPv6 {@code /64}, since one host can rotate through its whole
 *       prefix;</li>
 *   <li><b>per account:</b> sign-in attempts for one username from any number of clients, so a distributed guesser
 *       is slowed without locking the account.</li>
 * </ul>
 * The client is the servlet remote address. Behind a reverse proxy, trust the proxy and set
 * {@code server.forward-headers-strategy}; otherwise every client shares the proxy's bucket (the server logs a
 * warning at startup). The limits are node-local and bounded: when a map tracks too many keys it forgets expired
 * windows first and then the oldest half, so memory stays bounded under spraying without resetting recent limits.
 */
public final class LoginThrottle {
    static final int MAX_TRACKED = 10_000;
    private static final Pattern IP_LITERAL = Pattern.compile("[0-9A-Fa-f:.%\\[\\]]+");

    private record Window(Instant start, int attempts) {}

    private final SecurityPolicy.Login policy;
    private final Clock clock;
    private final Map<String, Window> clients = new ConcurrentHashMap<>();
    private final Map<String, Window> accounts = new ConcurrentHashMap<>();

    public LoginThrottle(SecurityPolicy.Login policy, Clock clock) {
        this.policy = Objects.requireNonNull(policy, "policy");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** Counts one attempt from the client at {@code address}; false when the client exhausted its window. */
    public boolean tryAcquire(String address) {
        return acquire(clients, clientKey(address), policy.attemptsPerWindow());
    }

    /** Counts one sign-in attempt for {@code username}; false when the account exhausted its window. */
    public boolean tryAcquireAccount(String username) {
        return acquire(accounts, accountKey(username), policy.attemptsPerAccountWindow());
    }

    /** Seconds until the client's current window ends. */
    public long retryAfterSeconds(String address) {
        return retryAfter(clients.get(clientKey(address)));
    }

    /** Seconds until the account's current window ends. */
    public long accountRetryAfterSeconds(String username) {
        return retryAfter(accounts.get(accountKey(username)));
    }

    /** The bucket of a client: the IPv4 address, or the {@code /64} prefix of an IPv6 address. */
    public static String clientKey(String address) {
        if (address == null || address.isBlank()) return "unknown";
        String literal = address.strip();
        // Only an IPv6 literal (which contains a colon, so it is never resolved through DNS) is parsed.
        if (literal.indexOf(':') < 0 || !IP_LITERAL.matcher(literal).matches()) return literal.toLowerCase(Locale.ROOT);
        try {
            InetAddress parsed = InetAddress.getByName(literal);
            if (parsed instanceof Inet6Address) {
                byte[] prefix = java.util.Arrays.copyOf(parsed.getAddress(), 8);
                return "v6:" + HexFormat.of().formatHex(prefix) + "/64";
            }
            return parsed.getHostAddress();
        } catch (UnknownHostException | SecurityException unparseable) {
            return literal.toLowerCase(Locale.ROOT);
        }
    }

    /**
     * The account bucket of a username. Every malformed username shares one bucket whose key no username can take
     * ({@code #} is not a username character), so a real user named "invalid" keeps a bucket of its own (#177).
     */
    private static String accountKey(String username) {
        return PasswordPolicy.normalizeUsername(username).orElse(MALFORMED_ACCOUNT);
    }

    private static final String MALFORMED_ACCOUNT = "#malformed";

    private boolean acquire(Map<String, Window> windows, String key, int limit) {
        Instant now = clock.instant();
        if (windows.size() >= MAX_TRACKED) prune(windows, now);
        Window window = windows.compute(key, (ignored, current) ->
                current == null || !current.start().plus(policy.window()).isAfter(now) ? new Window(now, 1)
                        : new Window(current.start(), current.attempts() + 1));
        return window.attempts() <= limit;
    }

    private long retryAfter(Window window) {
        if (window == null) return 1;
        long seconds = Duration.between(clock.instant(), window.start().plus(policy.window())).toSeconds();
        return Math.max(1, seconds + 1);
    }

    /** Forgets expired windows, then the oldest half, so no client can reset the others' limits. */
    private void prune(Map<String, Window> windows, Instant now) {
        windows.values().removeIf(window -> !window.start().plus(policy.window()).isAfter(now));
        if (windows.size() < MAX_TRACKED) return;
        List<Map.Entry<String, Window>> oldest = windows.entrySet().stream()
                .sorted(Map.Entry.comparingByValue(Comparator.comparing(Window::start)))
                .limit(windows.size() / 2).toList();
        oldest.forEach(entry -> windows.remove(entry.getKey(), entry.getValue()));
    }
}
