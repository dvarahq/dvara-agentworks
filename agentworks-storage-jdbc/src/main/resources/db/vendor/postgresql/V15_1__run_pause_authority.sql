-- Issue #183 PostgreSQL authority for operator run pause (V15).
-- * The run state machine gains QUEUED -> PAUSED: an operator pauses a queued run at once. An operator also pauses an
--   ACTIVE run whose lease expired (its worker is gone) by advancing the fence, as a cancellation revokes a lease; a
--   live lease is paused only by its holder. agentworks_fence_run is V5_1's body with exactly these changes: the new
--   transition, the refusal of a fence-advancing pause of a live lease (LEASE_HELD) or of a queued run
--   (ILLEGAL_TRANSITION), and a fence-advancing pause of an active run not counting as a claim. Neither pause is a claim, so both are permitted under a SUSPENDED cohort (like a checkpoint) and refused
--   under RETIRED, REVOKED, or PENDING.
-- * The RUN_PAUSED event of either pause is a control event written without a lease, after its summary update, in the
--   state it records, exactly like RUN_QUEUED, RUN_RESUMED, RUN_CANCELLED, and RUN_FAILED. agentworks_guard_run_event
--   is V5_1's body with that one case added, and it admits the event only when the history proves the pause: the
--   previous event queued the run (RUN_QUEUED or RUN_RESUMED), or the event carries the next fence (the expired-lease
--   pause). A worker's RUN_PAUSED of an active run still carries its lease, so no session can pause an active run
--   under a live lease without it.
-- * A pause request is fenced like other run writes: agentworks_guard_run_pause_request locks the run row (the lock
--   order every run write uses: the run, then its request) and admits a request only for an ACTIVE run. Removing one
--   is always permitted: a pause consumes it, a resume withdraws it, and every run end removes it.
-- * The Run Plane, which serves run control (the engine's store), records, reads, and removes pause requests; it can
--   never change one. The Control Plane gets nothing until an administrative view needs it (#79). Neither plane gains
--   any privilege on runs.
create or replace function agentworks_fence_run() returns trigger
    language plpgsql security definer as $$
declare
    cohort_state varchar(16);
    current_generation uuid;
    target_cohort uuid;
    target_revision uuid;
    parent agent_run%rowtype;
    claim boolean := false;
begin
    if tg_op = 'DELETE' then
        raise exception 'agentworks:IMMUTABLE_HISTORY runs cannot be deleted' using errcode = 'restrict_violation';
    end if;
    if tg_op = 'UPDATE' then
        if (new.workspace_id, new.id, new.version_id, new.release_id, new.deployment_id, new.environment_id,
                new.created_at, new.definition_id, new.revision_id, new.cohort_id, new.admission,
                new.routing_generation_id, new.routing_target_ordinal, new.parent_run_id, new.root_run_id,
                new.continuation_kind, new.idempotency_key_digest, new.request_fingerprint,
                new.idempotency_digest_version, new.trace_id, new.manifest_version, new.limit_total_tokens,
                new.limit_model_calls, new.limit_tool_calls, new.limit_runtime_millis, new.limit_cost_usd)
                is distinct from (old.workspace_id, old.id, old.version_id, old.release_id, old.deployment_id,
                old.environment_id, old.created_at, old.definition_id, old.revision_id, old.cohort_id, old.admission,
                old.routing_generation_id, old.routing_target_ordinal, old.parent_run_id, old.root_run_id,
                old.continuation_kind, old.idempotency_key_digest, old.request_fingerprint,
                old.idempotency_digest_version, old.trace_id, old.manifest_version, old.limit_total_tokens,
                old.limit_model_calls, old.limit_tool_calls, old.limit_runtime_millis, old.limit_cost_usd) then
            raise exception 'agentworks:IMMUTABLE_PIN run pins require explicit migration authority'
                using errcode = 'restrict_violation';
        end if;
        if old.lifecycle in ('SUCCEEDED', 'FAILED', 'CANCELLED') and new.lifecycle <> old.lifecycle then
            raise exception 'agentworks:TERMINAL_RUN terminal runs cannot resume' using errcode = 'check_violation';
        end if;
        if new.lifecycle <> old.lifecycle and not (
                (old.lifecycle = 'CREATED' and new.lifecycle in ('QUEUED', 'FAILED', 'CANCELLED'))
                or (old.lifecycle = 'QUEUED' and new.lifecycle in ('ACTIVE', 'PAUSED', 'FAILED', 'CANCELLED'))
                or (old.lifecycle = 'ACTIVE' and new.lifecycle in ('BLOCKED', 'PAUSED', 'SUCCEEDED', 'FAILED', 'CANCELLED'))
                or (old.lifecycle in ('BLOCKED', 'PAUSED') and new.lifecycle in ('QUEUED', 'FAILED', 'CANCELLED'))) then
            raise exception 'agentworks:ILLEGAL_TRANSITION % to %', old.lifecycle, new.lifecycle
                using errcode = 'check_violation';
        end if;
        -- #183: a pause that revokes the lease (advancing the fence) is allowed only once the lease has expired, and
        -- a queued run holds no lease to revoke.
        if old.lifecycle = 'ACTIVE' and new.lifecycle = 'PAUSED' and new.lease_fence <> old.lease_fence
                and old.lease_until > clock_timestamp() then
            raise exception 'agentworks:LEASE_HELD a live lease is paused only by its holder'
                using errcode = 'check_violation';
        end if;
        if old.lifecycle = 'QUEUED' and new.lifecycle = 'PAUSED' and new.lease_fence <> old.lease_fence then
            raise exception 'agentworks:ILLEGAL_TRANSITION a queued run pauses without a new fence'
                using errcode = 'check_violation';
        end if;
        -- The summary only moves forward: sequences, fences, and usage counters never regress, and a stale
        -- writer's compare-and-set cannot skip versions.
        if new.event_sequence < old.event_sequence or new.checkpoint_sequence < old.checkpoint_sequence
                or new.row_version not in (old.row_version, old.row_version + 1)
                or new.lease_fence not in (old.lease_fence, old.lease_fence + 1)
                or new.input_tokens < old.input_tokens or new.output_tokens < old.output_tokens
                or new.model_calls < old.model_calls or new.tool_calls < old.tool_calls
                or new.runtime_millis < old.runtime_millis or new.estimated_cost_usd < old.estimated_cost_usd then
            raise exception 'agentworks:SUMMARY_REGRESSION run summaries only advance' using errcode = 'check_violation';
        end if;
        -- Ownership moves only by a claim that advances the fence, and a claim never takes over a live lease: a
        -- recovery claim of an ACTIVE run requires its lease to have expired in database time.
        if new.lease_owner is not null and new.lease_owner is distinct from old.lease_owner
                and new.lease_fence = old.lease_fence then
            raise exception 'agentworks:STALE_LEASE ownership changes only with a new fence' using errcode = 'check_violation';
        end if;
        if new.lease_fence <> old.lease_fence and old.lifecycle = 'ACTIVE' and new.lifecycle = 'ACTIVE'
                and old.lease_until > clock_timestamp() then
            raise exception 'agentworks:LEASE_HELD a live lease cannot be claimed' using errcode = 'check_violation';
        end if;
        if (new.lifecycle <> old.lifecycle or new.lease_fence <> old.lease_fence
                or new.checkpoint_sequence <> old.checkpoint_sequence) and new.event_sequence = old.event_sequence then
            raise exception 'agentworks:TRANSITION_WITHOUT_EVENT every transition appends an event'
                using errcode = 'check_violation';
        end if;
        -- Only a live lease is extended or released by its holder; an expired lease is taken over by a new claim
        -- with the next fence, never revived. Cancellation is the control path that releases any lease.
        if old.lifecycle = 'ACTIVE' and new.lease_fence = old.lease_fence and old.lease_until <= clock_timestamp()
                and (new.lease_until is distinct from old.lease_until or new.lifecycle <> old.lifecycle
                     or new.event_sequence <> old.event_sequence) and new.lifecycle <> 'CANCELLED' then
            raise exception 'agentworks:STALE_LEASE' using errcode = 'check_violation';
        end if;
        -- Projection fields that record a transition change only with that transition.
        if new.lifecycle = old.lifecycle and ((new.block_reason, new.terminal_code, new.completed_at)
                is distinct from (old.block_reason, old.terminal_code, old.completed_at)) then
            raise exception 'agentworks:SUMMARY_WITHOUT_TRANSITION block reason, terminal code, and completion change only with the lifecycle'
                using errcode = 'check_violation';
        end if;
        if new.started_at is distinct from old.started_at
                and (old.started_at is not null or new.lease_fence = old.lease_fence) then
            raise exception 'agentworks:SUMMARY_WITHOUT_TRANSITION a run starts only with its first claim'
                using errcode = 'check_violation';
        end if;
        -- Terminalization and post-terminal evidence are always permitted writes; they create no resumable work.
        if new.lifecycle in ('SUCCEEDED', 'FAILED', 'CANCELLED') then
            return new;
        end if;
        claim := (new.lease_fence <> old.lease_fence and not (old.lifecycle = 'ACTIVE' and new.lifecycle = 'PAUSED'))
            or (new.lifecycle <> old.lifecycle and new.lifecycle in ('QUEUED', 'ACTIVE'));
        if not claim and new.lifecycle = old.lifecycle and new.event_sequence = old.event_sequence
                and new.checkpoint_sequence = old.checkpoint_sequence
                and new.lease_until is not distinct from old.lease_until then
            return new;
        end if;
    elsif new.admission = 'ROOT' then
        if new.lifecycle <> 'CREATED' then
            raise exception 'agentworks:INVALID_ADMISSION runs are admitted as CREATED' using errcode = 'check_violation';
        end if;
        select d.current_routing_generation_id into current_generation from agent_deployment d
            where d.workspace_id = new.workspace_id and d.id = new.deployment_id for share;
        if current_generation is distinct from new.routing_generation_id then
            -- Retrying re-reads the current route, giving promotion and admission one atomic cutoff.
            raise exception 'agentworks:STALE_ROUTING_GENERATION' using errcode = 'serialization_failure';
        end if;
        select t.cohort_id, t.revision_id into target_cohort, target_revision from routing_target t
            where t.workspace_id = new.workspace_id and t.generation_id = new.routing_generation_id
              and t.ordinal = new.routing_target_ordinal;
        if target_cohort is distinct from new.cohort_id or target_revision is distinct from new.revision_id then
            raise exception 'agentworks:ROUTE_TARGET_MISMATCH' using errcode = 'check_violation';
        end if;
    else
        select * into parent from agent_run r where r.workspace_id = new.workspace_id and r.id = new.parent_run_id
            for share;
        if not found or parent.lifecycle in ('SUCCEEDED', 'FAILED', 'CANCELLED') then
            raise exception 'agentworks:PARENT_NOT_RESUMABLE' using errcode = 'check_violation';
        end if;
        if new.lifecycle <> 'CREATED' or (new.cohort_id, new.revision_id, new.deployment_id, new.release_id,
                new.version_id, new.environment_id, new.definition_id, new.root_run_id)
                is distinct from (parent.cohort_id, parent.revision_id, parent.deployment_id, parent.release_id,
                parent.version_id, parent.environment_id, parent.definition_id, parent.root_run_id) then
            raise exception 'agentworks:CONTINUATION_PROVENANCE_MISMATCH' using errcode = 'check_violation';
        end if;
        -- A continuation of a run admitted by the engine inherits its trace, manifest version, and limits.
        if parent.trace_id is not null and (new.trace_id, new.manifest_version, new.limit_total_tokens,
                new.limit_model_calls, new.limit_tool_calls, new.limit_runtime_millis, new.limit_cost_usd)
                is distinct from (parent.trace_id, parent.manifest_version, parent.limit_total_tokens,
                parent.limit_model_calls, parent.limit_tool_calls, parent.limit_runtime_millis, parent.limit_cost_usd) then
            raise exception 'agentworks:CONTINUATION_PROVENANCE_MISMATCH creation facts differ from the parent'
                using errcode = 'check_violation';
        end if;
    end if;

    -- A quiescent cohort must be taken exclusively because late work rewrites it; upgrading a shared lock
    -- would deadlock concurrent late writers. Other states take the shared fence lock.
    select c.state into cohort_state from runtime_cohort c where c.workspace_id = new.workspace_id and c.id = new.cohort_id;
    if cohort_state = 'QUIESCENT' then
        select c.state into cohort_state from runtime_cohort c
            where c.workspace_id = new.workspace_id and c.id = new.cohort_id for no key update;
    else
        select c.state into cohort_state from runtime_cohort c
            where c.workspace_id = new.workspace_id and c.id = new.cohort_id for share;
        if cohort_state = 'QUIESCENT' then
            -- Observed quiescence between the peek and the lock; retry to take the exclusive lock.
            raise exception 'agentworks:COHORT_STATE_CHANGED' using errcode = 'serialization_failure';
        end if;
    end if;
    if tg_op = 'INSERT' and new.admission = 'ROOT' then
        if cohort_state <> 'ROUTABLE' then
            raise exception 'agentworks:COHORT_NOT_ADMITTING %', cohort_state using errcode = 'check_violation';
        end if;
    elsif cohort_state in ('RETIRED', 'REVOKED', 'PENDING')
            or (cohort_state = 'SUSPENDED' and (tg_op = 'INSERT' or claim)) then
        -- Existing work and continuations: claims, queueing, and resumes need an executing cohort; progress,
        -- checkpoints, and heartbeats may reach a safe boundary under SUSPENDED; nothing resumable may be written
        -- under RETIRED, REVOKED, or PENDING.
        raise exception 'agentworks:COHORT_NOT_ADMITTING %', cohort_state using errcode = 'check_violation';
    elsif cohort_state = 'QUIESCENT' then
        -- Late authoritative work invalidates the quiescence observation before it commits (ADR-027).
        update runtime_cohort set state = 'DRAINING', transition_version = transition_version + 1,
                quiescent_since = null, state_changed_at = current_timestamp
            where workspace_id = new.workspace_id and id = new.cohort_id;
        insert into cohort_transition(workspace_id, id, cohort_id, transition_version, from_state, to_state, cause,
                actor, reason, occurred_at)
            select new.workspace_id, gen_random_uuid(), c.id, c.transition_version, 'QUIESCENT', 'DRAINING',
                   'LATE_WORK', 'system:cohort-fence', 'resumable work observed after quiescence', current_timestamp
            from runtime_cohort c where c.workspace_id = new.workspace_id and c.id = new.cohort_id;
    end if;
    if tg_op = 'INSERT' and (new.trace_id is null or new.manifest_version is null or new.event_sequence <> 1
            or new.row_version <> 0 or new.lease_fence <> 0 or new.checkpoint_sequence <> 0 or new.lifecycle <> 'CREATED'
            or new.lease_owner is not null or new.block_reason is not null or new.terminal_code is not null
            or new.started_at is not null or new.completed_at is not null or new.active_since is not null
            or new.input_tokens <> 0 or new.output_tokens <> 0 or new.model_calls <> 0 or new.tool_calls <> 0
            or new.runtime_millis <> 0 or new.estimated_cost_usd <> 0) then
        raise exception 'agentworks:CREATION_FACTS_REQUIRED runs are created with trace identity, manifest version, and a RUN_CREATED event'
            using errcode = 'check_violation';
    end if;
    return new;
end;
$$;

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
            or (new.event_type = 'OPERATION_RESOLVED' and run.lifecycle in ('SUCCEEDED', 'FAILED', 'CANCELLED'))) then
        -- Control events are written without a lease only after their summary update, in the state they record;
        -- never while a worker holds the run.
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

create function agentworks_guard_run_pause_request() returns trigger
    language plpgsql security definer as $$
declare
    state varchar(16);
begin
    select r.lifecycle into state from agent_run r where r.workspace_id = new.workspace_id and r.id = new.run_id
        for no key update;
    if not found or state <> 'ACTIVE' then
        raise exception 'agentworks:PAUSE_REQUEST_NOT_ACTIVE a pause is requested only of an active run'
            using errcode = 'check_violation';
    end if;
    return new;
end;
$$;
create trigger agent_run_pause_request_guard before insert on agent_run_pause_request
    for each row execute function agentworks_guard_run_pause_request();

-- Owned by the fence owner like every other guard (V4_1, V5_1, V9_1): pinned search path, no PUBLIC execute.
do $$
declare
    guard text;
begin
    execute format('grant create on schema %I to agentworks_fence_owner', current_schema());
    foreach guard in array array['agentworks_fence_run', 'agentworks_guard_run_event',
            'agentworks_guard_run_pause_request'] loop
        execute format('alter function %I() set search_path = %I, pg_temp', guard, current_schema());
        execute format('alter function %I() owner to agentworks_fence_owner', guard);
        execute format('revoke execute on function %I() from public', guard);
    end loop;
    execute format('revoke create on schema %I from agentworks_fence_owner', current_schema());
end;
$$;

grant select, insert, delete on agent_run_pause_request to agentworks_run_plane;
