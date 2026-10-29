-- Issue #40: allocating or resuming a sandbox is effectful work, so SUSPENDED cohorts reject SANDBOX inserts and
-- PENDING-to-ACTIVE transitions. Cleanup terminalization remains permitted in every cohort state.
do $$
begin
    execute format('grant create on schema %I to agentworks_fence_owner', current_schema());
end;
$$;
set role agentworks_fence_owner;

create or replace function agentworks_fence_work_item() returns trigger
    language plpgsql security definer as $$
declare
    cohort_state varchar(16);
    claim boolean;
begin
    if tg_op = 'DELETE' then
        raise exception 'agentworks:IMMUTABLE_HISTORY work items cannot be deleted' using errcode = 'restrict_violation';
    end if;
    if tg_op = 'UPDATE' then
        if (new.workspace_id, new.id, new.cohort_id, new.run_id, new.category, new.subject_id, new.created_at)
                is distinct from (old.workspace_id, old.id, old.cohort_id, old.run_id, old.category, old.subject_id,
                old.created_at) then
            raise exception 'agentworks:IMMUTABLE_IDENTITY work item identity is immutable'
                using errcode = 'restrict_violation';
        end if;
        if old.state = 'TERMINAL' then
            raise exception 'agentworks:TERMINAL_WORK terminal work never resumes' using errcode = 'check_violation';
        end if;
    end if;
    if new.state = 'TERMINAL' then
        if new.category = 'OPERATION' and exists (select 1 from agent_run_effect f
                where f.workspace_id = new.workspace_id and f.id = new.subject_id and f.state = 'PENDING') then
            raise exception 'agentworks:EFFECT_IN_DOUBT an operation ends only after its effect has an outcome'
                using errcode = 'check_violation';
        end if;
        return new;
    end if;
    claim := new.category = 'CLAIM'
        or (new.category in ('OPERATION', 'SANDBOX') and tg_op = 'INSERT')
        or (tg_op = 'UPDATE' and old.state = 'PENDING' and new.state = 'ACTIVE');
    select c.state into cohort_state from runtime_cohort c where c.workspace_id = new.workspace_id and c.id = new.cohort_id;
    if cohort_state = 'QUIESCENT' then
        select c.state into cohort_state from runtime_cohort c
            where c.workspace_id = new.workspace_id and c.id = new.cohort_id for no key update;
    else
        select c.state into cohort_state from runtime_cohort c
            where c.workspace_id = new.workspace_id and c.id = new.cohort_id for share;
        if cohort_state = 'QUIESCENT' then
            raise exception 'agentworks:COHORT_STATE_CHANGED' using errcode = 'serialization_failure';
        end if;
    end if;
    if cohort_state in ('RETIRED', 'REVOKED', 'PENDING') or (cohort_state = 'SUSPENDED' and claim) then
        raise exception 'agentworks:COHORT_NOT_ADMITTING %', cohort_state using errcode = 'check_violation';
    end if;
    if cohort_state = 'QUIESCENT' then
        update runtime_cohort set state = 'DRAINING', transition_version = transition_version + 1,
                quiescent_since = null, state_changed_at = current_timestamp
            where workspace_id = new.workspace_id and id = new.cohort_id;
        insert into cohort_transition(workspace_id, id, cohort_id, transition_version, from_state, to_state, cause,
                actor, reason, occurred_at)
            select new.workspace_id, gen_random_uuid(), c.id, c.transition_version, 'QUIESCENT', 'DRAINING',
                   'LATE_WORK', 'system:cohort-fence', 'resumable work observed after quiescence', current_timestamp
            from runtime_cohort c where c.workspace_id = new.workspace_id and c.id = new.cohort_id;
    end if;
    return new;
end;
$$;

do $$
begin
    execute format('alter function %I() set search_path = %I, pg_temp',
        'agentworks_fence_work_item', current_schema());
end;
$$;

reset role;
do $$
begin
    execute format('revoke create on schema %I from agentworks_fence_owner', current_schema());
end;
$$;
