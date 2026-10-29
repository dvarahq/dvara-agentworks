-- Signed evidence is Control Plane authority. Run Plane reads the release manifest through its existing narrow path;
-- it never receives signing-key access and cannot create, replace, or delete evidence.
alter table release_evidence_bundle enable row level security;
alter table release_evidence_bundle force row level security;
create policy release_evidence_bundle_tenant on release_evidence_bundle
    using (workspace_id = current_setting('agentworks.workspace_id', true)::uuid)
    with check (workspace_id = current_setting('agentworks.workspace_id', true)::uuid);
create trigger release_evidence_bundle_immutable before update or delete on release_evidence_bundle
    for each row execute function agentworks_reject_history_mutation();

grant select, insert on release_evidence_bundle to agentworks_control_plane;
revoke all on release_evidence_bundle from agentworks_run_plane;
