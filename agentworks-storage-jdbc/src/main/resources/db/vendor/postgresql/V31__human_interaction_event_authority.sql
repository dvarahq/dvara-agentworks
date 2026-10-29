-- Issue #42: interaction requests remain lease-authored; terminal interaction outcomes are control events written
-- immediately before RUN_RESUMED, after the matching durable interaction reached its one terminal state.
do $$
begin
    execute format('grant create on schema %I to agentworks_fence_owner', current_schema());
end;
$$;
grant select on human_interaction to agentworks_fence_owner;
set role agentworks_fence_owner;

create or replace function agentworks_guard_run_event() returns trigger
    language plpgsql security definer as $$
declare
    run agent_run%rowtype;
    latest bigint;
    cohort_state varchar(16);
begin
    select * into run from agent_run r where r.workspace_id = new.workspace_id and r.id = new.run_id for no key update;
    if not found then
        raise exception 'agentworks:RUN_NOT_FOUND' using errcode = 'check_violation';
    end if;
    select coalesce(max(e.sequence), 0) into latest from agent_run_event e
        where e.workspace_id = new.workspace_id and e.run_id = new.run_id;
    if new.sequence <> latest + 1 then
        raise exception 'agentworks:EVENT_SEQUENCE expected % but got %', latest + 1, new.sequence
            using errcode = 'check_violation';
    end if;
    if (new.release_id, new.deployment_id, new.revision_id, new.cohort_id, new.trace_id)
            is distinct from (run.release_id, run.deployment_id, run.revision_id, run.cohort_id, run.trace_id) then
        raise exception 'agentworks:EVENT_CORRELATION_MISMATCH' using errcode = 'check_violation';
    end if;
    if new.lease_owner is not null then
        if run.lease_owner is distinct from new.lease_owner or run.lease_fence <> new.lease_fence
                or run.lease_until <= clock_timestamp() then
            raise exception 'agentworks:STALE_LEASE' using errcode = 'check_violation';
        end if;
    elsif new.lease_fence <> run.lease_fence or run.lease_owner is not null or not (
            (new.event_type = 'RUN_CREATED' and run.lifecycle = 'CREATED' and new.sequence = 1)
            or (new.event_type in ('RUN_QUEUED', 'RUN_RESUMED') and run.lifecycle = 'QUEUED')
            or (new.event_type = 'RUN_PAUSED' and run.lifecycle = 'PAUSED' and exists (select 1 from agent_run_event p
                where p.workspace_id = new.workspace_id and p.run_id = new.run_id and p.sequence = new.sequence - 1
                  and (p.event_type in ('RUN_QUEUED', 'RUN_RESUMED') or new.lease_fence = p.lease_fence + 1)))
            or (new.event_type = 'RUN_CANCELLED' and run.lifecycle = 'CANCELLED')
            or (new.event_type = 'RUN_FAILED' and run.lifecycle = 'FAILED')
            or (new.event_type = 'OPERATION_RESOLVED' and run.lifecycle in ('SUCCEEDED', 'FAILED', 'CANCELLED'))
            or (new.event_type in ('HUMAN_INTERACTION_RESPONDED', 'HUMAN_INTERACTION_EXPIRED',
                    'HUMAN_INTERACTION_CANCELLED', 'AUTHORIZATION_GRANTED', 'AUTHORIZATION_FAILED',
                    'AUTHORIZATION_REVOKED') and run.lifecycle = 'QUEUED' and exists (
                select 1 from human_interaction h join agent_run_event p
                    on p.workspace_id = h.workspace_id and p.run_id = h.run_id
                        and p.sequence = new.sequence - 1
                where h.workspace_id = new.workspace_id and h.run_id = new.run_id
                    and h.id::text = new.payload_json->>'interactionId'
                    and h.resolved_by = new.actor
                    and p.event_type = 'RUN_BLOCKED'
                    and p.payload_json->'detail'->>'interactionId' = h.id::text
                    and ((new.event_type = 'HUMAN_INTERACTION_RESPONDED' and h.status = 'RESPONDED'
                            and h.kind <> 'AUTHORIZATION')
                        or (new.event_type = 'HUMAN_INTERACTION_EXPIRED' and h.status = 'EXPIRED')
                        or (new.event_type = 'HUMAN_INTERACTION_CANCELLED' and h.status = 'CANCELLED')
                        or (new.event_type = 'AUTHORIZATION_GRANTED' and h.status = 'RESPONDED'
                            and h.kind = 'AUTHORIZATION' and (h.response_json->>'granted')::boolean)
                        or (new.event_type = 'AUTHORIZATION_FAILED' and h.status = 'RESPONDED'
                            and h.kind = 'AUTHORIZATION' and not (h.response_json->>'granted')::boolean)
                        or (new.event_type = 'AUTHORIZATION_REVOKED' and h.status = 'REVOKED'
                            and h.kind = 'AUTHORIZATION'))))) then
        -- Control events are written without a lease only after their summary or interaction update, in the state
        -- they record; never while a worker holds the run.
        raise exception 'agentworks:UNAUTHORIZED_EVENT_WRITER % needs a lease', new.event_type
            using errcode = 'check_violation';
    end if;
    -- Terminalization is always permitted evidence; any other event of a live run needs an executing cohort.
    if run.lifecycle not in ('SUCCEEDED', 'FAILED', 'CANCELLED')
            and new.event_type not in ('RUN_SUCCEEDED', 'RUN_FAILED', 'RUN_CANCELLED') then
        select c.state into cohort_state from runtime_cohort c
            where c.workspace_id = new.workspace_id and c.id = new.cohort_id for share;
        if cohort_state in ('RETIRED', 'REVOKED', 'PENDING') then
            raise exception 'agentworks:COHORT_NOT_ADMITTING %', cohort_state using errcode = 'check_violation';
        end if;
    end if;
    return new;
end;
$$;

do $$
begin
    execute format('alter function %I() set search_path = %I, pg_temp',
        'agentworks_guard_run_event', current_schema());
end;
$$;

reset role;
do $$
begin
    execute format('revoke create on schema %I from agentworks_fence_owner', current_schema());
end;
$$;
