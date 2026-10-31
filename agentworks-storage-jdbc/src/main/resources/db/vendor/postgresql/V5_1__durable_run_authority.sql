-- Issue #32 PostgreSQL authority for durable runs (ADR-003, ADR-012, ADR-027). V3_1 and V4_1 are published and
-- unchanged; this migration supersedes the agentworks_fence_run body. Application code is written to these rules;
-- the triggers make the reference database enforce them for stale workers, direct SQL, and plane roles too.
-- Rejections carry an `agentworks:` code in the message.
--
-- Digests: the triggers enforce sequence, ownership, correlation, the summary head, and history backing, but they
-- do not recompute payload or event digests. Digests are defined over the Java codec's canonical JSON, which JSONB
-- text does not reproduce, so the codec is authoritative: every claim and verify recomputes the chain, and a run
-- whose history does not verify is quarantined and never executed (RunClaimRefusedException, CORRUPT_HISTORY).
--
-- Write protocol: a writer locks the run first. A lease holder appends its events (and checkpoints and effects)
-- while its lease is still recorded on the run and then advances the summary, which may release the lease; a claim
-- or a control write (creation, queueing, resumption, cancellation) updates the summary first and then appends the
-- events it counts. The guards compare each row with the ownership the run records at that moment, and deferred
-- constraint triggers prove at commit that the summary counts exactly the events and checkpoints that exist and
-- that every transition and claim is backed by its event. Lock order stays deployment, then parent run, then run,
-- then cohort, matching admission, promotion, and retirement; no path locks a run after its cohort.

-- Bounded JSONB payloads: canonical JSON objects of at most 256 KiB.
alter table agent_run_event alter column payload_json type jsonb using payload_json::jsonb;
alter table agent_run_event add constraint agent_run_event_payload_ck
    check (jsonb_typeof(payload_json) = 'object' and octet_length(payload_json::text) <= 262144);
alter table agent_run_checkpoint alter column payload_json type jsonb using payload_json::jsonb;
alter table agent_run_checkpoint add constraint agent_run_checkpoint_payload_ck
    check (jsonb_typeof(payload_json) = 'object' and octet_length(payload_json::text) <= 262144);
alter table agent_run_effect alter column result_json type jsonb using result_json::jsonb;
alter table agent_run_effect add constraint agent_run_effect_result_ck
    check (result_json is null or octet_length(result_json::text) <= 262144);
alter table agent_run add constraint agent_run_trace_format_ck
    check (trace_id is null or (trace_id ~ '^[0-9a-f]{32}$' and trace_id <> repeat('0', 32)));
alter table agent_run_event add constraint agent_run_event_trace_format_ck
    check ((trace_id is null or trace_id ~ '^[0-9a-f]{32}$') and (span_id is null or span_id ~ '^[0-9a-f]{16}$'));

-- Events and checkpoints are append-only.
do $$
declare
    history_table text;
begin
    foreach history_table in array array['agent_run_event', 'agent_run_checkpoint'] loop
        execute format('create trigger %I before update or delete on %I for each row '
            'execute function agentworks_reject_history_mutation()', history_table || '_append_only', history_table);
        execute format('create trigger %I before truncate on %I for each statement '
            'execute function agentworks_reject_history_mutation()', history_table || '_no_truncate', history_table);
    end loop;
end;
$$;

-- The cohort fence, now covering the run state machine, fenced leases, summary integrity, and every
-- resumable-work write of an existing run, not only lifecycle changes.
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
                or (old.lifecycle = 'QUEUED' and new.lifecycle in ('ACTIVE', 'FAILED', 'CANCELLED'))
                or (old.lifecycle = 'ACTIVE' and new.lifecycle in ('BLOCKED', 'PAUSED', 'SUCCEEDED', 'FAILED', 'CANCELLED'))
                or (old.lifecycle in ('BLOCKED', 'PAUSED') and new.lifecycle in ('QUEUED', 'FAILED', 'CANCELLED'))) then
            raise exception 'agentworks:ILLEGAL_TRANSITION % to %', old.lifecycle, new.lifecycle
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
        claim := new.lease_fence <> old.lease_fence
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

-- At commit, every summary advance is backed by exactly the events it counts, each transition and claim by its
-- event, and a terminal run only by post-terminal evidence.
create function agentworks_verify_run_history() returns trigger
    language plpgsql security definer as $$
declare
    previous_sequence bigint;
    appended bigint;
    transitions bigint;
    recoveries bigint;
    expected varchar(64);
begin
    previous_sequence := case when tg_op = 'INSERT' then 0 else old.event_sequence end;
    select count(*) into appended from agent_run_event e
        where e.workspace_id = new.workspace_id and e.run_id = new.id
          and e.sequence > previous_sequence and e.sequence <= new.event_sequence;
    if appended <> new.event_sequence - previous_sequence then
        raise exception 'agentworks:HISTORY_MISMATCH the summary counts events that were not appended'
            using errcode = 'check_violation';
    end if;
    if new.event_sequence > 0 and not exists (select 1 from agent_run_event e where e.workspace_id = new.workspace_id
            and e.run_id = new.id and e.sequence = new.event_sequence and e.event_digest = new.last_event_digest) then
        raise exception 'agentworks:HISTORY_MISMATCH the summary head is not the last event' using errcode = 'check_violation';
    end if;
    if tg_op = 'INSERT' then
        if not exists (select 1 from agent_run_event e where e.workspace_id = new.workspace_id and e.run_id = new.id
                and e.sequence = 1 and e.event_type = 'RUN_CREATED') then
            raise exception 'agentworks:HISTORY_MISMATCH a run starts with RUN_CREATED' using errcode = 'check_violation';
        end if;
        return null;
    end if;
    -- Transition events appear exactly when the summary records their transition, and creation only once.
    select count(*) filter (where e.event_type in ('RUN_QUEUED', 'RUN_RESUMED', 'RUN_ACTIVATED', 'RUN_BLOCKED',
                'RUN_PAUSED', 'RUN_SUCCEEDED', 'RUN_FAILED', 'RUN_CANCELLED')),
           count(*) filter (where e.event_type = 'RUN_RECOVERED')
        into transitions, recoveries from agent_run_event e
        where e.workspace_id = new.workspace_id and e.run_id = new.id
          and e.sequence > old.event_sequence and e.sequence <= new.event_sequence;
    -- (plpgsql reads an IF condition up to its first THEN, so the expected counts are computed as booleans.)
    if transitions <> (new.lifecycle <> old.lifecycle)::integer
            or recoveries <> (new.lease_fence <> old.lease_fence and old.lifecycle = 'ACTIVE'
                and new.lifecycle = 'ACTIVE')::integer
            or exists (select 1 from agent_run_event e where e.workspace_id = new.workspace_id and e.run_id = new.id
                and e.sequence > old.event_sequence and e.sequence <= new.event_sequence and e.event_type = 'RUN_CREATED') then
        raise exception 'agentworks:HISTORY_MISMATCH transition events must match the recorded transitions'
            using errcode = 'check_violation';
    end if;
    if new.lifecycle <> old.lifecycle then
        expected := case new.lifecycle
            when 'QUEUED' then case when old.lifecycle = 'CREATED' then 'RUN_QUEUED' else 'RUN_RESUMED' end
            when 'ACTIVE' then 'RUN_ACTIVATED' when 'BLOCKED' then 'RUN_BLOCKED' when 'PAUSED' then 'RUN_PAUSED'
            when 'SUCCEEDED' then 'RUN_SUCCEEDED' when 'FAILED' then 'RUN_FAILED' else 'RUN_CANCELLED' end;
        if not exists (select 1 from agent_run_event e where e.workspace_id = new.workspace_id and e.run_id = new.id
                and e.sequence > old.event_sequence and e.sequence <= new.event_sequence and e.event_type = expected) then
            raise exception 'agentworks:TRANSITION_WITHOUT_EVENT % to % requires %', old.lifecycle, new.lifecycle, expected
                using errcode = 'check_violation';
        end if;
    end if;
    if new.lease_fence <> old.lease_fence and new.lease_owner is not null and not exists (select 1 from agent_run_event e
            where e.workspace_id = new.workspace_id and e.run_id = new.id and e.sequence > old.event_sequence
              and e.sequence <= new.event_sequence and e.event_type in ('RUN_ACTIVATED', 'RUN_RECOVERED')
              and e.lease_owner = new.lease_owner and e.lease_fence = new.lease_fence) then
        raise exception 'agentworks:TRANSITION_WITHOUT_EVENT a claim records its owner and fence'
            using errcode = 'check_violation';
    end if;
    if old.lifecycle in ('SUCCEEDED', 'FAILED', 'CANCELLED') and exists (select 1 from agent_run_event e
            where e.workspace_id = new.workspace_id and e.run_id = new.id and e.sequence > old.event_sequence
              and e.sequence <= new.event_sequence and e.event_type <> 'OPERATION_RESOLVED') then
        raise exception 'agentworks:TERMINAL_RUN terminal runs accept only post-terminal evidence'
            using errcode = 'check_violation';
    end if;
    if new.checkpoint_sequence > old.checkpoint_sequence
            and (select count(*) from agent_run_checkpoint k where k.workspace_id = new.workspace_id and k.run_id = new.id
                and k.sequence > old.checkpoint_sequence and k.sequence <= new.checkpoint_sequence)
                <> new.checkpoint_sequence - old.checkpoint_sequence then
        raise exception 'agentworks:HISTORY_MISMATCH the summary counts checkpoints that were not written'
            using errcode = 'check_violation';
    end if;
    return null;
end;
$$;
create constraint trigger agent_run_history_backed after insert or update on agent_run
    deferrable initially deferred for each row execute function agentworks_verify_run_history();

-- Events: gapless per-run sequence, correlation equal to the run's pins, and a writer that holds the run's
-- current, unexpired lease in database time. Only control events (creation, queueing, resumption, cancellation,
-- and post-terminal effect resolution) are written without a lease.
create function agentworks_guard_run_event() returns trigger
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
create trigger agent_run_event_guard before insert on agent_run_event
    for each row execute function agentworks_guard_run_event();

-- Checkpoints: written only by the current lease holder of an active run, in sequence, at its CHECKPOINT_CREATED
-- event.
create function agentworks_guard_run_checkpoint() returns trigger
    language plpgsql security definer as $$
declare
    run agent_run%rowtype;
    latest bigint;
begin
    select * into run from agent_run r where r.workspace_id = new.workspace_id and r.id = new.run_id for no key update;
    if not found or run.lifecycle <> 'ACTIVE' or run.lease_owner is distinct from new.lease_owner
            or run.lease_fence <> new.lease_fence or run.lease_until <= clock_timestamp() then
        raise exception 'agentworks:STALE_LEASE' using errcode = 'check_violation';
    end if;
    select coalesce(max(k.sequence), 0) into latest from agent_run_checkpoint k
        where k.workspace_id = new.workspace_id and k.run_id = new.run_id;
    if new.sequence <> latest + 1 or not exists (select 1 from agent_run_event e
                where e.workspace_id = new.workspace_id and e.run_id = new.run_id
                  and e.sequence = new.event_sequence and e.event_type = 'CHECKPOINT_CREATED'
                  and (e.payload_json ->> 'checkpointSequence')::bigint = new.sequence) then
        raise exception 'agentworks:CHECKPOINT_SEQUENCE' using errcode = 'check_violation';
    end if;
    return new;
end;
$$;
create trigger agent_run_checkpoint_guard before insert on agent_run_checkpoint
    for each row execute function agentworks_guard_run_checkpoint();

-- At commit, every event and checkpoint is counted by the run summary.
create function agentworks_verify_counted() returns trigger
    language plpgsql security definer as $$
begin
    if tg_table_name = 'agent_run_event' then
        if not exists (select 1 from agent_run r where r.workspace_id = new.workspace_id and r.id = new.run_id
                and r.event_sequence >= new.sequence) then
            raise exception 'agentworks:EVENT_NOT_COUNTED the run summary does not count event %', new.sequence
                using errcode = 'check_violation';
        end if;
    elsif not exists (select 1 from agent_run r where r.workspace_id = new.workspace_id and r.id = new.run_id
            and r.checkpoint_sequence >= new.sequence) then
        raise exception 'agentworks:CHECKPOINT_NOT_COUNTED the run summary does not count checkpoint %', new.sequence
            using errcode = 'check_violation';
    end if;
    return null;
end;
$$;
create constraint trigger agent_run_event_counted after insert on agent_run_event
    deferrable initially deferred for each row execute function agentworks_verify_counted();
create constraint trigger agent_run_checkpoint_counted after insert on agent_run_checkpoint
    deferrable initially deferred for each row execute function agentworks_verify_counted();

-- Effects: intent and outcome are written by the current lease holder; a committed outcome is fenced by the
-- cohort (revocation stops normal commits, leaving the effect explicitly in doubt); only a terminal run's in-doubt
-- effect may be resolved without a lease. Identity and outcomes are immutable.
create function agentworks_guard_run_effect() returns trigger
    language plpgsql security definer as $$
declare
    run agent_run%rowtype;
    cohort_state varchar(16);
begin
    if tg_op = 'DELETE' then
        raise exception 'agentworks:IMMUTABLE_HISTORY effects cannot be deleted' using errcode = 'restrict_violation';
    end if;
    select * into run from agent_run r where r.workspace_id = new.workspace_id and r.id = new.run_id for no key update;
    if tg_op = 'UPDATE' then
        if (new.workspace_id, new.run_id, new.effect_key, new.id, new.cohort_id, new.request_digest, new.started_at)
                is distinct from (old.workspace_id, old.run_id, old.effect_key, old.id, old.cohort_id,
                old.request_digest, old.started_at) or old.state <> 'PENDING' or new.attempts < old.attempts then
            raise exception 'agentworks:IMMUTABLE_HISTORY effect identity and outcomes are immutable'
                using errcode = 'restrict_violation';
        end if;
        if new.state = 'ABANDONED' then
            if new.lease_owner is not null or run.lifecycle not in ('SUCCEEDED', 'FAILED', 'CANCELLED') then
                raise exception 'agentworks:EFFECT_RESOLUTION_REQUIRES_TERMINAL_RUN' using errcode = 'check_violation';
            end if;
            return new;
        end if;
    elsif new.state <> 'PENDING' or not exists (select 1 from agent_run_event e where e.workspace_id = new.workspace_id
            and e.run_id = new.run_id and e.event_type = 'OPERATION_STARTED'
            and e.payload_json ->> 'effectKey' = new.effect_key) then
        raise exception 'agentworks:EFFECT_INTENT_REQUIRED effects are recorded as PENDING with OPERATION_STARTED before invocation'
            using errcode = 'check_violation';
    end if;
    if run.lifecycle <> 'ACTIVE' or run.lease_owner is distinct from new.lease_owner
            or run.lease_fence <> new.lease_fence or run.lease_until <= clock_timestamp() then
        raise exception 'agentworks:STALE_LEASE' using errcode = 'check_violation';
    end if;
    select c.state into cohort_state from runtime_cohort c
        where c.workspace_id = new.workspace_id and c.id = new.cohort_id for share;
    -- Suspension stops new invocations: a new intent or another attempt of an in-doubt effect. Recording the
    -- outcome of an invocation that already happened remains permitted.
    if cohort_state in ('RETIRED', 'REVOKED', 'PENDING') or (cohort_state = 'SUSPENDED'
            and (tg_op = 'INSERT' or new.attempts > old.attempts)) then
        raise exception 'agentworks:COHORT_NOT_ADMITTING %', cohort_state using errcode = 'check_violation';
    end if;
    return new;
end;
$$;
create trigger agent_run_effect_guard before insert or update or delete on agent_run_effect
    for each row execute function agentworks_guard_run_effect();
create trigger agent_run_effect_no_truncate before truncate on agent_run_effect
    for each statement execute function agentworks_reject_history_mutation();

-- The resumable-work ledger fence: the same semantics as
-- agentworks_fence_run. Terminal writes are always permitted; non-terminal writes are rejected under RETIRED,
-- REVOKED, and PENDING; claims, resumes, and new effectful operations are rejected under SUSPENDED; late
-- non-terminal work under QUIESCENT returns the cohort to DRAINING with a LATE_WORK transition.
create function agentworks_fence_work_item() returns trigger
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
        or (new.category = 'OPERATION' and tg_op = 'INSERT')
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
create trigger cohort_work_item_fence before insert or update or delete on cohort_work_item for each row execute function agentworks_fence_work_item();
create trigger cohort_work_item_no_truncate before truncate on cohort_work_item
    for each statement execute function agentworks_reject_history_mutation();

-- The fence owner reads what the guards check and may lock runs and cohorts; the identity triggers prevent any
-- change through the lock grants. Ownership moves to it as in V4_1: the new owner holds schema CREATE only while
-- ownership is transferred, and PUBLIC cannot execute any guard directly.
grant select on agent_run_event, agent_run_checkpoint, agent_run_effect, cohort_work_item to agentworks_fence_owner;
do $$
declare
    guard text;
begin
    execute format('grant create on schema %I to agentworks_fence_owner', current_schema());
    foreach guard in array array['agentworks_fence_run', 'agentworks_verify_run_history', 'agentworks_guard_run_event',
            'agentworks_verify_counted',
            'agentworks_guard_run_checkpoint', 'agentworks_guard_run_effect', 'agentworks_fence_work_item'] loop
        execute format('alter function %I() set search_path = %I, pg_temp', guard, current_schema());
        execute format('alter function %I() owner to agentworks_fence_owner', guard);
        execute format('revoke execute on function %I() from public', guard);
    end loop;
    execute format('revoke create on schema %I from agentworks_fence_owner', current_schema());
end;
$$;

-- Plane privileges (ADR-002, ADR-012). The Run Plane records run history, checkpoints, effects, and ledger rows
-- and advances run summaries; it still cannot author releases, deployments, routes, cohorts, decisions, or pins.
-- The Control Plane reads run evidence and the ledger for quiescence and records outbox deliveries.
grant select on agent_run_event, agent_run_checkpoint, agent_run_effect, cohort_work_item
    to agentworks_run_plane, agentworks_control_plane;
grant insert on agent_run_event, agent_run_checkpoint, agent_run_effect, cohort_work_item to agentworks_run_plane;
grant update (lifecycle, block_reason, terminal_code, event_sequence, last_event_digest, checkpoint_sequence,
    started_at, completed_at, input_tokens, output_tokens, total_tokens, model_calls, tool_calls, runtime_millis,
    estimated_cost_usd, row_version, lease_owner, lease_fence, lease_until, active_since, updated_at)
    on agent_run to agentworks_run_plane;
grant update (state, attempts, result_json, resolution, lease_owner, lease_fence, completed_at)
    on agent_run_effect to agentworks_run_plane;
grant update (state, state_changed_at, terminal_at) on cohort_work_item to agentworks_run_plane;
grant select, insert on control_plane_outbox_delivery to agentworks_control_plane;
