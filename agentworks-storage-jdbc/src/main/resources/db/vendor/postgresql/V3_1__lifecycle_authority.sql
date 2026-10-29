-- Issue #31 PostgreSQL authority for the canonical lifecycle (ADR-002, ADR-005, ADR-008, ADR-012, ADR-027).
-- Application code is written to these rules; the triggers make the reference database enforce them for
-- direct SQL, stale writers, and roles as well. Rejections carry an `agentworks:` code in the message.

-- Append-only lifecycle history.
create function agentworks_reject_history_mutation() returns trigger
    language plpgsql as $$
begin
    raise exception 'agentworks:IMMUTABLE_HISTORY % rows cannot be updated, deleted, or truncated', tg_table_name
        using errcode = 'restrict_violation';
end;
$$;

do $$
declare
    history_table text;
begin
    foreach history_table in array array['agent_release', 'release_alias_history', 'promotion_policy_version',
            'lifecycle_decision', 'deployment_revision', 'cohort_transition', 'routing_generation',
            'routing_target', 'control_plane_outbox'] loop
        execute format('create trigger %I before update or delete on %I for each row '
            'execute function agentworks_reject_history_mutation()', history_table || '_append_only', history_table);
        execute format('create trigger %I before truncate on %I for each statement '
            'execute function agentworks_reject_history_mutation()', history_table || '_no_truncate', history_table);
    end loop;
end;
$$;

-- Mutable current-state rows keep immutable identity and ownership.
create function agentworks_protect_environment() returns trigger
    language plpgsql as $$
begin
    if tg_op = 'DELETE' or new.workspace_id <> old.workspace_id or new.id <> old.id or new.name <> old.name
            or new.created_at <> old.created_at or new.description is distinct from old.description
            or new.policy_version not in (old.policy_version, old.policy_version + 1)
            or ((new.promotion_policy_json, new.promotion_policy_digest)
                is distinct from (old.promotion_policy_json, old.promotion_policy_digest))
               <> (new.policy_version = old.policy_version + 1) then
        raise exception 'agentworks:IMMUTABLE_IDENTITY environment identity is immutable and each policy change advances its version by one'
            using errcode = 'restrict_violation';
    end if;
    return new;
end;
$$;
create trigger agent_environment_protect before update or delete on agent_environment
    for each row execute function agentworks_protect_environment();

create function agentworks_protect_deployment() returns trigger
    language plpgsql as $$
begin
    if tg_op = 'DELETE' or new.workspace_id <> old.workspace_id or new.id <> old.id
            or new.definition_id <> old.definition_id or new.environment_id <> old.environment_id
            or new.name <> old.name or new.created_at <> old.created_at
            or new.revision not in (old.revision, old.revision + 1)
            or ((new.desired_release_id, new.current_revision_id, new.current_routing_generation_id)
                is distinct from (old.desired_release_id, old.current_revision_id, old.current_routing_generation_id))
               <> (new.revision = old.revision + 1) then
        raise exception 'agentworks:IMMUTABLE_IDENTITY deployment identity is immutable and desired state changes only with a new generation'
            using errcode = 'restrict_violation';
    end if;
    return new;
end;
$$;
create trigger agent_deployment_protect before update or delete on agent_deployment
    for each row execute function agentworks_protect_deployment();

-- Cohort state follows the ADR-027 graph and every change advances the transition version by one.
create function agentworks_guard_cohort_transition() returns trigger
    language plpgsql as $$
begin
    if tg_op = 'DELETE' or new.workspace_id <> old.workspace_id or new.id <> old.id
            or new.deployment_id <> old.deployment_id or new.revision_id <> old.revision_id
            or new.created_at <> old.created_at then
        raise exception 'agentworks:IMMUTABLE_IDENTITY cohort identity is immutable'
            using errcode = 'restrict_violation';
    end if;
    if new.state = old.state then
        if new.transition_version <> old.transition_version then
            raise exception 'agentworks:INVALID_TRANSITION version changed without a state transition'
                using errcode = 'check_violation';
        end if;
        return new;
    end if;
    if new.transition_version <> old.transition_version + 1 or not (
            (old.state = 'PENDING' and new.state in ('ROUTABLE', 'REVOKED'))
            or (old.state = 'ROUTABLE' and new.state in ('SUSPENDED', 'DRAINING', 'REVOKED'))
            or (old.state = 'SUSPENDED' and new.state in ('ROUTABLE', 'DRAINING', 'REVOKED'))
            or (old.state = 'DRAINING' and new.state in ('QUIESCENT', 'REVOKED'))
            or (old.state = 'QUIESCENT' and new.state in ('DRAINING', 'RETIRED', 'REVOKED'))) then
        raise exception 'agentworks:INVALID_TRANSITION % to % is not a permitted cohort transition', old.state, new.state
            using errcode = 'check_violation';
    end if;
    return new;
end;
$$;
create trigger runtime_cohort_guard before update or delete on runtime_cohort
    for each row execute function agentworks_guard_cohort_transition();

-- Idempotency records only compact; tombstones leave only after their namespace is irreversibly retired.
create function agentworks_guard_idempotency() returns trigger
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
            new.result_status, new.created_at, new.replay_until)
            is distinct from (old.workspace_id, old.namespace, old.key_digest, old.fingerprint_digest,
            old.result_kind, old.result_id, old.result_status, old.created_at, old.replay_until)
            or old.compacted_at is not null or new.result_json is not null then
        raise exception 'agentworks:IMMUTABLE_HISTORY idempotency records may only be compacted once'
            using errcode = 'restrict_violation';
    end if;
    return new;
end;
$$;
create trigger lifecycle_idempotency_guard before update or delete on lifecycle_idempotency
    for each row execute function agentworks_guard_idempotency();

create function agentworks_guard_namespace() returns trigger
    language plpgsql as $$
begin
    if tg_op = 'DELETE' or new.workspace_id <> old.workspace_id or new.namespace <> old.namespace
            or old.state = 'RETIRED' then
        raise exception 'agentworks:NAMESPACE_RETIRED namespace retirement is terminal'
            using errcode = 'restrict_violation';
    end if;
    return new;
end;
$$;
create trigger idempotency_namespace_guard before update or delete on idempotency_namespace
    for each row execute function agentworks_guard_namespace();

-- Cohort fence for run admission and resumable-work writes. It runs as the migration owner so a run-plane
-- role can take the fence locks without any authority to change control-plane state; its search_path is
-- pinned below with pg_temp last so session objects can never shadow these tables. Lock order is
-- deployment, then parent run, then cohort, matching the control plane's deployment-then-cohort order.
-- Control-plane row locks are FOR NO KEY UPDATE, so foreign-key checks (FOR KEY SHARE) never wait on them.
create function agentworks_fence_run() returns trigger
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
                new.continuation_kind, new.idempotency_key_digest, new.request_fingerprint)
                is distinct from (old.workspace_id, old.id, old.version_id, old.release_id, old.deployment_id,
                old.environment_id, old.created_at, old.definition_id, old.revision_id, old.cohort_id, old.admission,
                old.routing_generation_id, old.routing_target_ordinal, old.parent_run_id, old.root_run_id,
                old.continuation_kind, old.idempotency_key_digest, old.request_fingerprint) then
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
create trigger agent_run_fence before insert or update or delete on agent_run
    for each row execute function agentworks_fence_run();
do $$
begin
    execute format('alter function agentworks_fence_run() set search_path = %I, pg_temp', current_schema());
end;
$$;

-- Least-privilege plane roles (ADR-002, ADR-012). The migration creates them when it is allowed to; an
-- installation whose migration user cannot create roles must create both NOLOGIN roles before migrating.
do $$
begin
    if not exists (select 1 from pg_roles where rolname = 'agentworks_control_plane') then
        create role agentworks_control_plane nologin;
    end if;
    if not exists (select 1 from pg_roles where rolname = 'agentworks_run_plane') then
        create role agentworks_run_plane nologin;
    end if;
end;
$$;

grant select on organization, workspace, workspace_membership, agent_definition, agent_version, agent_release,
    release_alias, release_alias_history, agent_environment, promotion_policy_version, agent_deployment,
    lifecycle_decision, deployment_revision, runtime_cohort, cohort_transition, routing_generation, routing_target,
    control_plane_outbox, idempotency_namespace, lifecycle_idempotency, agent_run
    to agentworks_control_plane;
-- The Run Plane reads only what admission and resumption need: never decisions, audit text, outbox, or
-- idempotency results.
grant select on agent_release, agent_deployment, deployment_revision, runtime_cohort, routing_generation,
    routing_target, agent_run to agentworks_run_plane;
grant insert, update on agent_definition, agent_version, agent_release, release_alias, release_alias_history,
    agent_environment, promotion_policy_version, agent_deployment, lifecycle_decision, deployment_revision,
    runtime_cohort, cohort_transition, routing_generation, routing_target, control_plane_outbox,
    idempotency_namespace, lifecycle_idempotency to agentworks_control_plane;
grant delete on release_alias, lifecycle_idempotency to agentworks_control_plane;
do $$
begin
    execute format('grant usage on sequence %s to agentworks_control_plane',
        pg_get_serial_sequence('control_plane_outbox', 'sequence_number'));
end;
$$;
-- The Run Plane admits runs and records run lifecycle; it cannot author releases, environments,
-- deployments, routes, cohorts, decisions, or desired state.
grant insert on agent_run to agentworks_run_plane;
grant update (lifecycle) on agent_run to agentworks_run_plane;
