-- Issue #39: Control Plane locks immutable permission identities while appending a new history version.
create trigger remembered_permission_identity_append_only before update or delete on remembered_permission_identity
    for each row execute function agentworks_reject_history_mutation();
create trigger remembered_permission_identity_no_truncate before truncate on remembered_permission_identity
    for each statement execute function agentworks_reject_history_mutation();

-- UPDATE is needed only for SELECT ... FOR UPDATE; the trigger rejects every actual mutation.
grant select, insert, update on remembered_permission_identity to agentworks_control_plane;
