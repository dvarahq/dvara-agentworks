package com.dvarahq.agentworks.credential;

import com.dvarahq.agentworks.domain.security.CredentialRef;
import com.dvarahq.agentworks.domain.security.InvocationIdentity;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;

/** Resolves credential material after authorization and immediately around one effect. */
public final class JitCredentialToolInvoker {

    private final CredentialProvider credentialProvider;
    private final CredentialAuditSink auditSink;
    private final Clock clock;

    public JitCredentialToolInvoker(CredentialProvider credentialProvider, CredentialAuditSink auditSink, Clock clock) {
        this.credentialProvider = Objects.requireNonNull(credentialProvider, "credentialProvider");
        this.auditSink = Objects.requireNonNull(auditSink, "auditSink");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public String invoke(CredentialRef credentialRef, InvocationIdentity identity, CredentialedTool tool) {
        return invoke(CredentialUseRequest.withoutAgent(credentialRef, identity), tool);
    }

    public String invoke(CredentialUseRequest request, CredentialedTool tool) {
        Objects.requireNonNull(request, "request");
        CredentialRef credentialRef = request.credentialRef();
        InvocationIdentity identity = request.identity();
        Instant now = clock.instant();
        try {
            CredentialAuthorization authorization = credentialProvider.authorize(request, now);
            return resolveAndInvoke(authorization, credentialRef, identity, tool);
        } catch (RuntimeException failure) {
            auditSink.record(event("TOOL_CREDENTIAL_USE", "DENIED_OR_FAILED", credentialRef, identity, clock.instant()));
            throw failure;
        }
    }

    private String resolveAndInvoke(CredentialAuthorization authorization, CredentialRef credentialRef,
            InvocationIdentity identity, CredentialedTool tool) {
        try (ResolvedCredential credential = credentialProvider.resolve(authorization, clock.instant())) {
            String result = credential.use(clock.instant(), tool::invoke);
            auditSink.record(event("TOOL_CREDENTIAL_USE", "SUCCEEDED", credentialRef, identity, clock.instant()));
            return result;
        }
    }

    private static CredentialAuditEvent event(String action, String outcome, CredentialRef reference,
            InvocationIdentity identity, Instant occurredAt) {
        return new CredentialAuditEvent(action, outcome, reference,
                identity.organizationId(), identity.workspaceId(), identity.userId(), identity.sessionId(),
                identity.delegatedSubjectId(), occurredAt);
    }
}
