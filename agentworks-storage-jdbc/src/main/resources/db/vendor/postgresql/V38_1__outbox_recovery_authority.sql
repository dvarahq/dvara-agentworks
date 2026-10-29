-- Only the Control Plane dispatcher and operator surface own delivery recovery
-- state.  The Run Plane continues to receive minimized hints and cannot forge,
-- release, or hide a poison record.
grant select, insert, update on control_plane_outbox_attempt to agentworks_control_plane;
revoke all on control_plane_outbox_attempt from agentworks_run_plane;

-- A Run Plane checkpoint carries only the artifact references needed for deterministic recovery. Keep the grant
-- column-scoped: workers may identify the run's artifacts and capture their immutable digest/current safety state,
-- but cannot inspect artifact content metadata or mutate lifecycle authority.
grant select (workspace_id, id, content_digest, lifecycle_state, validation_state, run_id, created_at)
    on artifact_object to agentworks_run_plane;
