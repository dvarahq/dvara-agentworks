package com.dvarahq.agentworks.identity;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import java.util.regex.Pattern;

/**
 * Generates and digests bearer credential values. Session cookies, API tokens, and the bootstrap token are 256-bit
 * random values with a type prefix; only their SHA-256 digest is stored, which suffices because the values are not
 * guessable. Comparisons are constant-time.
 */
public final class CredentialCodec {
    public static final String SESSION_PREFIX = "aws_";
    public static final String TOKEN_PREFIX = "awt_";
    public static final String BOOTSTRAP_PREFIX = "awb_";
    /** The shape of every AgentWorks credential value; {@link #wellFormed} matches it whole. */
    public static final Pattern CREDENTIAL_SHAPE = Pattern.compile("aw[stb]_[A-Za-z0-9_-]{43}");
    private static final byte[] CSRF_PURPOSE = "agentworks-csrf-v1".getBytes(StandardCharsets.US_ASCII);

    private final SecureRandom random;

    public CredentialCodec() {
        this(new SecureRandom());
    }

    CredentialCodec(SecureRandom random) {
        this.random = random;
    }

    public String newValue(String prefix) {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return prefix + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /** A random password for administrator-issued resets; it must be changed at the next sign-in. */
    public String newTemporaryPassword() {
        byte[] bytes = new byte[18];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /** True when {@code value} is syntactically a credential of the given type; checked before any lookup. */
    public static boolean wellFormed(String value, String prefix) {
        return value != null && value.startsWith(prefix) && CREDENTIAL_SHAPE.matcher(value).matches();
    }

    public static String digest(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.US_ASCII)));
        } catch (NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException("SHA-256 is unavailable", unavailable);
        }
    }

    public static boolean sameDigest(String expected, String actual) {
        if (expected == null || actual == null) return false;
        return MessageDigest.isEqual(expected.getBytes(StandardCharsets.US_ASCII),
                actual.getBytes(StandardCharsets.US_ASCII));
    }

    /**
     * The CSRF token bound to a session: an HMAC of a fixed purpose keyed by the session value. The server
     * recomputes it from the {@code HttpOnly} cookie, so it needs no storage, and it cannot be derived without the
     * cookie value.
     */
    public static String csrfToken(String sessionValue) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(sessionValue.getBytes(StandardCharsets.US_ASCII), "HmacSHA256"));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(CSRF_PURPOSE));
        } catch (GeneralSecurityException unavailable) {
            throw new IllegalStateException("HMAC-SHA-256 is unavailable", unavailable);
        }
    }

    public static boolean sameToken(String expected, String presented) {
        if (expected == null || presented == null) return false;
        return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), presented.getBytes(StandardCharsets.UTF_8));
    }
}
