-- Issue #30 PostgreSQL defense in depth. The application exposes no update or delete path for published
-- versions; these triggers make the reference database reject direct mutation and digest forgery as well.

create function agentworks_verify_agent_version() returns trigger
    language plpgsql as $$
begin
    if tg_op = 'INSERT' then
        if new.spec_digest <> 'sha256:' || encode(sha256(convert_to(new.spec_json, 'UTF8')), 'hex') then
            raise exception 'agent_version % digest does not match its canonical specification', new.id
                using errcode = 'check_violation';
        end if;
        return new;
    end if;
    raise exception 'agent_version rows are immutable once published'
        using errcode = 'restrict_violation';
end;
$$;

create trigger agent_version_verify_insert
    before insert on agent_version
    for each row execute function agentworks_verify_agent_version();

create trigger agent_version_reject_update_delete
    before update or delete on agent_version
    for each row execute function agentworks_verify_agent_version();

create trigger agent_version_reject_truncate
    before truncate on agent_version
    for each statement execute function agentworks_verify_agent_version();

create function agentworks_protect_agent_definition_identity() returns trigger
    language plpgsql as $$
begin
    if new.workspace_id <> old.workspace_id or new.id <> old.id or new.name <> old.name
            or new.created_at <> old.created_at then
        raise exception 'agent_definition identity and workspace ownership are immutable'
            using errcode = 'restrict_violation';
    end if;
    return new;
end;
$$;

create trigger agent_definition_protect_identity
    before update on agent_definition
    for each row execute function agentworks_protect_agent_definition_identity();
