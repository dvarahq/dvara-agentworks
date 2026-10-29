package com.dvarahq.agentworks.identity;

import java.text.Normalizer;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Local password and username rules following NIST SP 800-63B: a length floor and ceiling, no composition rules,
 * and rejection of commonly used, repetitive, or username-derived values. Rejections name the rule, never the
 * rejected value.
 */
public final class PasswordPolicy {
    private static final Pattern USERNAME = Pattern.compile("[a-z0-9][a-z0-9._@+-]{2,63}");
    private static final Set<String> COMMON = Set.of("password", "password1", "password12", "password123",
            "passw0rd", "123456789012", "1234567890", "qwertyuiop", "qwertyuiopas", "letmein", "welcome",
            "welcome123", "iloveyou", "administrator", "admin123", "changeme", "changeme123", "agentworks",
            "agentworks123", "dvaraagentworks", "correcthorsebatterystaple");

    private final int minLength;
    private final int maxLength;

    public PasswordPolicy(SecurityPolicy.Passwords passwords) {
        this.minLength = passwords.minLength();
        this.maxLength = passwords.maxLength();
    }

    /** NFKC-normalized, trimmed, lower-case username, or empty when it is not a valid username. */
    public static Optional<String> normalizeUsername(String username) {
        if (username == null || username.length() > 256) return Optional.empty();
        String normalized = Normalizer.normalize(username, Normalizer.Form.NFKC).strip().toLowerCase(Locale.ROOT);
        return USERNAME.matcher(normalized).matches() ? Optional.of(normalized) : Optional.empty();
    }

    public void validate(String username, String password) {
        if (password == null) throw violation("a password is required");
        int length = password.codePointCount(0, password.length());
        if (length < minLength) throw violation("the password must have at least " + minLength + " characters");
        if (length > maxLength) throw violation("the password must have at most " + maxLength + " characters");
        String folded = password.toLowerCase(Locale.ROOT);
        if (COMMON.contains(folded) || COMMON.contains(folded.replaceAll("[^a-z0-9]", ""))) {
            throw violation("the password is too common");
        }
        if (password.codePoints().distinct().count() < 4) throw violation("the password is too repetitive");
        if (username != null && username.length() >= 3 && folded.contains(username.toLowerCase(Locale.ROOT))) {
            throw violation("the password must not contain the username");
        }
    }

    private static IdentityException violation(String rule) {
        return new IdentityException(IdentityException.PASSWORD_POLICY, "Password rejected: " + rule + ".");
    }
}
