package com.dvarahq.agentworks.storage.run;

import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Appends a run-control audit record (an operator's pause request or its withdrawal, #183) on the run engine's own
 * connection, so it joins the transaction that makes the change (#194). On every path:
 * <ul>
 *   <li>only the run-control (event type, detail) pairs are accepted ({@link #PAIRS});</li>
 *   <li>the target must be a run of the given workspace, and the organization is that workspace's;</li>
 *   <li>the record is stamped in database time, with outcome {@code SUCCEEDED} and target type {@code RUN}.</li>
 * </ul>
 * On a dedicated PostgreSQL Run Plane connection the record is appended through
 * {@code agentworks_append_run_control_audit} (V17_1, V19_1), because the Run Plane holds no privilege on
 * {@code auth_audit_event}; the function checks the same rules again. Otherwise (H2, or both planes on the owner
 * connection) it is inserted directly.
 */
public final class JdbcRunControlAudit {
    /** The accepted details of each run-control event type (the names of the engine's pause outcomes). */
    public static final Map<String, Set<String>> PAIRS = Map.of(
            "RUN_PAUSE_REQUESTED", Set.of("PAUSED", "ALREADY_PAUSED", "REQUESTED", "REPEATED"),
            "RUN_PAUSE_WITHDRAWN", Set.of("WITHDRAWN"));

    private final JdbcTemplate jdbc;
    private final boolean dedicatedRunPlane;
    private volatile Boolean viaFunction;

    /** @param dedicatedRunPlane whether {@code jdbc} is the Run Plane's own login, not the owner connection */
    public JdbcRunControlAudit(JdbcTemplate jdbc, boolean dedicatedRunPlane) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.dedicatedRunPlane = dedicatedRunPlane;
    }

    /**
     * Appends the record.
     *
     * @throws IllegalArgumentException for a pair that is not a run-control record
     * @throws IllegalStateException when the run is not a run of {@code workspaceId}
     */
    public void append(UUID workspaceId, UUID runId, String eventType, String detail, UUID actorId,
            String authenticationMethod, UUID credentialId, String remoteAddress) {
        Objects.requireNonNull(workspaceId, "workspaceId");
        Objects.requireNonNull(runId, "runId");
        if (eventType == null || detail == null || !PAIRS.getOrDefault(eventType, Set.of()).contains(detail)) {
            throw new IllegalArgumentException(eventType + " with detail " + detail + " is not a run-control record");
        }
        if (viaFunction()) {
            jdbc.queryForObject("select agentworks_append_run_control_audit(?, ?, ?, ?, ?, ?, ?, ?)", UUID.class,
                    workspaceId, runId, eventType, detail, actorId, authenticationMethod, credentialId, remoteAddress);
            return;
        }
        int appended = jdbc.update("insert into auth_audit_event(id, occurred_at, event_type, outcome, organization_id, "
                + "workspace_id, actor_id, authentication_method, credential_id, target_type, target_id, detail, "
                + "remote_address) select ?, current_timestamp, ?, 'SUCCEEDED', w.organization_id, w.id, ?, ?, ?, "
                + "'RUN', ?, ?, ? from workspace w where w.id = ? and exists (select 1 from agent_run r where "
                + "r.workspace_id = w.id and r.id = ?)", UUID.randomUUID(), eventType, actorId, authenticationMethod,
                credentialId, runId, detail, remoteAddress, workspaceId, runId);
        if (appended != 1) {
            throw new IllegalStateException("run " + runId + " is not a run of workspace " + workspaceId);
        }
    }

    private boolean viaFunction() {
        Boolean known = viaFunction;
        if (known == null) {
            known = dedicatedRunPlane && Boolean.TRUE.equals(jdbc.execute((ConnectionCallback<Boolean>) connection ->
                    connection.getMetaData().getDatabaseProductName().toLowerCase(Locale.ROOT).contains("postgresql")));
            viaFunction = known;
        }
        return known;
    }
}
