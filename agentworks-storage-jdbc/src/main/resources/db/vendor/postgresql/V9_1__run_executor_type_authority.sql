-- Issue #34: backfill agent_run.executor_type (V9) from each run's pinned release manifest and make it an immutable
-- creation fact enforced by the database.
--
-- The run guards (agent_run_fence and the deferred history checks) reject updates of an existing run that are not a
-- lifecycle transition, so the backfill disables the table's user triggers for this one statement, inside this
-- migration's transaction, and re-enables them at once; no guard is changed or weakened. Afterwards:
--   * a durable run (one with a trace identity) always carries an executor type;
--   * the executor type is written only at creation, equal to the pinned release manifest's executor.type, and a
--     continuation inherits its parent's;
--   * nothing can change it later (IMMUTABLE_PIN); the Run Plane role has no update privilege on the column either.
alter table agent_run disable trigger user;
update agent_run r set executor_type = rel.manifest_json::jsonb #>> '{executor,type}'
    from agent_release rel
    where rel.workspace_id = r.workspace_id and rel.id = r.release_id and r.executor_type is null;
alter table agent_run enable trigger user;

alter table agent_run add constraint agent_run_executor_type_ck
    check (trace_id is null or executor_type is not null);
create index agent_run_claim_executor_idx on agent_run (executor_type, created_at, id)
    where lifecycle in ('QUEUED', 'ACTIVE');

-- Runs after agent_run_fence (triggers of one timing fire in name order), so the fence's own refusals keep their
-- precedence.
create function agentworks_fence_run_executor_type() returns trigger
    language plpgsql security definer as $$
declare
    pinned varchar(128);
    parent_type varchar(128);
begin
    if tg_op = 'UPDATE' then
        if new.executor_type is distinct from old.executor_type then
            raise exception 'agentworks:IMMUTABLE_PIN run pins require explicit migration authority'
                using errcode = 'restrict_violation';
        end if;
        return new;
    end if;
    if new.trace_id is null then
        -- Runs admitted before the durable engine carry no creation facts and are never claimed.
        return new;
    end if;
    select rel.manifest_json::jsonb #>> '{executor,type}' into pinned from agent_release rel
        where rel.workspace_id = new.workspace_id and rel.id = new.release_id;
    if new.executor_type is distinct from pinned then
        raise exception 'agentworks:CREATION_FACTS_REQUIRED the executor type is the pinned release''s'
            using errcode = 'check_violation';
    end if;
    if new.admission <> 'ROOT' then
        select r.executor_type into parent_type from agent_run r
            where r.workspace_id = new.workspace_id and r.id = new.parent_run_id;
        if found and new.executor_type is distinct from parent_type then
            raise exception 'agentworks:CONTINUATION_PROVENANCE_MISMATCH a continuation inherits its parent''s executor type'
                using errcode = 'check_violation';
        end if;
    end if;
    return new;
end;
$$;
create trigger agent_run_fence_executor_type before insert or update on agent_run
    for each row execute function agentworks_fence_run_executor_type();

-- Owned by the fence owner like every other guard (V4_1, V5_1): pinned search path, no PUBLIC execute.
grant select on agent_release to agentworks_fence_owner;
do $$
begin
    execute format('grant create on schema %I to agentworks_fence_owner', current_schema());
    execute format('alter function agentworks_fence_run_executor_type() set search_path = %I, pg_temp', current_schema());
    alter function agentworks_fence_run_executor_type() owner to agentworks_fence_owner;
    revoke execute on function agentworks_fence_run_executor_type() from public;
    execute format('revoke create on schema %I from agentworks_fence_owner', current_schema());
end;
$$;
