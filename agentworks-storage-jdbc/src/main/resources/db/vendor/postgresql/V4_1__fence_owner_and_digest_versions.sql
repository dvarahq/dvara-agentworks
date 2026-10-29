-- Issue #155 PostgreSQL hardening. V3_1 is published and unchanged; this migration supersedes its function
-- bodies. The cohort fence covers the new idempotency digest-version pin, runs as a dedicated NOLOGIN owner
-- with only the privileges its checks need, and cannot be executed directly by PUBLIC. Like V3_1, it creates
-- the owner role when the migration user may; otherwise pre-create agentworks_fence_owner (NOLOGIN) first.

create or replace function agentworks_guard_idempotency() returns trigger
    language plpgsql as $$
begin
    if tg_op = 'DELETE' then
        if exists (select 1 from idempotency_namespace n where n.workspace_id = old.workspace_id
                and n.namespace = old.namespace and n.state = 'RETIRED') then
            return old;
        end if;
        raise exception 'agentworks:TOMBSTONE_RETAINED tombstones remain until namespace retirement'
            using errcode = 'restrict_violation';
    end if;
    if (new.workspace_id, new.namespace, new.key_digest, new.fingerprint_digest, new.result_kind, new.result_id,
            new.result_status, new.created_at, new.replay_until, new.digest_version)
            is distinct from (old.workspace_id, old.namespace, old.key_digest, old.fingerprint_digest,
            old.result_kind, old.result_id, old.result_status, old.created_at, old.replay_until, old.digest_version)
            or old.compacted_at is not null or new.result_json is not null then
        raise exception 'agentworks:IMMUTABLE_HISTORY idempotency records may only be compacted once'
            using errcode = 'restrict_violation';
    end if;
    return new;
end;
$$;
create or replace function agentworks_fence_run() returns trigger
    language plpgsql security definer as $$
declare
    cohort_state varchar(16);
    current_generation uuid;
    target_cohort uuid;
    target_revision uuid;
    parent agent_run%rowtype;
begin
    if tg_op = 'DELETE' then
        raise exception 'agentworks:IMMUTABLE_HISTORY runs cannot be deleted' using errcode = 'restrict_violation';
    end if;
    if tg_op = 'UPDATE' then
        if (new.workspace_id, new.id, new.version_id, new.release_id, new.deployment_id, new.environment_id,
                new.created_at, new.definition_id, new.revision_id, new.cohort_id, new.admission,
                new.routing_generation_id, new.routing_target_ordinal, new.parent_run_id, new.root_run_id,
                new.continuation_kind, new.idempotency_key_digest, new.request_fingerprint,
                new.idempotency_digest_version)
                is distinct from (old.workspace_id, old.id, old.version_id, old.release_id, old.deployment_id,
                old.environment_id, old.created_at, old.definition_id, old.revision_id, old.cohort_id, old.admission,
                old.routing_generation_id, old.routing_target_ordinal, old.parent_run_id, old.root_run_id,
                old.continuation_kind, old.idempotency_key_digest, old.request_fingerprint,
                old.idempotency_digest_version) then
            raise exception 'agentworks:IMMUTABLE_PIN run pins require explicit migration authority'
                using errcode = 'restrict_violation';
        end if;
        if old.lifecycle in ('SUCCEEDED', 'FAILED', 'CANCELLED') and new.lifecycle <> old.lifecycle then
            raise exception 'agentworks:TERMINAL_RUN terminal runs cannot resume' using errcode = 'check_violation';
        end if;
        -- Terminalization is always a permitted evidence write; it never creates resumable work.
        if new.lifecycle = old.lifecycle or new.lifecycle in ('SUCCEEDED', 'FAILED', 'CANCELLED') then
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
        return new;
    end if;
    -- Existing work and continuations: claims/resumes need an executing cohort; checkpoints may record a
    -- suspension at a safe boundary; nothing resumable may be written under RETIRED, REVOKED, or PENDING.
    if cohort_state in ('RETIRED', 'REVOKED', 'PENDING')
            or (cohort_state = 'SUSPENDED' and (tg_op = 'INSERT' or new.lifecycle in ('QUEUED', 'ACTIVE'))) then
        raise exception 'agentworks:COHORT_NOT_ADMITTING %', cohort_state using errcode = 'check_violation';
    end if;
    if cohort_state = 'QUIESCENT' then
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
    return new;
end;
$$;
do $$
begin
    execute format('alter function agentworks_fence_run() set search_path = %I, pg_temp', current_schema());
    if not exists (select 1 from pg_roles where rolname = 'agentworks_fence_owner') then
        create role agentworks_fence_owner nologin;
    end if;
    -- Transferring ownership requires that the migration user can become the new owner: SET membership on
    -- PostgreSQL 16 and later, plain membership before. A pre-created role must be granted to the migration user
    -- with ADMIN OPTION so that this grant can be made.
    if current_setting('server_version_num')::integer >= 160000 then
        if not pg_has_role(current_user, 'agentworks_fence_owner', 'SET') then
            execute format('grant agentworks_fence_owner to %I with set true', current_user);
        end if;
    elsif not pg_has_role(current_user, 'agentworks_fence_owner', 'MEMBER') then
        execute format('grant agentworks_fence_owner to %I', current_user);
    end if;
    execute format('grant usage on schema %I to agentworks_fence_owner', current_schema());
end;
$$;

-- Row locks need UPDATE privilege on at least one column; the fence and identity triggers prevent any change
-- through these grants.
grant select on agent_deployment, routing_target, runtime_cohort, agent_run to agentworks_fence_owner;
grant update (workspace_id) on agent_deployment, agent_run to agentworks_fence_owner;
grant update (state, transition_version, quiescent_since, state_changed_at) on runtime_cohort to agentworks_fence_owner;
grant insert on cohort_transition to agentworks_fence_owner;

-- The new owner of a function must hold CREATE on its schema, which PostgreSQL 15 and later no longer grant to
-- PUBLIC on the public schema. It holds CREATE only for the ownership change.
do $$
begin
    execute format('grant create on schema %I to agentworks_fence_owner', current_schema());
    alter function agentworks_fence_run() owner to agentworks_fence_owner;
    execute format('revoke create on schema %I from agentworks_fence_owner', current_schema());
end;
$$;
revoke execute on function agentworks_fence_run() from public;
