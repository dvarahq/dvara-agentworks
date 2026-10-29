package com.dvarahq.agentworks.identity;

import com.dvarahq.agentworks.domain.identity.AuthenticatedPrincipal;
import com.dvarahq.agentworks.storage.run.JdbcRunControlAudit;

import java.util.Objects;

/**
 * Records run control actions (an operator's pause request and its withdrawal, #183) in the authentication and
 * administration audit trail on the Run Plane connection, inside the run engine's transaction (#194). The record's
 * workspace is the one the action was resolved in, the run's, which every run-control target must carry (#198); its
 * organization is that workspace's ({@link JdbcRunControlAudit}). The actor, authentication method, and credential are
 * the principal's.
 */
public final class RunControlAuditLog implements ControlActionAudit.Recorder {
    private final JdbcRunControlAudit writer;

    public RunControlAuditLog(JdbcRunControlAudit writer) {
        this.writer = Objects.requireNonNull(writer, "writer");
    }

    @Override
    public void record(String action, AuthenticatedPrincipal principal, ControlActionAudit.Target target) {
        if (target.workspace() == null) {
            throw new IllegalArgumentException("a run-control audit target names the workspace it was resolved in");
        }
        writer.append(target.workspace().value(), target.id(), action, target.detail(), principal.subjectId(),
                principal.method().value(), principal.credentialId(), AuthAuditLog.remoteAddress());
    }
}
