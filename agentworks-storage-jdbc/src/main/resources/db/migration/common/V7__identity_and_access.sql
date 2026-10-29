-- Issue #74: local identities, workspace membership roles, browser sessions, API tokens, the one-time
-- installation bootstrap, and the authentication audit trail (ADR-012).
-- Credential material never enters these tables: passwords are stored as adaptive hashes, and session and API
-- token values only as SHA-256 digests of 256-bit random values.

-- A user belongs to exactly one organization. Tenant and resource authority stays in workspace_membership, so a
-- later external identity provider links to these rows instead of replacing them.
create table user_account (
    id uuid primary key,
    organization_id uuid not null references organization(id),
    username varchar(64) not null,
    display_name varchar(200) not null,
    organization_role varchar(16) not null,
    identity_provider varchar(64) default 'local' not null,
    password_hash varchar(512),
    password_changed_at timestamp with time zone,
    must_change_password boolean default false not null,
    status varchar(16) default 'ACTIVE' not null,
    failed_login_count integer default 0 not null,
    locked_until timestamp with time zone,
    created_at timestamp with time zone not null,
    updated_at timestamp with time zone not null,
    constraint user_account_username_uk unique (username),
    constraint user_account_organization_uk unique (organization_id, id),
    constraint user_account_role_ck check (organization_role in ('ADMIN', 'MEMBER')),
    constraint user_account_status_ck check (status in ('ACTIVE', 'DISABLED')),
    constraint user_account_failures_ck check (failed_login_count >= 0),
    constraint user_account_local_password_ck check (identity_provider <> 'local' or password_hash is not null)
);

-- Schema V1 bootstrapped a placeholder OWNER membership for a subject without any identity. It carried no
-- credential and granted nothing, so it is removed before memberships must reference real users.
delete from workspace_membership;
alter table workspace_membership add column updated_at timestamp with time zone;
alter table workspace_membership add constraint workspace_membership_subject_fk
    foreign key (organization_id, subject_id) references user_account(organization_id, id);
alter table workspace_membership add constraint workspace_membership_role_ck
    check (membership_role in ('ADMIN', 'AUTHOR', 'OPERATOR', 'VIEWER'));
create index workspace_membership_subject_idx on workspace_membership(subject_id);

-- Browser sessions. The cookie carries a random value; only its digest is stored. A session is bound to one
-- user and at most one selected workspace, which must be one of the user's memberships: removing that
-- membership deletes the sessions selected into it. Rotation replaces the row, so identity never changes.
create table auth_session (
    id uuid primary key,
    session_digest varchar(64) not null,
    organization_id uuid not null,
    user_id uuid not null,
    workspace_id uuid,
    created_at timestamp with time zone not null,
    last_seen_at timestamp with time zone not null,
    expires_at timestamp with time zone not null,
    revoked_at timestamp with time zone,
    revoked_reason varchar(32),
    constraint auth_session_digest_uk unique (session_digest),
    constraint auth_session_user_fk foreign key (organization_id, user_id) references user_account(organization_id, id),
    constraint auth_session_workspace_fk foreign key (workspace_id, organization_id) references workspace(id, organization_id),
    constraint auth_session_membership_fk foreign key (workspace_id, user_id)
        references workspace_membership(workspace_id, subject_id) on delete cascade,
    constraint auth_session_expiry_ck check (expires_at > created_at),
    constraint auth_session_revocation_ck check ((revoked_at is null) = (revoked_reason is null))
);
create index auth_session_user_idx on auth_session(user_id, created_at);

-- Workspace-bound API tokens owned by a member. Effective authority is the intersection of the token role and
-- the owner's current membership role; removing the membership deletes its tokens.
create table api_token (
    id uuid primary key,
    token_digest varchar(64) not null,
    token_prefix varchar(16) not null,
    name varchar(100) not null,
    organization_id uuid not null,
    workspace_id uuid not null,
    user_id uuid not null,
    token_role varchar(16) not null,
    created_at timestamp with time zone not null,
    expires_at timestamp with time zone not null,
    last_used_at timestamp with time zone,
    revoked_at timestamp with time zone,
    revoked_by uuid,
    constraint api_token_digest_uk unique (token_digest),
    constraint api_token_user_fk foreign key (organization_id, user_id) references user_account(organization_id, id),
    constraint api_token_workspace_fk foreign key (workspace_id, organization_id) references workspace(id, organization_id),
    constraint api_token_membership_fk foreign key (workspace_id, user_id)
        references workspace_membership(workspace_id, subject_id) on delete cascade,
    constraint api_token_role_ck check (token_role in ('ADMIN', 'AUTHOR', 'OPERATOR', 'VIEWER')),
    constraint api_token_expiry_ck check (expires_at > created_at)
);
create index api_token_workspace_idx on api_token(workspace_id, user_id);

-- Exactly one installation bootstrap path: a single row whose one-time token digest is cleared on completion.
create table installation_bootstrap (
    id integer primary key,
    state varchar(16) not null,
    token_digest varchar(64),
    issued_at timestamp with time zone not null,
    expires_at timestamp with time zone not null,
    completed_at timestamp with time zone,
    completed_by uuid,
    constraint installation_bootstrap_singleton_ck check (id = 1),
    constraint installation_bootstrap_state_ck check (state in ('PENDING', 'COMPLETED')),
    constraint installation_bootstrap_token_ck check ((state = 'PENDING') = (token_digest is not null))
);

-- Append-only authentication and administration audit trail. Failed logins for unknown usernames carry no
-- organization. Detail values are fixed reason codes, never request content or credential material.
create table auth_audit_event (
    id uuid primary key,
    occurred_at timestamp with time zone not null,
    event_type varchar(48) not null,
    outcome varchar(16) not null,
    organization_id uuid,
    workspace_id uuid,
    actor_id uuid,
    authentication_method varchar(32),
    credential_id uuid,
    target_type varchar(32),
    target_id uuid,
    detail varchar(200),
    remote_address varchar(64),
    constraint auth_audit_event_outcome_ck check (outcome in ('SUCCEEDED', 'FAILED', 'DENIED'))
);
create index auth_audit_event_organization_idx on auth_audit_event(organization_id, occurred_at);
