package com.dvarahq.agentworks.identity.web;

import com.dvarahq.agentworks.domain.identity.AuthenticatedPrincipal;
import com.dvarahq.agentworks.identity.ApiTokenService;
import com.dvarahq.agentworks.identity.SessionService;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.Authentication;

import java.util.Objects;
import java.util.Optional;

/**
 * Authenticates API tokens and session cookies against their stored digests. Every failure is the same
 * {@link BadCredentialsException} with a fixed message, whatever the cause.
 */
public final class CredentialAuthenticationProvider implements AuthenticationProvider {
    static final String INVALID = "The credentials are not valid.";

    private final ApiTokenService tokens;
    private final SessionService sessions;

    public CredentialAuthenticationProvider(ApiTokenService tokens, SessionService sessions) {
        this.tokens = Objects.requireNonNull(tokens, "tokens");
        this.sessions = Objects.requireNonNull(sessions, "sessions");
    }

    @Override
    public Authentication authenticate(Authentication authentication) {
        CredentialAuthenticationToken credential = (CredentialAuthenticationToken) authentication;
        Optional<AuthenticatedPrincipal> principal = switch (credential.kind()) {
            case BEARER -> tokens.authenticate(credential.value());
            case SESSION -> sessions.authenticate(credential.value());
        };
        return principal.map(AgentWorksAuthentication::new).orElseThrow(() -> new BadCredentialsException(INVALID));
    }

    @Override
    public boolean supports(Class<?> authentication) {
        return CredentialAuthenticationToken.class.isAssignableFrom(authentication);
    }
}
