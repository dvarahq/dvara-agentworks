-- Issue #201 (PostgreSQL).

-- Medium 1: the Control Plane reads and records the first sight of a bootstrap reissue value (V20), like the consumed
-- values (V13_1), and can neither remove one nor change what it records, except to forget which token it named once
-- the bootstrap completes (the token's own digest is cleared then too).
grant select, insert on installation_bootstrap_reissue_seen to agentworks_control_plane;
grant update (target_digest) on installation_bootstrap_reissue_seen to agentworks_control_plane;

-- Low 1: the Run Plane records who repeated a standing pause request with a single INSERT ... ON CONFLICT DO NOTHING,
-- which needs no read. Rows go with their request: the foreign key's ON DELETE CASCADE runs as the table owner, so the
-- Run Plane needs no delete grant either.
grant insert on agent_run_pause_repeat to agentworks_run_plane;

-- A repeat is recorded only for a run with a standing request (the foreign key) and only for an actor other than the
-- one whose request stands, so a Run Plane session cannot pre-record the standing requester. The guard runs with the
-- privileges of the Run Plane, which reads pause requests already (V15_1).
create function agentworks_guard_run_pause_repeat() returns trigger
    language plpgsql as $$
begin
    if exists (select 1 from agent_run_pause_request p where p.workspace_id = new.workspace_id
            and p.run_id = new.run_id and p.requested_by = new.actor) then
        raise exception 'agentworks:PAUSE_REPEAT_BY_REQUESTER the standing requester does not repeat its own request'
            using errcode = 'check_violation';
    end if;
    return new;
end;
$$;
do $$
begin
    execute format('alter function agentworks_guard_run_pause_repeat() set search_path = %I, pg_temp',
        current_schema());
end;
$$;
create trigger agent_run_pause_repeat_guard before insert on agent_run_pause_repeat
    for each row execute function agentworks_guard_run_pause_repeat();
