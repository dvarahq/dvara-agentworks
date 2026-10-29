-- Issue #57: PostgreSQL owns tenant isolation, immutable requests, lifecycle outcomes, and cohort ledger projection.
-- Extend the existing run fence without copying its large body: CHILD uses current-route fencing like a root while
-- retaining immutable causal lineage and the parent's trace. Abort if an upstream body no longer matches exactly.
do $$
declare definition text; changed text;
begin
    definition := pg_get_functiondef('agentworks_fence_run()'::regprocedure);
    changed := replace(definition, 'elsif new.admission = ''ROOT'' then',
            'elsif new.admission in (''ROOT'', ''CHILD'') then');
    changed := replace(changed,
            'if target_cohort is distinct from new.cohort_id or target_revision is distinct from new.revision_id then
            raise exception ''agentworks:ROUTE_TARGET_MISMATCH'' using errcode = ''check_violation'';
        end if;
    else',
            'if target_cohort is distinct from new.cohort_id or target_revision is distinct from new.revision_id then
            raise exception ''agentworks:ROUTE_TARGET_MISMATCH'' using errcode = ''check_violation'';
        end if;
        if new.admission = ''CHILD'' then
            select * into parent from agent_run r where r.workspace_id = new.workspace_id and r.id = new.parent_run_id for share;
            if not found or parent.lifecycle in (''SUCCEEDED'', ''FAILED'', ''CANCELLED'')
                    or parent.root_run_id is distinct from new.root_run_id
                    or parent.trace_id is distinct from new.trace_id then
                raise exception ''agentworks:CONTINUATION_PROVENANCE_MISMATCH child lineage differs from parent'' using errcode = ''check_violation'';
            end if;
        end if;
    else');
    changed := replace(changed, 'if tg_op = ''INSERT'' and new.admission = ''ROOT'' then',
            'if tg_op = ''INSERT'' and new.admission in (''ROOT'', ''CHILD'') then');
    if changed = definition then raise exception 'agentworks:RUN_FENCE_PATCH_MISMATCH'; end if;
    execute changed;
end $$;

alter table child_run alter column context_json type jsonb using context_json::jsonb;
alter table child_run alter column tools_json type jsonb using tools_json::jsonb;
alter table child_run alter column budget_json type jsonb using budget_json::jsonb;
alter table scheduled_wakeup alter column payload_json type jsonb using payload_json::jsonb;
alter table child_run add constraint child_run_payload_size_ck check (
    octet_length(task_text) <= 65536 and octet_length(context_json::text) <= 262144
    and octet_length(tools_json::text) <= 32768 and octet_length(budget_json::text) <= 8192);
alter table scheduled_wakeup add constraint scheduled_wakeup_payload_size_ck
    check (octet_length(payload_json::text) <= 262144);

alter table child_run enable row level security;
alter table child_run force row level security;
create policy child_run_tenant on child_run
    using (workspace_id = current_setting('agentworks.workspace_id', true)::uuid)
    with check (workspace_id = current_setting('agentworks.workspace_id', true)::uuid);
alter table scheduled_wakeup enable row level security;
alter table scheduled_wakeup force row level security;
create policy scheduled_wakeup_tenant on scheduled_wakeup
    using (workspace_id = current_setting('agentworks.workspace_id', true)::uuid)
    with check (workspace_id = current_setting('agentworks.workspace_id', true)::uuid);

create function agentworks_guard_child_run() returns trigger
    language plpgsql security definer as $$
declare parent agent_run%rowtype; child agent_run%rowtype; parent_cohort_state varchar(16);
begin
    if tg_op = 'DELETE' then
        raise exception 'agentworks:IMMUTABLE_HISTORY child relationships cannot be deleted' using errcode = 'restrict_violation';
    end if;
    select * into parent from agent_run where workspace_id = new.workspace_id and id = new.parent_run_id for share;
    select * into child from agent_run where workspace_id = new.workspace_id and id = new.child_run_id for share;
    if parent.id is null or child.id is null or parent.root_run_id <> new.root_run_id
            or parent.cohort_id <> new.parent_cohort_id
            or (child.release_id, child.deployment_id, child.revision_id, child.cohort_id)
                is distinct from (new.child_release_id, new.child_deployment_id, new.child_revision_id, new.child_cohort_id)
            or child.trace_id is distinct from parent.trace_id then
        raise exception 'agentworks:RUN_PINS_MISMATCH child provenance does not match its runs'
            using errcode = 'check_violation';
    end if;
    if tg_op = 'INSERT' then
        select state into parent_cohort_state from runtime_cohort
            where workspace_id = new.workspace_id and id = new.parent_cohort_id for share;
        if parent.lifecycle <> 'BLOCKED' or parent.block_reason <> 'CHILD_RUN' or new.status <> 'STARTING' then
            raise exception 'agentworks:CHILD_NOT_ADMISSIBLE' using errcode = 'check_violation';
        end if;
        -- A draining cohort may finish provenance-bearing work already executing in it. Suspension and revocation
        -- are hard safe-boundary fences, and pending/retired cohorts cannot acquire a new blocker.
        if parent_cohort_state not in ('ROUTABLE', 'DRAINING') then
            raise exception 'agentworks:CHILD_NOT_ADMISSIBLE parent cohort is %', parent_cohort_state
                using errcode = 'check_violation';
        end if;
        return new;
    end if;
    if (new.workspace_id, new.id, new.parent_run_id, new.root_run_id, new.child_run_id,
            new.parent_cohort_id, new.child_release_id, new.child_deployment_id, new.child_revision_id,
            new.child_cohort_id, new.depth, new.idempotency_key, new.request_digest, new.task_text,
            new.context_json, new.tools_json, new.budget_json, new.budget_tokens, new.budget_cost_usd,
            new.failure_policy, new.created_at)
       is distinct from
           (old.workspace_id, old.id, old.parent_run_id, old.root_run_id, old.child_run_id,
            old.parent_cohort_id, old.child_release_id, old.child_deployment_id, old.child_revision_id,
            old.child_cohort_id, old.depth, old.idempotency_key, old.request_digest, old.task_text,
            old.context_json, old.tools_json, old.budget_json, old.budget_tokens, old.budget_cost_usd,
            old.failure_policy, old.created_at) then
        raise exception 'agentworks:IMMUTABLE_IDENTITY child request is immutable' using errcode = 'restrict_violation';
    end if;
    if old.status in ('SUCCEEDED', 'FAILED', 'CANCELLED') then
        raise exception 'agentworks:CHILD_ALREADY_TERMINAL' using errcode = 'check_violation';
    end if;
    if not ((old.status = 'STARTING' and new.status = 'RUNNING')
        or (new.status in ('SUCCEEDED', 'FAILED', 'CANCELLED'))) then
        raise exception 'agentworks:INVALID_CHILD_TRANSITION' using errcode = 'check_violation';
    end if;
    return new;
end;
$$;
create trigger child_run_guard before insert or update or delete on child_run
    for each row execute function agentworks_guard_child_run();
create trigger child_run_no_truncate before truncate on child_run
    for each statement execute function agentworks_reject_history_mutation();

create function agentworks_guard_scheduled_wakeup() returns trigger
    language plpgsql security definer as $$
declare run agent_run%rowtype; cohort_state varchar(16); cohort_version bigint;
begin
    if tg_op = 'DELETE' then
        raise exception 'agentworks:IMMUTABLE_HISTORY wakeups cannot be deleted' using errcode = 'restrict_violation';
    end if;
    select * into run from agent_run where workspace_id = new.workspace_id and id = new.run_id for share;
    select state, transition_version into cohort_state, cohort_version from runtime_cohort
        where workspace_id = new.workspace_id and id = new.cohort_id for share;
    if run.id is null or (run.release_id, run.deployment_id, run.revision_id, run.cohort_id)
            is distinct from (new.release_id, new.deployment_id, new.revision_id, new.cohort_id) then
        raise exception 'agentworks:RUN_PINS_MISMATCH wakeup pins do not match its run' using errcode = 'check_violation';
    end if;
    if tg_op = 'INSERT' then
        if run.lifecycle <> 'BLOCKED' or run.block_reason <> 'SCHEDULED_WAKEUP'
                or new.status <> 'SCHEDULED' or cohort_state in ('PENDING', 'RETIRED', 'REVOKED', 'SUSPENDED') then
            raise exception 'agentworks:WAKEUP_NOT_ADMISSIBLE %', cohort_state using errcode = 'check_violation';
        end if;
        if cohort_version <> new.cohort_transition_version then
            raise exception 'agentworks:COHORT_STATE_CHANGED' using errcode = 'serialization_failure';
        end if;
        return new;
    end if;
    if (new.workspace_id, new.id, new.session_id, new.run_id, new.release_id, new.deployment_id,
            new.revision_id, new.cohort_id, new.cohort_transition_version, new.idempotency_key,
            new.request_digest, new.correlation_id, new.due_at, new.payload_json, new.created_at)
       is distinct from
           (old.workspace_id, old.id, old.session_id, old.run_id, old.release_id, old.deployment_id,
            old.revision_id, old.cohort_id, old.cohort_transition_version, old.idempotency_key,
            old.request_digest, old.correlation_id, old.due_at, old.payload_json, old.created_at) then
        raise exception 'agentworks:IMMUTABLE_IDENTITY wakeup request is immutable' using errcode = 'restrict_violation';
    end if;
    if old.status <> 'SCHEDULED' then
        raise exception 'agentworks:WAKEUP_ALREADY_TERMINAL' using errcode = 'check_violation';
    end if;
    if new.status = 'DELIVERED' and new.due_at > clock_timestamp() then
        raise exception 'agentworks:WAKEUP_NOT_DUE' using errcode = 'check_violation';
    end if;
    return new;
end;
$$;
create trigger scheduled_wakeup_guard before insert or update or delete on scheduled_wakeup
    for each row execute function agentworks_guard_scheduled_wakeup();
create trigger scheduled_wakeup_no_truncate before truncate on scheduled_wakeup
    for each statement execute function agentworks_reject_history_mutation();

create function agentworks_sync_child_work() returns trigger language plpgsql security definer as $$
begin
    if tg_op = 'INSERT' then
        insert into cohort_work_item(workspace_id, id, cohort_id, run_id, category, subject_id, state,
                created_at, state_changed_at, terminal_at)
        values (new.workspace_id, gen_random_uuid(), new.parent_cohort_id, new.parent_run_id, 'CHILD_WORK',
                new.id, 'PENDING', new.created_at, new.created_at, null);
    elsif new.status = 'RUNNING' then
        update cohort_work_item set state = 'ACTIVE', state_changed_at = clock_timestamp()
            where workspace_id = new.workspace_id and category = 'CHILD_WORK' and subject_id = new.id and state <> 'TERMINAL';
    elsif new.status in ('SUCCEEDED', 'FAILED', 'CANCELLED') then
        update cohort_work_item set state = 'TERMINAL', state_changed_at = new.terminal_at, terminal_at = new.terminal_at
            where workspace_id = new.workspace_id and category = 'CHILD_WORK' and subject_id = new.id and state <> 'TERMINAL';
    end if;
    if not found and tg_op = 'UPDATE' then raise exception 'agentworks:CHILD_LEDGER_MISSING' using errcode = 'check_violation'; end if;
    return new;
end; $$;
create trigger child_run_work after insert or update on child_run for each row execute function agentworks_sync_child_work();

create function agentworks_sync_wakeup_work() returns trigger language plpgsql security definer as $$
begin
    if tg_op = 'INSERT' then
        insert into cohort_work_item(workspace_id, id, cohort_id, run_id, category, subject_id, state,
                created_at, state_changed_at, terminal_at)
        values (new.workspace_id, gen_random_uuid(), new.cohort_id, new.run_id, 'WAKEUP', new.id,
                'PENDING', new.created_at, new.created_at, null);
    elsif new.status in ('DELIVERED', 'CANCELLED') then
        update cohort_work_item set state = 'TERMINAL', state_changed_at = clock_timestamp(), terminal_at = clock_timestamp()
            where workspace_id = new.workspace_id and category = 'WAKEUP' and subject_id = new.id and state <> 'TERMINAL';
    end if;
    if not found and tg_op = 'UPDATE' then raise exception 'agentworks:WAKEUP_LEDGER_MISSING' using errcode = 'check_violation'; end if;
    return new;
end; $$;
create trigger scheduled_wakeup_work after insert or update on scheduled_wakeup
    for each row execute function agentworks_sync_wakeup_work();

grant select, insert, update on child_run, scheduled_wakeup to agentworks_run_plane;
grant select on child_run, scheduled_wakeup to agentworks_control_plane;
grant select, insert, update on cohort_work_item to agentworks_fence_owner;
grant select on agent_run, runtime_cohort to agentworks_fence_owner;
do $$
declare guard text;
begin
    execute format('grant create on schema %I to agentworks_fence_owner', current_schema());
    foreach guard in array array['agentworks_guard_child_run', 'agentworks_guard_scheduled_wakeup',
            'agentworks_sync_child_work', 'agentworks_sync_wakeup_work'] loop
        execute format('alter function %I() set search_path = %I, pg_temp', guard, current_schema());
        execute format('alter function %I() owner to agentworks_fence_owner', guard);
        execute format('revoke execute on function %I() from public', guard);
    end loop;
    execute format('revoke create on schema %I from agentworks_fence_owner', current_schema());
end $$;
