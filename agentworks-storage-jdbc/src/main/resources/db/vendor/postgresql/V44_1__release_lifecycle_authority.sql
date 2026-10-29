-- Issue #79: only the Control Plane owns desired release lifecycle and revocation authority. Tenant isolation follows
-- the canonical lifecycle contract: every repository statement carries the transaction-bound workspace predicate,
-- while database grants prevent the Run Plane from reading or forging this authority.

create function agentworks_guard_release_lifecycle() returns trigger language plpgsql as $$
begin
    if tg_op = 'DELETE' or new.workspace_id <> old.workspace_id or new.release_id <> old.release_id
            or new.lifecycle_version <> old.lifecycle_version + 1 or not (
            (old.state = 'PENDING' and new.state in ('AVAILABLE', 'SUSPENDED', 'REVOKED'))
            or (old.state = 'AVAILABLE' and new.state in ('SUSPENDED', 'REVOKED', 'RETIRED'))
            or (old.state = 'SUSPENDED' and new.state in ('AVAILABLE', 'REVOKED', 'RETIRED'))) then
        raise exception 'agentworks:INVALID_RELEASE_TRANSITION % to %', old.state, new.state
            using errcode = 'check_violation';
    end if;
    return new;
end;
$$;
create trigger release_lifecycle_guard before update or delete on release_lifecycle
    for each row execute function agentworks_guard_release_lifecycle();
create trigger release_revocation_append_only before update or delete on release_revocation
    for each row execute function agentworks_reject_history_mutation();
create trigger release_revocation_no_truncate before truncate on release_revocation
    for each statement execute function agentworks_reject_history_mutation();
create trigger release_lifecycle_event_append_only before update or delete on release_lifecycle_event
    for each row execute function agentworks_reject_history_mutation();
create trigger release_lifecycle_event_no_truncate before truncate on release_lifecycle_event
    for each statement execute function agentworks_reject_history_mutation();

grant select, insert, update on release_lifecycle to agentworks_control_plane;
grant select, insert on release_revocation, release_lifecycle_event to agentworks_control_plane;
revoke all on release_lifecycle, release_revocation, release_lifecycle_event from agentworks_run_plane;
