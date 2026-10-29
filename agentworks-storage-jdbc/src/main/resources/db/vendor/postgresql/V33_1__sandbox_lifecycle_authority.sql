-- Issue #40: PostgreSQL owns sandbox state transitions and atomically mirrors them into cohort_work_item.
do $$
begin
    execute format('grant create on schema %I to agentworks_fence_owner', current_schema());
end;
$$;
set role agentworks_fence_owner;

create function agentworks_fence_sandbox_resource() returns trigger
    language plpgsql security definer as $$
begin
    if new.provider !~ '^[a-z][a-z0-9-]{0,31}$' then
        raise exception 'agentworks:INVALID_SANDBOX_PROVIDER %', new.provider
            using errcode = 'check_violation';
    end if;
    if new.last_failure_code is not null and new.last_failure_code !~ '^[A-Z][A-Z0-9_]{0,63}$' then
        raise exception 'agentworks:INVALID_SANDBOX_FAILURE_CODE %', new.last_failure_code
            using errcode = 'check_violation';
    end if;
    if tg_op = 'DELETE' then
        raise exception 'agentworks:IMMUTABLE_HISTORY sandbox resources cannot be deleted'
            using errcode = 'restrict_violation';
    end if;
    if tg_op = 'UPDATE' then
        if (new.workspace_id, new.id, new.work_item_id, new.organization_id, new.run_id, new.release_id,
                new.revision_id, new.cohort_id, new.provider, new.expires_at, new.created_at)
                is distinct from
                (old.workspace_id, old.id, old.work_item_id, old.organization_id, old.run_id, old.release_id,
                old.revision_id, old.cohort_id, old.provider, old.expires_at, old.created_at) then
            raise exception 'agentworks:IMMUTABLE_IDENTITY sandbox ownership and lifetime are immutable'
                using errcode = 'restrict_violation';
        end if;
        if old.state = 'TERMINAL' then
            raise exception 'agentworks:TERMINAL_SANDBOX terminal sandbox state is immutable'
                using errcode = 'check_violation';
        end if;
        if not ((old.state = 'PROVISIONING' and new.state in ('ACTIVE', 'CLEANUP_PENDING', 'CLEANING'))
                or (old.state = 'ACTIVE' and new.state in ('CLEANUP_PENDING', 'CLEANING'))
                or (old.state = 'CLEANUP_PENDING' and new.state = 'CLEANING')
                or (old.state = 'CLEANING' and new.state in ('CLEANING', 'CLEANUP_PENDING', 'TERMINAL'))) then
            raise exception 'agentworks:INVALID_SANDBOX_TRANSITION % -> %', old.state, new.state
                using errcode = 'check_violation';
        end if;
    end if;

    if new.state = 'CLEANING' then
        if new.cleanup_fence <= coalesce(old.cleanup_fence, 0) or new.cleanup_until <= clock_timestamp() then
            raise exception 'agentworks:INVALID_CLEANUP_LEASE' using errcode = 'check_violation';
        end if;
    elsif new.cleanup_owner is not null or new.cleanup_until is not null then
        raise exception 'agentworks:INVALID_CLEANUP_LEASE' using errcode = 'check_violation';
    end if;
    return new;
end;
$$;

create function agentworks_sync_sandbox_work_item() returns trigger
    language plpgsql security definer as $$
begin
    if tg_op = 'INSERT' then
        insert into cohort_work_item(workspace_id, id, cohort_id, run_id, category, subject_id, state,
                created_at, state_changed_at, terminal_at)
            values (new.workspace_id, new.work_item_id, new.cohort_id, new.run_id, 'SANDBOX', new.id, 'PENDING',
                new.created_at, new.created_at, null);
    elsif new.state = 'ACTIVE' and old.state = 'PROVISIONING' then
        update cohort_work_item set state = 'ACTIVE', state_changed_at = new.updated_at
            where workspace_id = new.workspace_id and id = new.work_item_id and state = 'PENDING';
    elsif new.state = 'TERMINAL' then
        update cohort_work_item set state = 'TERMINAL', state_changed_at = new.updated_at,
                terminal_at = new.terminal_at
            where workspace_id = new.workspace_id and id = new.work_item_id and state <> 'TERMINAL';
    end if;
    return new;
end;
$$;

do $$
begin
    execute format('alter function %I() set search_path = %I, pg_temp',
        'agentworks_fence_sandbox_resource', current_schema());
    execute format('alter function %I() set search_path = %I, pg_temp',
        'agentworks_sync_sandbox_work_item', current_schema());
end;
$$;

reset role;
do $$
begin
    execute format('revoke create on schema %I from agentworks_fence_owner', current_schema());
end;
$$;

revoke all on function agentworks_fence_sandbox_resource() from public;
revoke all on function agentworks_sync_sandbox_work_item() from public;
create trigger sandbox_resource_fence before insert or update or delete on sandbox_resource
    for each row execute function agentworks_fence_sandbox_resource();
create trigger sandbox_resource_work_item after insert or update on sandbox_resource
    for each row execute function agentworks_sync_sandbox_work_item();
create trigger sandbox_resource_no_truncate before truncate on sandbox_resource
    for each statement execute function agentworks_reject_history_mutation();

grant select, insert on sandbox_resource to agentworks_run_plane;
grant update (state, cleanup_after, cleanup_owner, cleanup_fence, cleanup_until, cleanup_failures,
    last_failure_code, activated_at, updated_at, terminal_at) on sandbox_resource to agentworks_run_plane;
grant select on sandbox_resource to agentworks_control_plane;
