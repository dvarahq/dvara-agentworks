-- Issue #43: logical sessions, serialized inbox delivery, and durable external-operation authority.
create table agent_session (
    workspace_id uuid not null,
    id uuid not null,
    deployment_id uuid not null,
    version bigint default 0 not null,
    created_at timestamp with time zone not null,
    updated_at timestamp with time zone not null,
    primary key (workspace_id, id),
    foreign key (workspace_id, deployment_id) references agent_deployment(workspace_id, id),
    constraint agent_session_version_ck check (version >= 0 and updated_at >= created_at)
);
create index agent_session_deployment_idx on agent_session(workspace_id, deployment_id, created_at, id);

-- A run may belong to one logical session. This association does not add or change an execution pin.
create table agent_session_run (
    workspace_id uuid not null,
    session_id uuid not null,
    run_id uuid not null,
    linked_at timestamp with time zone not null,
    primary key (workspace_id, session_id, run_id),
    unique (workspace_id, run_id),
    foreign key (workspace_id, session_id) references agent_session(workspace_id, id),
    foreign key (workspace_id, run_id) references agent_run(workspace_id, id)
);

create table session_inbox (
    workspace_id uuid not null,
    id uuid not null,
    session_id uuid not null,
    run_id uuid not null,
    release_id uuid not null,
    deployment_id uuid not null,
    revision_id uuid not null,
    cohort_id uuid not null,
    cohort_transition_version bigint not null,
    sequence bigint not null,
    message_type varchar(32) not null,
    correlation_id varchar(128) not null,
    idempotency_key varchar(128) not null,
    request_digest varchar(71) not null,
    payload_json text not null,
    status varchar(16) not null,
    row_version bigint default 0 not null,
    claimed_by varchar(128),
    claim_fence bigint,
    claim_until timestamp with time zone,
    created_at timestamp with time zone not null,
    terminal_at timestamp with time zone,
    primary key (workspace_id, id),
    unique (workspace_id, session_id, sequence),
    unique (workspace_id, session_id, idempotency_key),
    foreign key (workspace_id, session_id, run_id) references agent_session_run(workspace_id, session_id, run_id),
    foreign key (workspace_id, run_id, cohort_id) references agent_run(workspace_id, id, cohort_id),
    constraint session_inbox_type_ck check (message_type in ('USER_MESSAGE', 'CHANNEL_MESSAGE', 'SYSTEM_EVENT',
        'OPERATION_COMPLETED', 'TEAM_MESSAGE', 'A2A_MESSAGE', 'SCHEDULED_WAKEUP')),
    constraint session_inbox_status_ck check (status in ('PENDING', 'CLAIMED', 'CONSUMED', 'SUPERSEDED', 'CANCELLED')
        and (status = 'CLAIMED') = (claimed_by is not null)
        and (status = 'CLAIMED') = (claim_fence is not null)
        and (status = 'CLAIMED') = (claim_until is not null)
        and (status in ('CONSUMED', 'SUPERSEDED', 'CANCELLED')) = (terminal_at is not null)),
    constraint session_inbox_version_ck check (cohort_transition_version > 0 and sequence > 0 and row_version >= 0),
    constraint session_inbox_digest_ck check (char_length(request_digest) = 71 and request_digest like 'sha256:%')
);
create index session_inbox_claim_idx on session_inbox(workspace_id, run_id, status, sequence);
create index session_inbox_list_idx on session_inbox(workspace_id, session_id, sequence desc);

create table durable_operation (
    workspace_id uuid not null,
    id uuid not null,
    session_id uuid not null,
    run_id uuid not null,
    release_id uuid not null,
    deployment_id uuid not null,
    revision_id uuid not null,
    cohort_id uuid not null,
    cohort_transition_version bigint not null,
    correlation_id varchar(128) not null,
    idempotency_key varchar(128) not null,
    request_digest varchar(71) not null,
    provider_id varchar(128) not null,
    operation_type varchar(128) not null,
    input_json text not null,
    callback_token_digest varchar(71) not null,
    callback_token_version integer not null,
    external_id varchar(512),
    status varchar(16) not null,
    progress_percent integer,
    result_code varchar(128),
    result_json text,
    resolved_by varchar(255),
    created_at timestamp with time zone not null,
    deadline timestamp with time zone not null,
    updated_at timestamp with time zone not null,
    terminal_at timestamp with time zone,
    primary key (workspace_id, id),
    unique (workspace_id, run_id, idempotency_key),
    foreign key (workspace_id, session_id, run_id) references agent_session_run(workspace_id, session_id, run_id),
    foreign key (workspace_id, run_id, cohort_id) references agent_run(workspace_id, id, cohort_id),
    constraint durable_operation_status_ck check (status in ('STARTING', 'RUNNING', 'SUCCEEDED', 'FAILED', 'CANCELLED')
        and (status in ('SUCCEEDED', 'FAILED', 'CANCELLED')) = (result_json is not null)
        and (status in ('SUCCEEDED', 'FAILED', 'CANCELLED')) = (result_code is not null)
        and (status in ('SUCCEEDED', 'FAILED', 'CANCELLED')) = (resolved_by is not null)
        and (status in ('SUCCEEDED', 'FAILED', 'CANCELLED')) = (terminal_at is not null)),
    constraint durable_operation_time_ck check (deadline > created_at and updated_at >= created_at),
    constraint durable_operation_progress_ck check (progress_percent is null or progress_percent between 0 and 100),
    constraint durable_operation_version_ck check (cohort_transition_version > 0 and callback_token_version > 0),
    constraint durable_operation_digest_ck check (
        char_length(request_digest) = 71 and request_digest like 'sha256:%'
        and char_length(callback_token_digest) = 71 and callback_token_digest like 'sha256:%')
);
create index durable_operation_reconcile_idx on durable_operation(workspace_id, status, updated_at, id);
create index durable_operation_session_idx on durable_operation(workspace_id, session_id, created_at desc, id desc);
