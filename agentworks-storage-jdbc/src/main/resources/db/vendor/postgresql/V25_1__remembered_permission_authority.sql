-- Issue #39 PostgreSQL authority: history is append-only and only the Control Plane may author it.
create trigger remembered_permission_rule_append_only before update or delete on remembered_permission_rule
    for each row execute function agentworks_guard_tool_registry_history();
create trigger remembered_permission_rule_no_truncate before truncate on remembered_permission_rule
    for each statement execute function agentworks_guard_tool_registry_history();

grant select, insert on remembered_permission_rule to agentworks_control_plane;
grant select on remembered_permission_rule to agentworks_run_plane;
