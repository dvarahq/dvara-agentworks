package com.dvarahq.agentworks.identity;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** NIST SP 800-63B-style password rules and username normalization; rejections never echo the value. */
class PasswordPolicyTest {
    private final PasswordPolicy policy = new PasswordPolicy(SecurityPolicy.defaults().passwords());

    @Test
    void acceptsLongPassphrasesWithoutCompositionRules() {
        assertThatCode(() -> policy.validate("alice", "purple otter sings loudly")).doesNotThrowAnyException();
        assertThatCode(() -> policy.validate("alice", "ÜñïçødéPässwörd✓")).doesNotThrowAnyException();
    }

    @Test
    void rejectsShortLongCommonRepetitiveAndUsernameDerivedPasswords() {
        String longest = "x1y2".repeat(32);
        assertThatCode(() -> policy.validate("alice", longest)).doesNotThrowAnyException();
        for (String rejected : new String[]{"short1!", longest + "z", "Password123", "p-a-s-s-w-o-r-d-1-2-3",
                "aaaaaaaaaaaaaaaa", "abababababab", "my-alice-secret-phrase", null}) {
            assertThatThrownBy(() -> policy.validate("alice", rejected))
                    .isInstanceOfSatisfying(IdentityException.class, failure -> {
                        assertThat(failure.code()).isEqualTo(IdentityException.PASSWORD_POLICY);
                        if (rejected != null) assertThat(failure.getMessage()).doesNotContain(rejected);
                    });
        }
    }

    @Test
    void usernamesAreNormalizedAndConstrained() {
        assertThat(PasswordPolicy.normalizeUsername("  Alice@Example.COM ")).contains("alice@example.com");
        // NFKC folds compatibility forms, so visually confusable full-width names collide with ASCII ones.
        assertThat(PasswordPolicy.normalizeUsername("ａｌｉｃｅ")).contains("alice");
        assertThat(PasswordPolicy.normalizeUsername("al")).isEmpty();
        assertThat(PasswordPolicy.normalizeUsername("alice smith")).isEmpty();
        assertThat(PasswordPolicy.normalizeUsername("alice\u0000")).isEmpty();
        assertThat(PasswordPolicy.normalizeUsername("a".repeat(65))).isEmpty();
        assertThat(PasswordPolicy.normalizeUsername(null)).isEmpty();
    }
}
