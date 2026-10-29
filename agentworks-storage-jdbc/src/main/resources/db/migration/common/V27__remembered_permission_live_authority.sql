-- Issue #39: every tool use loads the latest permission version in one tenant-bound lookup.
create index remembered_permission_live_lookup_idx on remembered_permission_rule(
    organization_id, scope_level, scope_id, permission_id, version desc
);
