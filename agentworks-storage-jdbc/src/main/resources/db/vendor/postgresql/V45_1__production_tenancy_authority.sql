-- Issue #59: production PostgreSQL tenant authority and least-privilege role hardening.
--
-- Authentication tables are deliberately not included in the generic policy. A credential must be resolved before
-- its workspace is known; those tables remain protected by unguessable digests, explicit predicates, and the
-- control-plane grants in V7_1. Every control/run table that carries workspace_id is protected here.

create function agentworks_current_workspace() returns uuid
    language sql stable parallel safe
    return nullif(current_setting('agentworks.workspace_id', true), '')::uuid;

revoke all on function agentworks_current_workspace() from public;
grant execute on function agentworks_current_workspace()
    to agentworks_control_plane, agentworks_run_plane, agentworks_fence_owner, agentworks_run_control_auditor;

-- A worker can discover only the workspace key needed to establish its transaction-local RLS context after a
-- run-specific claim failure. The function does not expose the run row and its dedicated NOLOGIN owner has only the
-- two columns needed for this lookup.
create function agentworks_run_workspace(p_run_id uuid) returns uuid
    language sql stable security definer
    return (select workspace_id from agent_run where id = p_run_id);

-- The shared worker pool asks only which workspace currently has the next compatible candidate. It then establishes
-- that workspace's RLS context and repeats the complete claim query with its normal row lock and state checks.
grant select (lifecycle, lease_until, manifest_version, executor_type, created_at, cohort_id)
    on agent_run to agentworks_run_control_auditor;
grant select (workspace_id, id, state) on runtime_cohort to agentworks_run_control_auditor;
grant select (workspace_id, last_claimed_at) on run_admission_fairness to agentworks_run_control_auditor;
grant select (workspace_id, run_id, compatibility, not_before, closed_at)
    on agent_run_claim_quarantine to agentworks_run_control_auditor;

create function agentworks_candidate_workspace(p_manifest_versions integer[], p_executor_types varchar[],
        p_excluded_run_ids uuid[], p_compatibility varchar) returns uuid
    language sql volatile security definer
    return (
        select r.workspace_id
          from agent_run r
          left join run_admission_fairness f on f.workspace_id = r.workspace_id
         where (r.lifecycle = 'QUEUED' or (r.lifecycle = 'ACTIVE' and r.lease_until <= clock_timestamp()))
           and exists (select 1 from runtime_cohort c where c.workspace_id = r.workspace_id and c.id = r.cohort_id
                and c.state in ('ROUTABLE', 'DRAINING'))
           and r.manifest_version = any (p_manifest_versions)
           and (cardinality(p_executor_types) = 0 or r.executor_type = any (p_executor_types))
           and (cardinality(p_excluded_run_ids) = 0 or r.id <> all (p_excluded_run_ids))
           and not exists (select 1 from agent_run_claim_quarantine q
                where q.workspace_id = r.workspace_id and q.run_id = r.id
                  and q.compatibility = p_compatibility and q.closed_at is null
                  and (q.not_before is null or q.not_before > clock_timestamp()))
         order by f.last_claimed_at nulls first, r.created_at, r.id
         fetch first 1 row only
    );

do $$
declare
    signature text := 'agentworks_run_workspace(uuid)';
    candidate_signature text := 'agentworks_candidate_workspace(integer[], varchar[], uuid[], varchar)';
    self_granted boolean := false;
begin
    if current_setting('server_version_num')::integer >= 160000 then
        if not pg_has_role(current_user, 'agentworks_run_control_auditor', 'SET') then
            execute format('grant agentworks_run_control_auditor to %I with set true', current_user);
            self_granted := true;
        end if;
    elsif not pg_has_role(current_user, 'agentworks_run_control_auditor', 'MEMBER') then
        execute format('grant agentworks_run_control_auditor to %I', current_user);
        self_granted := true;
    end if;
    execute format('grant create on schema %I to agentworks_run_control_auditor', current_schema());
    execute format('alter function %s set search_path = %I, pg_temp', signature, current_schema());
    execute format('alter function %s owner to agentworks_run_control_auditor', signature);
    execute format('revoke execute on function %s from public', signature);
    execute format('grant execute on function %s to agentworks_run_plane', signature);
    execute format('alter function %s set search_path = %I, pg_temp', candidate_signature, current_schema());
    execute format('alter function %s owner to agentworks_run_control_auditor', candidate_signature);
    execute format('revoke execute on function %s from public', candidate_signature);
    execute format('grant execute on function %s to agentworks_run_plane', candidate_signature);
    execute format('revoke create on schema %I from agentworks_run_control_auditor', current_schema());
    if self_granted then
        execute format('revoke agentworks_run_control_auditor from %I', current_user);
    end if;
end;
$$;

do $$
declare
    tenant_table record;
    old_policy record;
    policy_name text;
begin
    for tenant_table in
        select c.table_schema, c.table_name
          from information_schema.columns c
          join information_schema.tables t using (table_schema, table_name)
         where c.table_schema = current_schema()
           and c.column_name = 'workspace_id'
           and t.table_type = 'BASE TABLE'
           and c.table_name not in ('workspace_membership', 'auth_session', 'api_token', 'auth_audit_event')
         order by c.table_name
    loop
        -- The migration owner remains the maintenance/break-glass path. Production servers use the NOLOGIN plane
        -- roles through dedicated LOGIN members and therefore cannot bypass this policy.
        execute format('alter table %I.%I enable row level security',
            tenant_table.table_schema, tenant_table.table_name);
        execute format('alter table %I.%I no force row level security',
            tenant_table.table_schema, tenant_table.table_name);

        for old_policy in
            select policyname from pg_policies
             where schemaname = tenant_table.table_schema and tablename = tenant_table.table_name
        loop
            execute format('drop policy %I on %I.%I', old_policy.policyname,
                tenant_table.table_schema, tenant_table.table_name);
        end loop;

        policy_name := tenant_table.table_name || '_workspace_tenant';
        execute format('create policy %I on %I.%I using '
            || '(workspace_id = agentworks_current_workspace() or current_user in '
            || '(''agentworks_fence_owner'', ''agentworks_run_control_auditor'')) with check '
            || '(workspace_id = agentworks_current_workspace() or current_user in '
            || '(''agentworks_fence_owner'', ''agentworks_run_control_auditor''))', policy_name,
            tenant_table.table_schema, tenant_table.table_name);
    end loop;
end;
$$;

-- Group roles are never connection identities. Fail closed instead of attempting to remove elevated attributes:
-- PostgreSQL correctly reserves changing SUPERUSER/REPLICATION/BYPASSRLS for a superuser, while supported Flyway
-- installations may use a non-superuser CREATEROLE owner.
do $$
declare
    unsafe_roles text;
begin
    select string_agg(rolname, ', ' order by rolname) into unsafe_roles
      from pg_roles
     where rolname in ('agentworks_control_plane', 'agentworks_run_plane', 'agentworks_fence_owner',
            'agentworks_run_control_auditor')
       and (rolcanlogin or rolsuper or rolcreatedb or rolcreaterole or rolreplication or rolbypassrls);
    if unsafe_roles is not null then
        raise exception 'agentworks:UNSAFE_DATABASE_ROLE %', unsafe_roles using errcode = 'insufficient_privilege';
    end if;
end;
$$;

do $$
begin
    execute format('revoke create on schema %I from agentworks_control_plane, agentworks_run_plane', current_schema());
end;
$$;

-- Leading tenant indexes bound common lifecycle, durable-run, outbox, and checkpoint scans. IF NOT EXISTS keeps the
-- migration safe for installations that added an equivalent index during an earlier production tuning exercise.
create index if not exists idx_agent_run_event_workspace_run_sequence
    on agent_run_event(workspace_id, run_id, sequence);
create index if not exists idx_agent_run_checkpoint_workspace_run_sequence
    on agent_run_checkpoint(workspace_id, run_id, sequence desc);
create index if not exists idx_control_plane_outbox_workspace_sequence
    on control_plane_outbox(workspace_id, sequence_number);
create index if not exists idx_control_plane_delivery_workspace_outbox
    on control_plane_outbox_delivery(workspace_id, outbox_id);
create index if not exists idx_agent_run_workspace_lifecycle_created
    on agent_run(workspace_id, lifecycle, created_at desc, id);
