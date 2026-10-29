-- Issue #39: one immutable identity row provides a safe serialization lock for append-only permission history.
create table remembered_permission_identity (
    permission_id uuid primary key,
    organization_id uuid not null references organization(id),
    scope_level varchar(16) not null,
    scope_id uuid not null,
    created_at timestamp with time zone not null default current_timestamp,
    constraint remembered_permission_identity_scope_ck check (scope_level in ('ORGANIZATION', 'WORKSPACE')),
    unique (permission_id, organization_id, scope_level, scope_id)
);

insert into remembered_permission_identity(permission_id, organization_id, scope_level, scope_id)
select distinct permission_id, organization_id, scope_level, scope_id from remembered_permission_rule;

alter table remembered_permission_rule add constraint remembered_permission_identity_fk
    foreign key (permission_id, organization_id, scope_level, scope_id)
    references remembered_permission_identity(permission_id, organization_id, scope_level, scope_id);
