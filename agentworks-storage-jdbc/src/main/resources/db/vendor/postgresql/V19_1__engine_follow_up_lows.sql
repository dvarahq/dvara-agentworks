-- Engine follow-up lows (PostgreSQL); each section names its issue. V9_1 and V17_1 are applied and unchanged,
-- and this migration supersedes parts of them.

-- #198: the run-control audit append function (V17_1).
-- * Its (event type, detail) check was not NULL-safe: a NULL detail made the check NULL, which passed. A NULL event
--   type or detail is now refused explicitly.
-- * It is owned by its own NOLOGIN role, agentworks_run_control_auditor, which holds exactly what it needs (read the
--   ids of a workspace and a run and a workspace's organization, append an audit row), instead of the shared fence
--   owner, whose V17_1 grants are revoked.
-- * UPGRADE PREREQUISITE. The migration user creates the role when it holds CREATEROLE. Otherwise a database
--   administrator must first run, with the migration user's name:
--       CREATE ROLE agentworks_run_control_auditor NOLOGIN;
--       GRANT agentworks_run_control_auditor TO <migration user> WITH ADMIN OPTION;
--   Without that, this migration stops before changing anything with agentworks:ROLE_REQUIRED, naming both
--   statements, and the upgrade is retried after running them.
-- * The migration user needs membership of the role only to hand it the function. A membership this migration
--   granted itself is revoked again at the end; one a database administrator granted can be revoked only by them.
do $$
begin
    if not exists (select 1 from pg_roles where rolname = 'agentworks_run_control_auditor') then
        if not exists (select 1 from pg_roles where rolname = current_user and (rolcreaterole or rolsuper)) then
            raise exception 'agentworks:ROLE_REQUIRED the NOLOGIN role agentworks_run_control_auditor does not exist '
                'and % cannot create roles. As a database administrator, run: CREATE ROLE '
                'agentworks_run_control_auditor NOLOGIN; GRANT agentworks_run_control_auditor TO % WITH ADMIN '
                'OPTION; then run the migration again.', current_user, current_user
                using errcode = 'insufficient_privilege';
        end if;
        create role agentworks_run_control_auditor nologin;
    -- #201: on PostgreSQL 15 and older a CREATEROLE migration user may grant itself any role that is not a superuser,
    -- as the block below does, so it is accepted without a membership. Roles belong to the cluster, and this
    -- migration revokes the membership it granted itself, so a second database of the cluster, or this one after
    -- flyway clean, finds the role without it.
    elsif not pg_has_role(current_user, 'agentworks_run_control_auditor', 'MEMBER WITH ADMIN OPTION')
            and not pg_has_role(current_user, 'agentworks_run_control_auditor',
                case when current_setting('server_version_num')::integer >= 160000 then 'SET' else 'MEMBER' end)
            and not (current_setting('server_version_num')::integer < 160000
                and exists (select 1 from pg_roles where rolname = current_user and rolcreaterole)) then
        raise exception 'agentworks:ROLE_REQUIRED % can neither become nor administer the role '
            'agentworks_run_control_auditor. As a database administrator, run: GRANT agentworks_run_control_auditor '
            'TO % WITH ADMIN OPTION; then run the migration again.', current_user, current_user
            using errcode = 'insufficient_privilege';
    end if;
    execute format('grant usage on schema %I to agentworks_run_control_auditor', current_schema());
end;
$$;

-- Column privileges only: the ids that bind a record to a run of its workspace, and the workspace's organization.
grant select (id, organization_id) on workspace to agentworks_run_control_auditor;
grant select (workspace_id, id) on agent_run to agentworks_run_control_auditor;
grant insert on auth_audit_event to agentworks_run_control_auditor;

create or replace function agentworks_append_run_control_audit(p_workspace_id uuid, p_run_id uuid,
        p_event_type varchar, p_detail varchar, p_actor_id uuid, p_authentication_method varchar, p_credential_id uuid,
        p_remote_address varchar) returns uuid
    language plpgsql security definer as $$
declare
    organization uuid;
    appended uuid := gen_random_uuid();
begin
    if p_event_type is null or p_detail is null
            or not ((p_event_type = 'RUN_PAUSE_WITHDRAWN' and p_detail = 'WITHDRAWN')
                or (p_event_type = 'RUN_PAUSE_REQUESTED'
                    and p_detail in ('PAUSED', 'ALREADY_PAUSED', 'REQUESTED', 'REPEATED'))) then
        raise exception 'agentworks:UNAUTHORIZED_AUDIT_WRITER % with detail % is not a run-control record',
            p_event_type, p_detail using errcode = 'insufficient_privilege';
    end if;
    select w.organization_id into organization from workspace w where w.id = p_workspace_id;
    if not found or not exists (select 1 from agent_run r where r.workspace_id = p_workspace_id and r.id = p_run_id) then
        raise exception 'agentworks:UNAUTHORIZED_AUDIT_WRITER the run is not a run of the workspace'
            using errcode = 'insufficient_privilege';
    end if;
    insert into auth_audit_event(id, occurred_at, event_type, outcome, organization_id, workspace_id, actor_id,
            authentication_method, credential_id, target_type, target_id, detail, remote_address)
        values (appended, current_timestamp, p_event_type, 'SUCCEEDED', organization, p_workspace_id, p_actor_id,
            p_authentication_method, p_credential_id, 'RUN', p_run_id, p_detail, p_remote_address);
    return appended;
end;
$$;

do $$
declare
    signature text := 'agentworks_append_run_control_audit(uuid, uuid, varchar, varchar, uuid, varchar, uuid, varchar)';
    self_granted boolean := false;
begin
    -- Transferring ownership requires that the migration user can become the new owner (see V4_1).
    if current_setting('server_version_num')::integer >= 160000 then
        if not pg_has_role(current_user, 'agentworks_run_control_auditor', 'SET') then
            execute format('grant agentworks_run_control_auditor to %I with set true', current_user);
            self_granted := true;
        end if;
    elsif not pg_has_role(current_user, 'agentworks_run_control_auditor', 'MEMBER') then
        execute format('grant agentworks_run_control_auditor to %I', current_user);
        self_granted := true;
    end if;
    execute format('grant create on schema %I to agentworks_run_control_auditor', current_schema());
    execute format('alter function %s set search_path = %I, pg_temp', signature, current_schema());
    execute format('alter function %s owner to agentworks_run_control_auditor', signature);
    execute format('revoke execute on function %s from public', signature);
    execute format('grant execute on function %s to agentworks_run_plane', signature);
    execute format('revoke create on schema %I from agentworks_run_control_auditor', current_schema());
    -- The membership this migration granted itself served only the ownership transfer.
    if self_granted then
        execute format('revoke agentworks_run_control_auditor from %I', current_user);
    end if;
end;
$$;

revoke select on workspace from agentworks_fence_owner;
revoke insert on auth_audit_event from agentworks_fence_owner;

-- #181: the executor-type guard (V9_1) fired a SECURITY DEFINER row trigger on every agent_run write, although only an
-- insert or an update that names executor_type can set or change it. It now fires only for those: an update whose SET
-- list omits executor_type cannot change it, so claims, heartbeats, and every other run write skip the call. The
-- function itself is unchanged.
-- Replacing the trigger locks agent_run exclusively, and every run write queues behind a waiting request. Each
-- attempt therefore waits at most 2 seconds (lock_timeout); a timed-out attempt is rolled back to its savepoint, lets
-- the queued writes through for a moment, and is retried, up to 5 attempts (#201). If all of them time out, the upgrade
-- fails (SQLSTATE 55P03), the whole migration rolls back, and a restart retries it, ideally when fewer runs are active.
do $$
declare
    attempt integer := 1;
begin
    loop
        begin
            perform set_config('lock_timeout', '2s', true);
            drop trigger agent_run_fence_executor_type on agent_run;
            create trigger agent_run_fence_executor_type before insert or update of executor_type on agent_run
                for each row execute function agentworks_fence_run_executor_type();
            exit;
        exception when lock_not_available then
            if attempt >= 5 then
                raise;
            end if;
        end;
        perform pg_sleep(0.25 * attempt);
        attempt := attempt + 1;
    end loop;
    perform set_config('lock_timeout', '0', true);
end;
$$;
