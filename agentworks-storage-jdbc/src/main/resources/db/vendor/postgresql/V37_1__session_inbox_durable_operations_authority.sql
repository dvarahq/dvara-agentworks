-- Issue #43: PostgreSQL owns session sequencing, inbox claims/outcomes, operation outcomes, and cohort fencing.
alter table session_inbox alter column payload_json type jsonb using payload_json::jsonb;
alter table durable_operation alter column input_json type jsonb using input_json::jsonb;
alter table durable_operation alter column result_json type jsonb using result_json::jsonb;
alter table session_inbox add constraint session_inbox_payload_size_ck check (octet_length(payload_json::text) <= 262144);
alter table durable_operation add constraint durable_operation_payload_size_ck check (
    octet_length(input_json::text) <= 262144 and (result_json is null or octet_length(result_json::text) <= 1048576));

create function agentworks_guard_session() returns trigger
    language plpgsql security definer as $$
begin
    if tg_op = 'DELETE' then
        raise exception 'agentworks:IMMUTABLE_HISTORY sessions cannot be deleted' using errcode = 'restrict_violation';
    end if;
    if tg_op = 'UPDATE' then
        if (new.workspace_id, new.id, new.deployment_id, new.created_at)
                is distinct from (old.workspace_id, old.id, old.deployment_id, old.created_at) then
            raise exception 'agentworks:IMMUTABLE_IDENTITY session identity is immutable' using errcode = 'restrict_violation';
        end if;
        if new.version <= old.version or new.updated_at < old.updated_at then
            raise exception 'agentworks:STALE_SESSION_VERSION' using errcode = 'serialization_failure';
        end if;
    end if;
    return new;
end;
$$;
create trigger agent_session_guard before update or delete on agent_session
    for each row execute function agentworks_guard_session();
create trigger agent_session_no_truncate before truncate on agent_session
    for each statement execute function agentworks_reject_history_mutation();

create function agentworks_guard_session_run() returns trigger
    language plpgsql security definer as $$
declare session_deployment uuid; run_deployment uuid;
begin
    if tg_op <> 'INSERT' then
        raise exception 'agentworks:IMMUTABLE_HISTORY session/run links are append-only' using errcode = 'restrict_violation';
    end if;
    select deployment_id into session_deployment from agent_session
        where workspace_id = new.workspace_id and id = new.session_id for share;
    select deployment_id into run_deployment from agent_run
        where workspace_id = new.workspace_id and id = new.run_id for share;
    if session_deployment is null or run_deployment is null or session_deployment <> run_deployment then
        raise exception 'agentworks:SESSION_RUN_MISMATCH' using errcode = 'check_violation';
    end if;
    return new;
end;
$$;
create trigger agent_session_run_guard before insert or update or delete on agent_session_run
    for each row execute function agentworks_guard_session_run();
create trigger agent_session_run_no_truncate before truncate on agent_session_run
    for each statement execute function agentworks_reject_history_mutation();

create function agentworks_guard_session_inbox() returns trigger
    language plpgsql security definer as $$
declare run_row agent_run%rowtype; cohort_state varchar(16); cohort_version bigint;
begin
    if tg_op = 'DELETE' then
        raise exception 'agentworks:IMMUTABLE_HISTORY inbox entries cannot be deleted' using errcode = 'restrict_violation';
    end if;
    select r.* into run_row from agent_run r where r.workspace_id = new.workspace_id and r.id = new.run_id for share;
    select c.state, c.transition_version into cohort_state, cohort_version from runtime_cohort c
        where c.workspace_id = new.workspace_id and c.id = new.cohort_id for share;
    if not found or (run_row.release_id, run_row.deployment_id, run_row.revision_id, run_row.cohort_id)
            is distinct from (new.release_id, new.deployment_id, new.revision_id, new.cohort_id) then
        raise exception 'agentworks:RUN_PINS_MISMATCH' using errcode = 'check_violation';
    end if;
    if tg_op = 'INSERT' then
        if new.status <> 'PENDING' or cohort_state in ('PENDING', 'RETIRED', 'REVOKED') then
            raise exception 'agentworks:COHORT_NOT_ADMITTING %', cohort_state using errcode = 'check_violation';
        end if;
        if cohort_version <> new.cohort_transition_version then
            raise exception 'agentworks:COHORT_STATE_CHANGED' using errcode = 'serialization_failure';
        end if;
        return new;
    end if;
    if (new.workspace_id, new.id, new.session_id, new.run_id, new.release_id, new.deployment_id, new.revision_id,
            new.cohort_id, new.cohort_transition_version, new.sequence, new.message_type, new.correlation_id,
            new.idempotency_key, new.request_digest, new.payload_json, new.created_at)
            is distinct from
            (old.workspace_id, old.id, old.session_id, old.run_id, old.release_id, old.deployment_id, old.revision_id,
            old.cohort_id, old.cohort_transition_version, old.sequence, old.message_type, old.correlation_id,
            old.idempotency_key, old.request_digest, old.payload_json, old.created_at) then
        raise exception 'agentworks:IMMUTABLE_IDENTITY inbox identity is immutable' using errcode = 'restrict_violation';
    end if;
    if old.status in ('CONSUMED', 'SUPERSEDED', 'CANCELLED') then
        raise exception 'agentworks:INBOX_ALREADY_TERMINAL' using errcode = 'check_violation';
    end if;
    if new.row_version <> old.row_version + 1 then
        raise exception 'agentworks:STALE_INBOX_VERSION' using errcode = 'serialization_failure';
    end if;
    if new.status = 'CLAIMED' then
        if run_row.lifecycle <> 'ACTIVE' or run_row.lease_owner is distinct from new.claimed_by
                or run_row.lease_fence <> new.claim_fence or run_row.lease_until <= clock_timestamp() then
            raise exception 'agentworks:STALE_LEASE' using errcode = 'check_violation';
        end if;
        if old.status = 'CLAIMED' and old.claim_until > clock_timestamp() then
            raise exception 'agentworks:INBOX_ALREADY_CLAIMED' using errcode = 'check_violation';
        end if;
        if cohort_state in ('PENDING', 'RETIRED', 'REVOKED', 'SUSPENDED') then
            raise exception 'agentworks:COHORT_NOT_ADMITTING %', cohort_state using errcode = 'check_violation';
        end if;
    elsif new.status = 'CONSUMED' then
        if old.status <> 'CLAIMED' or old.claimed_by is distinct from run_row.lease_owner
                or old.claim_fence <> run_row.lease_fence or old.claim_until <= clock_timestamp() then
            raise exception 'agentworks:STALE_INBOX_CLAIM' using errcode = 'check_violation';
        end if;
    elsif new.status not in ('SUPERSEDED', 'CANCELLED') then
        raise exception 'agentworks:INVALID_INBOX_TRANSITION' using errcode = 'check_violation';
    end if;
    return new;
end;
$$;
create trigger session_inbox_guard before insert or update or delete on session_inbox
    for each row execute function agentworks_guard_session_inbox();
create trigger session_inbox_no_truncate before truncate on session_inbox
    for each statement execute function agentworks_reject_history_mutation();

create function agentworks_sync_inbox_work() returns trigger
    language plpgsql security definer as $$
begin
    if tg_op = 'INSERT' then
        insert into cohort_work_item(workspace_id, id, cohort_id, run_id, category, subject_id, state,
                created_at, state_changed_at, terminal_at)
            values (new.workspace_id, gen_random_uuid(), new.cohort_id, new.run_id, 'INBOX', new.id, 'PENDING',
                new.created_at, new.created_at, null);
    elsif new.status = 'CLAIMED' then
        update cohort_work_item set state = 'ACTIVE', state_changed_at = clock_timestamp()
            where workspace_id = new.workspace_id and category = 'INBOX' and subject_id = new.id and state <> 'TERMINAL';
    elsif new.status in ('CONSUMED', 'SUPERSEDED', 'CANCELLED') then
        update cohort_work_item set state = 'TERMINAL', state_changed_at = new.terminal_at, terminal_at = new.terminal_at
            where workspace_id = new.workspace_id and category = 'INBOX' and subject_id = new.id and state <> 'TERMINAL';
    end if;
    if not found and tg_op = 'UPDATE' then
        raise exception 'agentworks:INBOX_LEDGER_MISSING' using errcode = 'check_violation';
    end if;
    return new;
end;
$$;
create trigger session_inbox_work after insert or update on session_inbox
    for each row execute function agentworks_sync_inbox_work();

create function agentworks_guard_durable_operation() returns trigger
    language plpgsql security definer as $$
declare run_row agent_run%rowtype; cohort_state varchar(16); cohort_version bigint;
begin
    if tg_op = 'DELETE' then
        raise exception 'agentworks:IMMUTABLE_HISTORY operations cannot be deleted' using errcode = 'restrict_violation';
    end if;
    select r.* into run_row from agent_run r where r.workspace_id = new.workspace_id and r.id = new.run_id for share;
    select c.state, c.transition_version into cohort_state, cohort_version from runtime_cohort c
        where c.workspace_id = new.workspace_id and c.id = new.cohort_id for share;
    if not found or (run_row.release_id, run_row.deployment_id, run_row.revision_id, run_row.cohort_id)
            is distinct from (new.release_id, new.deployment_id, new.revision_id, new.cohort_id) then
        raise exception 'agentworks:RUN_PINS_MISMATCH' using errcode = 'check_violation';
    end if;
    if tg_op = 'INSERT' then
        if new.status <> 'STARTING' or run_row.lifecycle <> 'BLOCKED' or run_row.block_reason <> 'DURABLE_OPERATION'
                or cohort_state in ('PENDING', 'RETIRED', 'REVOKED', 'SUSPENDED') then
            raise exception 'agentworks:OPERATION_NOT_ADMISSIBLE %', cohort_state using errcode = 'check_violation';
        end if;
        if cohort_version <> new.cohort_transition_version then
            raise exception 'agentworks:COHORT_STATE_CHANGED' using errcode = 'serialization_failure';
        end if;
        return new;
    end if;
    if (new.workspace_id, new.id, new.session_id, new.run_id, new.release_id, new.deployment_id, new.revision_id,
            new.cohort_id, new.cohort_transition_version, new.correlation_id, new.idempotency_key,
            new.request_digest, new.provider_id, new.operation_type, new.input_json, new.callback_token_digest,
            new.callback_token_version,
            new.created_at, new.deadline)
            is distinct from
            (old.workspace_id, old.id, old.session_id, old.run_id, old.release_id, old.deployment_id, old.revision_id,
            old.cohort_id, old.cohort_transition_version, old.correlation_id, old.idempotency_key,
            old.request_digest, old.provider_id, old.operation_type, old.input_json, old.callback_token_digest,
            old.callback_token_version,
            old.created_at, old.deadline) then
        raise exception 'agentworks:IMMUTABLE_IDENTITY operation request is immutable' using errcode = 'restrict_violation';
    end if;
    if old.status in ('SUCCEEDED', 'FAILED', 'CANCELLED') then
        raise exception 'agentworks:OPERATION_ALREADY_TERMINAL' using errcode = 'check_violation';
    end if;
    if new.status not in ('RUNNING', 'SUCCEEDED', 'FAILED', 'CANCELLED') then
        raise exception 'agentworks:INVALID_OPERATION_TRANSITION' using errcode = 'check_violation';
    end if;
    if new.status = 'RUNNING' and cohort_state in ('PENDING', 'RETIRED', 'REVOKED', 'SUSPENDED') then
        raise exception 'agentworks:COHORT_NOT_ADMITTING %', cohort_state using errcode = 'check_violation';
    end if;
    return new;
end;
$$;
create trigger durable_operation_guard before insert or update or delete on durable_operation
    for each row execute function agentworks_guard_durable_operation();
create trigger durable_operation_no_truncate before truncate on durable_operation
    for each statement execute function agentworks_reject_history_mutation();

create function agentworks_sync_operation_work() returns trigger
    language plpgsql security definer as $$
begin
    if tg_op = 'INSERT' then
        insert into cohort_work_item(workspace_id, id, cohort_id, run_id, category, subject_id, state,
                created_at, state_changed_at, terminal_at)
            values (new.workspace_id, gen_random_uuid(), new.cohort_id, new.run_id, 'OPERATION', new.id, 'PENDING',
                new.created_at, new.created_at, null);
    elsif new.status = 'RUNNING' then
        update cohort_work_item set state = 'ACTIVE', state_changed_at = new.updated_at
            where workspace_id = new.workspace_id and category = 'OPERATION' and subject_id = new.id and state <> 'TERMINAL';
    elsif new.status in ('SUCCEEDED', 'FAILED', 'CANCELLED') then
        update cohort_work_item set state = 'TERMINAL', state_changed_at = new.terminal_at, terminal_at = new.terminal_at
            where workspace_id = new.workspace_id and category = 'OPERATION' and subject_id = new.id and state <> 'TERMINAL';
    end if;
    if not found and tg_op = 'UPDATE' then
        raise exception 'agentworks:OPERATION_LEDGER_MISSING' using errcode = 'check_violation';
    end if;
    return new;
end;
$$;
create trigger durable_operation_work after insert or update on durable_operation
    for each row execute function agentworks_sync_operation_work();

grant select, insert, update on cohort_work_item to agentworks_fence_owner;
grant select, update on agent_session to agentworks_fence_owner;
grant select on agent_session_run, agent_run, runtime_cohort, agent_run_effect to agentworks_fence_owner;
do $$
declare guard text;
begin
    execute format('grant create on schema %I to agentworks_fence_owner', current_schema());
    foreach guard in array array['agentworks_guard_session', 'agentworks_guard_session_run',
            'agentworks_guard_session_inbox', 'agentworks_sync_inbox_work', 'agentworks_guard_durable_operation',
            'agentworks_sync_operation_work'] loop
        execute format('alter function %I() set search_path = %I, pg_temp', guard, current_schema());
        execute format('alter function %I() owner to agentworks_fence_owner', guard);
        execute format('revoke execute on function %I() from public', guard);
    end loop;
    execute format('revoke create on schema %I from agentworks_fence_owner', current_schema());
end $$;

grant select, insert, update on agent_session, agent_session_run, session_inbox, durable_operation
    to agentworks_run_plane;
grant select on agent_session, agent_session_run, session_inbox, durable_operation to agentworks_control_plane;

-- Extend the run-event fence: a lease-free operation outcome and its completion-message notification are legal
-- only after the matching durable rows reached their one authoritative outcome in the same transaction.
do $$ begin execute format('grant create on schema %I to agentworks_fence_owner', current_schema()); end $$;
grant select on durable_operation, session_inbox to agentworks_fence_owner;
set role agentworks_fence_owner;
create or replace function agentworks_guard_run_event() returns trigger
    language plpgsql security definer as $$
declare
    run agent_run%rowtype;
    latest bigint;
    cohort_state varchar(16);
begin
    select * into run from agent_run r where r.workspace_id = new.workspace_id and r.id = new.run_id for no key update;
    if not found then raise exception 'agentworks:RUN_NOT_FOUND' using errcode = 'check_violation'; end if;
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
                    on p.workspace_id = h.workspace_id and p.run_id = h.run_id and p.sequence = new.sequence - 1
                where h.workspace_id = new.workspace_id and h.run_id = new.run_id
                    and h.id::text = new.payload_json->>'interactionId' and h.resolved_by = new.actor
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
                            and h.kind = 'AUTHORIZATION'))))
            or (new.event_type in ('OPERATION_COMPLETED', 'OPERATION_FAILED', 'OPERATION_CANCELLED')
                    and run.lifecycle = 'QUEUED' and exists (
                select 1 from durable_operation o join agent_run_event p
                    on p.workspace_id = o.workspace_id and p.run_id = o.run_id and p.sequence = new.sequence - 1
                where o.workspace_id = new.workspace_id and o.run_id = new.run_id
                    and o.id::text = new.payload_json->>'operationId' and o.resolved_by = new.actor
                    and p.event_type = 'RUN_BLOCKED'
                    and p.payload_json->'detail'->>'operationId' = o.id::text
                    and ((new.event_type = 'OPERATION_COMPLETED' and o.status = 'SUCCEEDED')
                        or (new.event_type = 'OPERATION_FAILED' and o.status = 'FAILED')
                        or (new.event_type = 'OPERATION_CANCELLED' and o.status = 'CANCELLED'))))
            or (new.event_type = 'INBOX_MESSAGE_RECEIVED' and run.lifecycle = 'QUEUED' and exists (
                select 1 from session_inbox i join agent_run_event p
                    on p.workspace_id = i.workspace_id and p.run_id = i.run_id and p.sequence = new.sequence - 1
                where i.workspace_id = new.workspace_id and i.run_id = new.run_id
                    and i.id::text = new.payload_json->>'messageId' and i.message_type = 'OPERATION_COMPLETED'
                    and i.status = 'PENDING'
                    and p.event_type in ('OPERATION_COMPLETED', 'OPERATION_FAILED', 'OPERATION_CANCELLED')
                    and p.payload_json->>'operationId' = i.payload_json->>'operationId'))
            ) then
        raise exception 'agentworks:UNAUTHORIZED_EVENT_WRITER % needs a lease', new.event_type
            using errcode = 'check_violation';
    end if;
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
do $$ begin execute format('alter function %I() set search_path = %I, pg_temp',
    'agentworks_guard_run_event', current_schema()); end $$;
reset role;
do $$ begin execute format('revoke create on schema %I from agentworks_fence_owner', current_schema()); end $$;
