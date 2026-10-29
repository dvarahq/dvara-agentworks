package com.dvarahq.agentworks.identity.web;

import com.dvarahq.agentworks.identity.IdentityService;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;

import java.util.List;
import java.util.Objects;

/**
 * Verifies a local username and password. Success yields the verified user identifier as the principal, from
 * which the sign-in endpoint issues a session; every failure is one uniform {@link BadCredentialsException}.
 */
public final class PasswordAuthenticationProvider implements AuthenticationProvider {
    private final IdentityService identities;

    public PasswordAuthenticationProvider(IdentityService identities) {
        this.identities = Objects.requireNonNull(identities, "identities");
    }

    @Override
    public Authentication authenticate(Authentication authentication) {
        String username = String.valueOf(authentication.getPrincipal());
        Object credentials = authentication.getCredentials();
        String password = credentials instanceof String value ? value : "";
        return identities.verifyPassword(username, password)
                .map(user -> UsernamePasswordAuthenticationToken.authenticated(user.id(), null, List.of()))
                .orElseThrow(() -> new BadCredentialsException(CredentialAuthenticationProvider.INVALID));
    }

    @Override
    public boolean supports(Class<?> authentication) {
        return UsernamePasswordAuthenticationToken.class.isAssignableFrom(authentication);
    }
}
