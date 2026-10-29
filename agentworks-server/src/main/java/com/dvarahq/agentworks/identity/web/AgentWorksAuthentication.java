package com.dvarahq.agentworks.identity.web;

import com.dvarahq.agentworks.domain.identity.AuthenticatedPrincipal;
import com.dvarahq.agentworks.domain.identity.AuthenticationMethod;
import com.dvarahq.agentworks.domain.identity.WorkspacePermission;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * The Spring Security view of an {@link AuthenticatedPrincipal}. It retains no credential. Its authorities are
 * what request authorization evaluates:
 * <ul>
 *   <li>{@value #AUTHENTICATED} for every principal;</li>
 *   <li>{@value #SESSION} for a browser session, even while a password change is pending;</li>
 *   <li>{@value #ACTIVE} and, for sessions, {@value #ACTIVE_SESSION} once no password change is pending;</li>
 *   <li>{@value #ORGANIZATION_ADMIN} for an active session of an organization administrator;</li>
 *   <li>one {@value #PERMISSION_PREFIX}{@code <permission>} per effective workspace permission while active.</li>
 * </ul>
 * Any other authentication provider (for example a later external identity provider) produces this type, so it
 * is authorized by exactly the same membership-derived rules.
 */
public final class AgentWorksAuthentication extends AbstractAuthenticationToken {
    public static final String AUTHENTICATED = "agentworks:authenticated";
    public static final String SESSION = "agentworks:session";
    public static final String ACTIVE = "agentworks:active";
    public static final String ACTIVE_SESSION = "agentworks:active-session";
    public static final String ORGANIZATION_ADMIN = "agentworks:organization-admin";
    public static final String PERMISSION_PREFIX = "agentworks:permission:";

    private final AuthenticatedPrincipal principal;

    public AgentWorksAuthentication(AuthenticatedPrincipal principal) {
        super(authorities(principal));
        this.principal = Objects.requireNonNull(principal, "principal");
        setAuthenticated(true);
    }

    public static String permission(WorkspacePermission permission) {
        return PERMISSION_PREFIX + permission.name();
    }

    @Override
    public Object getCredentials() {
        return null;
    }

    @Override
    public AuthenticatedPrincipal getPrincipal() {
        return principal;
    }

    @Override
    public String getName() {
        return principal.subjectId().toString();
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof AgentWorksAuthentication authentication && principal.equals(authentication.principal);
    }

    @Override
    public int hashCode() {
        return principal.hashCode();
    }

    @Override
    public String toString() {
        return "AgentWorksAuthentication[subject=" + principal.subjectId() + ", method=" + principal.method() + "]";
    }

    private static List<GrantedAuthority> authorities(AuthenticatedPrincipal principal) {
        List<GrantedAuthority> authorities = new ArrayList<>();
        authorities.add(new SimpleGrantedAuthority(AUTHENTICATED));
        boolean session = AuthenticationMethod.SESSION.equals(principal.method());
        if (session) authorities.add(new SimpleGrantedAuthority(SESSION));
        if (principal.mustChangePassword()) return authorities;
        authorities.add(new SimpleGrantedAuthority(ACTIVE));
        if (session) authorities.add(new SimpleGrantedAuthority(ACTIVE_SESSION));
        if (session && principal.organizationAdmin()) authorities.add(new SimpleGrantedAuthority(ORGANIZATION_ADMIN));
        principal.permissions().forEach(permission -> authorities.add(new SimpleGrantedAuthority(permission(permission))));
        return authorities;
    }
}
