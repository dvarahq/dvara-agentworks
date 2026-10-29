package com.dvarahq.agentworks.identity;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** Credential values are unguessable, typed, stored only as digests, and compared in constant time. */
class CredentialCodecTest {
    private final CredentialCodec codec = new CredentialCodec();

    @Test
    void valuesAreTypedRandomAndOnlyTheirDigestIsDeterministic() {
        Set<String> seen = new HashSet<>();
        for (int index = 0; index < 1_000; index++) {
            String token = codec.newValue(CredentialCodec.TOKEN_PREFIX);
            assertThat(seen.add(token)).isTrue();
            assertThat(CredentialCodec.wellFormed(token, CredentialCodec.TOKEN_PREFIX)).isTrue();
            // A token is never accepted as a session or bootstrap value.
            assertThat(CredentialCodec.wellFormed(token, CredentialCodec.SESSION_PREFIX)).isFalse();
            assertThat(CredentialCodec.wellFormed(token, CredentialCodec.BOOTSTRAP_PREFIX)).isFalse();
        }
        String token = seen.iterator().next();
        assertThat(CredentialCodec.digest(token)).hasSize(64).isEqualTo(CredentialCodec.digest(token))
                .doesNotContain(token.substring(4));
        assertThat(CredentialCodec.sameDigest(CredentialCodec.digest(token), CredentialCodec.digest(token + "x"))).isFalse();
    }

    @Test
    void malformedAndTruncatedValuesAreRejectedBeforeLookup() {
        String token = codec.newValue(CredentialCodec.TOKEN_PREFIX);
        assertThat(CredentialCodec.wellFormed(token.substring(0, token.length() - 1), CredentialCodec.TOKEN_PREFIX))
                .isFalse();
        assertThat(CredentialCodec.wellFormed(token + "A", CredentialCodec.TOKEN_PREFIX)).isFalse();
        assertThat(CredentialCodec.wellFormed(token.replace('_', '.'), CredentialCodec.TOKEN_PREFIX)).isFalse();
        assertThat(CredentialCodec.wellFormed("", CredentialCodec.TOKEN_PREFIX)).isFalse();
        assertThat(CredentialCodec.wellFormed(null, CredentialCodec.TOKEN_PREFIX)).isFalse();
    }

    @Test
    void csrfTokensAreBoundToTheirSession() {
        String first = codec.newValue(CredentialCodec.SESSION_PREFIX);
        String second = codec.newValue(CredentialCodec.SESSION_PREFIX);
        String csrf = CredentialCodec.csrfToken(first);

        assertThat(CredentialCodec.csrfToken(first)).isEqualTo(csrf);
        assertThat(CredentialCodec.sameToken(csrf, CredentialCodec.csrfToken(first))).isTrue();
        assertThat(CredentialCodec.sameToken(csrf, CredentialCodec.csrfToken(second))).isFalse();
        assertThat(CredentialCodec.sameToken(csrf, null)).isFalse();
        assertThat(csrf).doesNotContain(first.substring(4));
    }

    @Test
    void temporaryPasswordsSatisfyThePolicy() {
        var policy = new PasswordPolicy(SecurityPolicy.defaults().passwords());
        for (int index = 0; index < 100; index++) policy.validate("someone", codec.newTemporaryPassword());
    }
}
