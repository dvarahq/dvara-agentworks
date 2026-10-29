-- Issue #55: explicit Control Plane promotion approvals, bound to the exact environment policy and release.
create table promotion_approval (
    workspace_id uuid not null,
    id uuid not null,
    environment_id uuid not null,
    policy_digest varchar(71) not null,
    release_id uuid not null,
    status varchar(16) not null,
    requested_by varchar(256) not null,
    approved_by varchar(256),
    created_at timestamp with time zone not null,
    approved_at timestamp with time zone,
    primary key (workspace_id, id),
    foreign key (workspace_id, environment_id) references agent_environment(workspace_id, id),
    foreign key (workspace_id, release_id) references agent_release(workspace_id, id),
    constraint promotion_approval_status_ck check
        ((status = 'REQUESTED' and approved_by is null and approved_at is null) or
         (status = 'APPROVED' and approved_by is not null and approved_at is not null))
);
create index promotion_approval_binding_idx on promotion_approval
    (workspace_id, environment_id, policy_digest, release_id, status);
