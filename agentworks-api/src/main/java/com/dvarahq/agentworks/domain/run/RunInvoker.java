package com.dvarahq.agentworks.domain.run;

import com.dvarahq.agentworks.domain.security.InvocationIdentity;

import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * The authenticated principal that submitted a run (issue #74), recorded durably on the run's {@code RUN_CREATED}
 * event so that a stateless worker can invoke models on its behalf later, on any node (issue #34). It carries
 * identifiers only, never a password, session value, or token.
 *
 * @param organizationId the principal's organization
 * @param workspaceId    the workspace the principal acted in; model invocation refuses it unless it owns the release
 * @param subjectId      the authenticated user
 * @param credentialId   the session or API token record that authenticated the submission
 * @param method         how the principal authenticated, for example {@code session} or {@code api-token}
 */
public record RunInvoker(UUID organizationId, UUID workspaceId, UUID subjectId, UUID credentialId, String method) {
    private static final Pattern METHOD = Pattern.compile("[a-z][a-z0-9-]{0,31}");

    public RunInvoker {
        Objects.requireNonNull(organizationId, "organizationId");
        Objects.requireNonNull(workspaceId, "workspaceId");
        Objects.requireNonNull(subjectId, "subjectId");
        Objects.requireNonNull(credentialId, "credentialId");
        if (method == null || !METHOD.matcher(method).matches()) {
            throw new IllegalArgumentException("method is 1 to 32 characters of [a-z0-9-]");
        }
    }

    /**
     * The identity a model invocation and its just-in-time credential authorization run under. There is no delegation
     * in 1.0, so the principal is its own delegated subject; the credential that authenticated the submission stands
     * in for the session.
     */
    public InvocationIdentity identity() {
        return new InvocationIdentity(organizationId, workspaceId, subjectId, credentialId, subjectId);
    }
}
