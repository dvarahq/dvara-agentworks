-- Issue #42: PostgreSQL is authoritative for interaction pins, database-time deadlines, cohort admission, immutable
-- identity, one terminal outcome, and the matching INTERACTION resumable-work ledger row.
alter table human_interaction alter column prompt_json type jsonb using prompt_json::jsonb;
alter table human_interaction alter column response_json type jsonb using response_json::jsonb;
alter table human_interaction add constraint human_interaction_payload_size_ck check (
    octet_length(prompt_json::text) <= 262144
    and (response_json is null or octet_length(response_json::text) <= 262144));

create function agentworks_guard_human_interaction() returns trigger
    language plpgsql security definer as $$
declare
    run_row agent_run%rowtype;
    cohort_state varchar(16);
    cohort_version bigint;
begin
    if tg_op = 'DELETE' then
        raise exception 'agentworks:IMMUTABLE_HISTORY interactions cannot be deleted'
            using errcode = 'restrict_violation';
    end if;
    if tg_op = 'INSERT' then
        if new.status <> 'OPEN' then
            raise exception 'agentworks:INTERACTION_ALREADY_TERMINAL' using errcode = 'check_violation';
        end if;
        select c.state, c.transition_version into cohort_state, cohort_version from runtime_cohort c
            where c.workspace_id = new.workspace_id and c.id = new.cohort_id for share;
        if cohort_state in ('PENDING', 'RETIRED', 'REVOKED') then
            raise exception 'agentworks:COHORT_NOT_ADMITTING %', cohort_state using errcode = 'check_violation';
        end if;
        if cohort_version <> new.cohort_transition_version then
            raise exception 'agentworks:COHORT_STATE_CHANGED' using errcode = 'serialization_failure';
        end if;
        select r.* into run_row from agent_run r
            where r.workspace_id = new.workspace_id and r.id = new.run_id for share;
        if not found or (run_row.release_id, run_row.deployment_id, run_row.revision_id, run_row.cohort_id)
                is distinct from (new.release_id, new.deployment_id, new.revision_id, new.cohort_id) then
            raise exception 'agentworks:RUN_PINS_MISMATCH' using errcode = 'check_violation';
        end if;
        return new;
    end if;

    if (new.workspace_id, new.id, new.run_id, new.release_id, new.deployment_id, new.revision_id, new.cohort_id,
            new.cohort_transition_version, new.correlation_id, new.idempotency_key, new.request_digest, new.kind,
            new.prompt_json, new.assignment_level, new.assigned_principal_id, new.created_at, new.deadline)
            is distinct from
            (old.workspace_id, old.id, old.run_id, old.release_id, old.deployment_id, old.revision_id, old.cohort_id,
            old.cohort_transition_version, old.correlation_id, old.idempotency_key, old.request_digest, old.kind,
            old.prompt_json, old.assignment_level, old.assigned_principal_id, old.created_at, old.deadline) then
        raise exception 'agentworks:IMMUTABLE_IDENTITY interaction request is immutable'
            using errcode = 'restrict_violation';
    end if;
    if old.status <> 'OPEN' or new.status = 'OPEN' then
        raise exception 'agentworks:INTERACTION_ALREADY_TERMINAL' using errcode = 'check_violation';
    end if;
    select c.state into cohort_state from runtime_cohort c
        where c.workspace_id = new.workspace_id and c.id = new.cohort_id for share;
    if new.status = 'RESPONDED' then
        if clock_timestamp() >= old.deadline then
            raise exception 'agentworks:INTERACTION_DEADLINE_PASSED' using errcode = 'check_violation';
        end if;
        if cohort_state in ('PENDING', 'RETIRED', 'REVOKED') then
            raise exception 'agentworks:COHORT_NOT_ADMITTING %', cohort_state using errcode = 'check_violation';
        end if;
    elsif new.status = 'EXPIRED' and clock_timestamp() < old.deadline then
        raise exception 'agentworks:INTERACTION_DEADLINE_NOT_REACHED' using errcode = 'check_violation';
    end if;
    return new;
end;
$$;
create trigger human_interaction_guard before insert or update or delete on human_interaction
    for each row execute function agentworks_guard_human_interaction();
create trigger human_interaction_no_truncate before truncate on human_interaction
    for each statement execute function agentworks_reject_history_mutation();

create function agentworks_sync_human_interaction_work() returns trigger
    language plpgsql security definer as $$
begin
    if tg_op = 'INSERT' then
        insert into cohort_work_item(workspace_id, id, cohort_id, run_id, category, subject_id, state,
                created_at, state_changed_at, terminal_at)
            values (new.workspace_id, gen_random_uuid(), new.cohort_id, new.run_id, 'INTERACTION', new.id, 'PENDING',
                new.created_at, new.created_at, null);
    elsif new.status <> 'OPEN' then
        update cohort_work_item set state = 'TERMINAL', state_changed_at = new.resolved_at,
                terminal_at = new.resolved_at
            where workspace_id = new.workspace_id and category = 'INTERACTION' and subject_id = new.id
                and state <> 'TERMINAL';
        if not found then
            raise exception 'agentworks:INTERACTION_LEDGER_MISSING' using errcode = 'check_violation';
        end if;
    end if;
    return new;
end;
$$;
create trigger human_interaction_work after insert or update on human_interaction
    for each row execute function agentworks_sync_human_interaction_work();

grant select, insert, update on cohort_work_item to agentworks_fence_owner;
grant select on agent_run, runtime_cohort to agentworks_fence_owner;
do $$
declare
    guard text;
begin
    execute format('grant create on schema %I to agentworks_fence_owner', current_schema());
    foreach guard in array array['agentworks_guard_human_interaction', 'agentworks_sync_human_interaction_work'] loop
        execute format('alter function %I() set search_path = %I, pg_temp', guard, current_schema());
        execute format('alter function %I() owner to agentworks_fence_owner', guard);
        execute format('revoke execute on function %I() from public', guard);
    end loop;
    execute format('revoke create on schema %I from agentworks_fence_owner', current_schema());
end $$;

grant select, insert on human_interaction to agentworks_run_plane;
grant select on human_interaction to agentworks_control_plane;
grant update (status, response_json, resolved_by, resolved_at) on human_interaction
    to agentworks_run_plane, agentworks_control_plane;
