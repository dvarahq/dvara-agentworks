-- Issue #194 PostgreSQL authority for atomic run-control audit records.
-- * An operator's pause request and its withdrawal by a resume (#183) change only Run Plane state (the run and its
--   agent_run_pause_request row), and no run event can record them while a worker holds the lease. They are recorded as
--   auth_audit_event rows, written in the run engine's own transaction on the Run Plane connection, so a change and its
--   record commit or roll back together.
-- * The Run Plane gains no privilege on auth_audit_event (V7_1 stands: it cannot read, insert, change, or remove a row).
--   It appends through agentworks_append_run_control_audit, a SECURITY DEFINER function owned by the NOLOGIN fence
--   owner, which only the Run Plane may execute. The function:
--     - accepts only the pairs RUN_PAUSE_REQUESTED with a pause outcome (PAUSED, ALREADY_PAUSED, REQUESTED, REPEATED:
--       the names of DurableRunEngine.PauseResult.Outcome) and RUN_PAUSE_WITHDRAWN with WITHDRAWN;
--     - requires the target run to exist in the given workspace;
--     - derives organization_id from that workspace, whatever the caller believes it to be;
--     - stamps occurred_at in database time, outcome SUCCEEDED, and target type RUN.
--   The actor columns (actor, authentication method, credential) and the remote address are the caller's: a compromised
--   Run Plane login could still name another actor of the workspace, the same trust the Run Plane already has for the
--   run events it writes. Row-level security is #59.
grant select on workspace to agentworks_fence_owner;
grant insert on auth_audit_event to agentworks_fence_owner;

create function agentworks_append_run_control_audit(p_workspace_id uuid, p_run_id uuid, p_event_type varchar,
        p_detail varchar, p_actor_id uuid, p_authentication_method varchar, p_credential_id uuid,
        p_remote_address varchar) returns uuid
    language plpgsql security definer as $$
declare
    organization uuid;
    appended uuid := gen_random_uuid();
begin
    if not ((p_event_type = 'RUN_PAUSE_WITHDRAWN' and p_detail = 'WITHDRAWN')
            or (p_event_type = 'RUN_PAUSE_REQUESTED'
                and p_detail in ('PAUSED', 'ALREADY_PAUSED', 'REQUESTED', 'REPEATED'))) then
        raise exception 'agentworks:UNAUTHORIZED_AUDIT_WRITER % with detail % is not a run-control record',
            p_event_type, p_detail using errcode = 'insufficient_privilege';
    end if;
    select w.organization_id into organization from workspace w where w.id = p_workspace_id;
    if not found or not exists (select 1 from agent_run r where r.workspace_id = p_workspace_id and r.id = p_run_id) then
        raise exception 'agentworks:UNAUTHORIZED_AUDIT_WRITER the run is not a run of the workspace'
            using errcode = 'insufficient_privilege';
    end if;
    insert into auth_audit_event(id, occurred_at, event_type, outcome, organization_id, workspace_id, actor_id,
            authentication_method, credential_id, target_type, target_id, detail, remote_address)
        values (appended, now(), p_event_type, 'SUCCEEDED', organization, p_workspace_id, p_actor_id,
            p_authentication_method, p_credential_id, 'RUN', p_run_id, p_detail, p_remote_address);
    return appended;
end;
$$;

-- Owned by the fence owner like every other guard (V4_1, V5_1, V9_1, V15_1): pinned search path with pg_temp last, no
-- PUBLIC execute; only the Run Plane executes it.
do $$
declare
    signature text := 'agentworks_append_run_control_audit(uuid, uuid, varchar, varchar, uuid, varchar, uuid, varchar)';
begin
    execute format('grant create on schema %I to agentworks_fence_owner', current_schema());
    execute format('alter function %s set search_path = %I, pg_temp', signature, current_schema());
    execute format('alter function %s owner to agentworks_fence_owner', signature);
    execute format('revoke execute on function %s from public', signature);
    execute format('grant execute on function %s to agentworks_run_plane', signature);
    execute format('revoke create on schema %I from agentworks_fence_owner', current_schema());
end;
$$;
