-- Issue #79: authoritative release verification/lifecycle state and immutable trust revocations.
-- Release-wide administrative decisions do not belong to one environment or definition. Keep them in the same
-- immutable decision/outbox journal while making only those two deployment-specific dimensions optional.
alter table lifecycle_decision alter column environment_id drop not null;
alter table lifecycle_decision alter column definition_id drop not null;
alter table lifecycle_decision drop constraint lifecycle_decision_kind_ck;
alter table lifecycle_decision add constraint lifecycle_decision_kind_ck check (kind in
    ('PROMOTION', 'ROLLBACK', 'SUSPEND', 'RESUME', 'REVOKE', 'RETIRE',
     'RELEASE_SUSPEND', 'RELEASE_RESUME', 'RELEASE_REVOKE', 'RELEASE_RETIRE'));

create table release_lifecycle (
    workspace_id uuid not null,
    release_id uuid not null,
    state varchar(16) not null,
    lifecycle_version bigint not null,
    reason text,
    actor varchar(512),
    changed_at timestamp with time zone not null,
    primary key (workspace_id, release_id),
    foreign key (workspace_id, release_id) references agent_release(workspace_id, id),
    constraint release_lifecycle_state_ck check (state in ('PENDING', 'AVAILABLE', 'SUSPENDED', 'REVOKED', 'RETIRED')),
    constraint release_lifecycle_version_ck check (lifecycle_version > 0)
);

create table release_revocation (
    workspace_id uuid not null,
    id uuid not null,
    release_id uuid not null,
    subject_kind varchar(24) not null,
    subject_identity varchar(512) not null,
    reason text not null,
    evidence_digest varchar(71),
    actor varchar(512) not null,
    occurred_at timestamp with time zone not null,
    primary key (workspace_id, id),
    foreign key (workspace_id, release_id) references agent_release(workspace_id, id),
    constraint release_revocation_subject_ck check (subject_kind in ('RELEASE', 'SIGNING_KEY', 'DEPENDENCY')),
    constraint release_revocation_evidence_ck check (evidence_digest is null or
        (char_length(evidence_digest) = 71 and evidence_digest like 'sha256:%'))
);
create index release_revocation_list_idx on release_revocation(workspace_id, occurred_at desc, id desc);
create index release_revocation_subject_idx on release_revocation(workspace_id, subject_kind, subject_identity);

create table release_lifecycle_event (
    workspace_id uuid not null,
    id uuid not null,
    release_id uuid not null,
    lifecycle_version bigint not null,
    from_state varchar(16),
    to_state varchar(16) not null,
    reason text,
    actor varchar(512),
    revocation_id uuid,
    occurred_at timestamp with time zone not null,
    primary key (workspace_id, id),
    unique (workspace_id, release_id, lifecycle_version),
    foreign key (workspace_id, release_id) references agent_release(workspace_id, id),
    foreign key (workspace_id, revocation_id) references release_revocation(workspace_id, id)
);

insert into release_lifecycle(workspace_id, release_id, state, lifecycle_version, reason, actor, changed_at)
select workspace_id, id, 'AVAILABLE', 1, 'MIGRATED_AS_AVAILABLE', 'system:migration', created_at from agent_release;
