-- Issue #42: durable Human Interaction Protocol authority. PostgreSQL assigns creation/deadline time and the vendor
-- migration fences every write; this common shape also supports H2 local development.
create table human_interaction (
    workspace_id uuid not null,
    id uuid not null,
    run_id uuid not null,
    release_id uuid not null,
    deployment_id uuid not null,
    revision_id uuid not null,
    cohort_id uuid not null,
    cohort_transition_version bigint not null,
    correlation_id varchar(128) not null,
    idempotency_key varchar(128) not null,
    request_digest varchar(71) not null,
    kind varchar(24) not null,
    prompt_json text not null,
    assignment_level varchar(32) not null,
    assigned_principal_id uuid,
    status varchar(16) not null,
    response_json text,
    resolved_by varchar(255),
    created_at timestamp with time zone not null,
    deadline timestamp with time zone not null,
    resolved_at timestamp with time zone,
    primary key (workspace_id, id),
    unique (workspace_id, run_id, idempotency_key),
    foreign key (workspace_id, run_id, cohort_id) references agent_run(workspace_id, id, cohort_id),
    constraint human_interaction_kind_ck check (kind in
        ('APPROVAL', 'QUESTION', 'SELECTION', 'FORM', 'CONFIRMATION', 'AUTHORIZATION')),
    constraint human_interaction_assignment_ck check (
        assignment_level in ('WORKSPACE', 'USER', 'SESSION', 'DELEGATED_SUBJECT')
        and (assignment_level = 'WORKSPACE') = (assigned_principal_id is null)),
    constraint human_interaction_status_ck check (
        status in ('OPEN', 'RESPONDED', 'EXPIRED', 'CANCELLED', 'REVOKED')
        and (status = 'OPEN') = (resolved_at is null)
        and (status = 'OPEN') = (resolved_by is null)
        and (status = 'RESPONDED') = (response_json is not null)),
    constraint human_interaction_time_ck check (deadline > created_at),
    constraint human_interaction_version_ck check (cohort_transition_version > 0),
    constraint human_interaction_digest_ck check (
        char_length(request_digest) = 71 and request_digest like 'sha256:%')
);
create index human_interaction_pending_idx on human_interaction(workspace_id, status, deadline, id);
create index human_interaction_run_idx on human_interaction(workspace_id, run_id, created_at, id);
