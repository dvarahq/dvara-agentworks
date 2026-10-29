-- Issue #39: immutable, versioned remembered-permission decisions. No secret or request payload is stored.
create table remembered_permission_rule (
    permission_id uuid not null,
    version bigint not null,
    organization_id uuid not null references organization(id),
    scope_level varchar(16) not null,
    scope_id uuid not null,
    actor_id uuid not null,
    agent_definition_id uuid,
    release_id uuid,
    capability varchar(128) not null,
    resource varchar(512) not null,
    action varchar(64) not null,
    environment_id uuid not null,
    effect varchar(8) not null,
    maximum_risk varchar(16) not null,
    valid_from timestamp with time zone not null,
    expires_at timestamp with time zone not null,
    revoked_at timestamp with time zone,
    created_at timestamp with time zone not null default current_timestamp,
    primary key (permission_id, version),
    constraint remembered_permission_version_ck check (version > 0),
    constraint remembered_permission_scope_ck check (scope_level in ('ORGANIZATION', 'WORKSPACE')),
    constraint remembered_permission_effect_ck check (effect in ('ALLOW', 'DENY')),
    constraint remembered_permission_risk_ck check (maximum_risk in ('LOW', 'MEDIUM', 'HIGH', 'CRITICAL')),
    constraint remembered_permission_time_ck check (expires_at > valid_from),
    constraint remembered_permission_revocation_ck check (revoked_at is null or revoked_at >= valid_from)
);

create index remembered_permission_lookup_idx on remembered_permission_rule(
    organization_id, scope_level, scope_id, actor_id, capability, resource, action, environment_id,
    permission_id, version
);
