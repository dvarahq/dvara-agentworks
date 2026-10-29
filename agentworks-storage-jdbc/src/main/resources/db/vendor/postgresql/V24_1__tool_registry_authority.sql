-- Issue #38 PostgreSQL authority: published versions and pack membership are append-only, schema hashes are
-- verified from the exact stored schema, lifecycle transitions are bounded, and only the Control Plane writes.

create function agentworks_guard_tool_registry_history() returns trigger
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
    foreach history_table in array array['agent_tool_version', 'agent_tool_tag', 'agent_tool_compatibility',
            'capability_pack_version', 'capability_pack_tool'] loop
        execute format('create trigger %I before update or delete on %I for each row '
            'execute function agentworks_guard_tool_registry_history()', history_table || '_append_only', history_table);
        execute format('create trigger %I before truncate on %I for each statement '
            'execute function agentworks_guard_tool_registry_history()', history_table || '_no_truncate', history_table);
    end loop;
end;
$$;

create function agentworks_verify_tool_schema() returns trigger
    language plpgsql as $$
begin
    if new.schema_hash <> ('sha256:' || encode(sha256(convert_to(new.input_schema, 'UTF8')), 'hex')) then
        raise exception 'agentworks:SCHEMA_HASH_MISMATCH tool schema hash does not match stored schema'
            using errcode = 'check_violation';
    end if;
    return new;
end;
$$;
create trigger agent_tool_version_verify_insert before insert on agent_tool_version
    for each row execute function agentworks_verify_tool_schema();

create function agentworks_guard_capability_lifecycle() returns trigger
    language plpgsql as $$
begin
    if tg_op = 'DELETE' or (new.workspace_id, new.namespace, new.name, new.version)
            is distinct from (old.workspace_id, old.namespace, old.name, old.version)
            or old.state = 'DENIED'
            or (old.state, new.state) not in (('AVAILABLE', 'ACTIVE'), ('AVAILABLE', 'DISABLED'),
                ('AVAILABLE', 'DENIED'), ('ACTIVE', 'DISABLED'), ('ACTIVE', 'DENIED'),
                ('DISABLED', 'ACTIVE'), ('DISABLED', 'DENIED')) then
        raise exception 'agentworks:INVALID_TOOL_LIFECYCLE % to %', old.state, new.state
            using errcode = 'check_violation';
    end if;
    return new;
end;
$$;
create trigger agent_tool_lifecycle_guard before update or delete on agent_tool_lifecycle
    for each row execute function agentworks_guard_capability_lifecycle();

create function agentworks_guard_pack_lifecycle() returns trigger
    language plpgsql as $$
begin
    if tg_op = 'DELETE' or (new.workspace_id, new.pack_id, new.version)
            is distinct from (old.workspace_id, old.pack_id, old.version)
            or old.state = 'DENIED'
            or (old.state, new.state) not in (('AVAILABLE', 'ACTIVE'), ('AVAILABLE', 'DISABLED'),
                ('AVAILABLE', 'DENIED'), ('ACTIVE', 'DISABLED'), ('ACTIVE', 'DENIED'),
                ('DISABLED', 'ACTIVE'), ('DISABLED', 'DENIED')) then
        raise exception 'agentworks:INVALID_PACK_LIFECYCLE % to %', old.state, new.state
            using errcode = 'check_violation';
    end if;
    return new;
end;
$$;
create trigger capability_pack_lifecycle_guard before update or delete on capability_pack_lifecycle
    for each row execute function agentworks_guard_pack_lifecycle();

grant select, insert on agent_tool_version, agent_tool_lifecycle, agent_tool_tag, agent_tool_compatibility,
    capability_pack_version, capability_pack_lifecycle, capability_pack_tool to agentworks_control_plane;
grant update (state, updated_at) on agent_tool_lifecycle, capability_pack_lifecycle to agentworks_control_plane;
grant select on agent_tool_version, agent_tool_lifecycle, agent_tool_tag, agent_tool_compatibility,
    capability_pack_version, capability_pack_lifecycle, capability_pack_tool to agentworks_run_plane;
