-- Issue #84: lifecycle, durable pins, content reference accounting, and auditable maintenance.

alter table artifact_object add column lifecycle_state varchar(16) default 'ACTIVE' not null;
alter table artifact_object add column validation_reason varchar(256) default 'LEGACY_ACCEPTED' not null;
alter table artifact_object add column state_changed_at timestamp with time zone;
alter table artifact_object add column expired_at timestamp with time zone;
alter table artifact_object add column deleted_at timestamp with time zone;

update artifact_object set state_changed_at = created_at where state_changed_at is null;
alter table artifact_object alter column state_changed_at set not null;

alter table artifact_object add constraint artifact_object_lifecycle_ck check (
    lifecycle_state in ('ACTIVE', 'EXPIRED', 'DELETED')
    and (lifecycle_state = 'ACTIVE') = (expired_at is null and deleted_at is null)
    and (lifecycle_state = 'EXPIRED') = (expired_at is not null and deleted_at is null)
    and (lifecycle_state = 'DELETED') = (expired_at is not null and deleted_at is not null));

create table artifact_content_object (
    workspace_id uuid not null,
    organization_id uuid not null,
    content_digest varchar(71) not null,
    content_size bigint not null,
    state varchar(16) not null,
    reference_count bigint not null,
    owner_token uuid,
    lease_until timestamp with time zone,
    created_at timestamp with time zone not null,
    updated_at timestamp with time zone not null,
    primary key (workspace_id, content_digest),
    foreign key (workspace_id, organization_id) references workspace(id, organization_id),
    constraint artifact_content_object_state_ck check (
        state in ('STAGING', 'ACTIVE') and reference_count >= 0
        and (state = 'STAGING') = (owner_token is not null and lease_until is not null))
);

insert into artifact_content_object(workspace_id, organization_id, content_digest, content_size, state, reference_count,
        owner_token, lease_until, created_at, updated_at)
select workspace_id, organization_id, content_digest, max(content_size), 'ACTIVE', count(*), null, null,
       min(created_at), max(created_at)
from artifact_object
group by workspace_id, organization_id, content_digest;

create table artifact_pin (
    workspace_id uuid not null,
    id uuid not null,
    artifact_id uuid not null,
    organization_id uuid not null,
    pin_kind varchar(16) not null,
    subject_id uuid not null,
    created_at timestamp with time zone not null,
    expires_at timestamp with time zone,
    actor varchar(256) not null,
    primary key (workspace_id, id),
    unique (workspace_id, artifact_id, pin_kind, subject_id),
    foreign key (workspace_id, artifact_id) references artifact_object(workspace_id, id),
    constraint artifact_pin_kind_ck check (pin_kind in ('RUN', 'RELEASE', 'EVALUATION', 'AUDIT', 'RETENTION')),
    constraint artifact_pin_expiry_ck check (expires_at is null or expires_at > created_at)
);
create index artifact_pin_artifact_idx on artifact_pin(workspace_id, artifact_id, expires_at);

create table artifact_maintenance_event (
    id uuid primary key,
    event_type varchar(32) not null,
    completed_at timestamp with time zone not null,
    stale_uploads_removed integer not null,
    metadata_expired integer not null,
    metadata_deleted integer not null,
    content_objects_removed integer not null,
    integrity_failures integer not null,
    constraint artifact_maintenance_counts_ck check (stale_uploads_removed >= 0 and metadata_expired >= 0
        and metadata_deleted >= 0 and content_objects_removed >= 0 and integrity_failures >= 0)
);

create table artifact_lifecycle_event (
    workspace_id uuid not null,
    id uuid not null,
    artifact_id uuid not null,
    event_type varchar(32) not null,
    actor varchar(256) not null,
    detail varchar(256),
    created_at timestamp with time zone not null,
    primary key (workspace_id, id),
    foreign key (workspace_id, artifact_id) references artifact_object(workspace_id, id)
);
create index artifact_lifecycle_event_artifact_idx
    on artifact_lifecycle_event(workspace_id, artifact_id, created_at, id);

create index artifact_object_lifecycle_idx on artifact_object(lifecycle_state, expires_at, created_at, id);
