-- Issue #49: tenant enforcement and least privilege for memory content and provenance.
alter table agent_memory_scope_revision enable row level security;
alter table agent_memory_scope_revision force row level security;
create policy agent_memory_scope_revision_tenant on agent_memory_scope_revision
    using (workspace_id = current_setting('agentworks.workspace_id', true)::uuid)
    with check (workspace_id = current_setting('agentworks.workspace_id', true)::uuid);

alter table agent_memory_record enable row level security;
alter table agent_memory_record force row level security;
create policy agent_memory_record_tenant on agent_memory_record
    using (workspace_id = current_setting('agentworks.workspace_id', true)::uuid)
    with check (workspace_id = current_setting('agentworks.workspace_id', true)::uuid);

alter table agent_memory_provenance enable row level security;
alter table agent_memory_provenance force row level security;
create policy agent_memory_provenance_tenant on agent_memory_provenance
    using (workspace_id = current_setting('agentworks.workspace_id', true)::uuid)
    with check (workspace_id = current_setting('agentworks.workspace_id', true)::uuid);

grant select, insert, update on agent_memory_scope_revision to agentworks_run_plane;
grant select, insert, delete on agent_memory_record to agentworks_run_plane;
grant select, insert on agent_memory_provenance to agentworks_run_plane;
grant usage, select on sequence agent_memory_provenance_sequence_seq to agentworks_run_plane;
