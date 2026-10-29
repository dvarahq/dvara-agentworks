-- Issue #84: PostgreSQL remains authoritative for artifact ownership, immutable metadata, validation, and lineage.
-- Content lives behind a provider-neutral digest and is never addressed by a caller-supplied filesystem path.

create table artifact_object (
    workspace_id uuid not null,
    id uuid not null,
    organization_id uuid not null,
    media_type varchar(255) not null,
    content_size bigint not null,
    content_digest varchar(71) not null,
    created_at timestamp with time zone not null,
    expires_at timestamp with time zone,
    classification varchar(16) not null,
    redacted boolean not null,
    validation_state varchar(16) not null,
    provenance_source varchar(64) not null,
    provenance_actor varchar(256) not null,
    run_id uuid,
    release_id uuid,
    revision_id uuid,
    cohort_id uuid,
    input_refs varchar(10000) not null,
    primary key (workspace_id, id),
    foreign key (workspace_id, organization_id) references workspace(id, organization_id),
    foreign key (workspace_id, run_id, release_id, revision_id, cohort_id)
        references agent_run(workspace_id, id, release_id, revision_id, cohort_id),
    constraint artifact_object_size_ck check (content_size >= 0),
    constraint artifact_object_digest_ck check (
        char_length(content_digest) = 71
        and substring(content_digest from 1 for 7) = 'sha256:'
        and lower(content_digest) = content_digest),
    constraint artifact_object_expiry_ck check (expires_at is null or expires_at > created_at),
    constraint artifact_object_classification_ck check (
        classification in ('PUBLIC', 'INTERNAL', 'CONFIDENTIAL', 'RESTRICTED')),
    constraint artifact_object_validation_ck check (
        validation_state in ('PENDING', 'ACCEPTED', 'QUARANTINED', 'REJECTED')),
    constraint artifact_object_run_binding_ck check (
        (run_id is null and release_id is null and revision_id is null and cohort_id is null)
        or (run_id is not null and release_id is not null and revision_id is not null and cohort_id is not null))
);

create index artifact_object_search_idx on artifact_object(workspace_id, created_at, id);
create index artifact_object_digest_idx on artifact_object(content_digest);
