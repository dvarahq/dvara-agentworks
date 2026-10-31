-- Issue #32: durable run engine (ADR-003, ADR-005, ADR-027). agent_run becomes the reconstructible run summary
-- beside an immutable, ordered, versioned event history; leases are fenced and use database time; checkpoints,
-- modeled external effects, and the cohort_work_item ledger make every resumable-work category visible to
-- quiescence. Payload columns are text here so H2 can run the schema for local development; the PostgreSQL vendor
-- migration V5_1 converts them to bounded JSONB and installs the database authority.
--
-- Runs admitted before this migration keep a null trace identity and manifest version: they carry no stored input
-- and are never claimed. As with V2 and V3, rows that violate the new lifecycle invariants (for example an ACTIVE
-- run without a lease, which no Core writer produced) fail the migration rather than receiving invented history.

-- Creation facts pinned with the run.
alter table agent_run add column trace_id varchar(32);
alter table agent_run add column manifest_version integer;
alter table agent_run add column limit_total_tokens bigint;
alter table agent_run add column limit_model_calls bigint;
alter table agent_run add column limit_tool_calls bigint;
alter table agent_run add column limit_runtime_millis bigint;
alter table agent_run add column limit_cost_usd decimal(19, 6);

-- Reconstructible summary projection.
alter table agent_run add column block_reason varchar(32);
alter table agent_run add column terminal_code varchar(64);
alter table agent_run add column event_sequence bigint default 0 not null;
alter table agent_run add column last_event_digest varchar(71);
alter table agent_run add column checkpoint_sequence bigint default 0 not null;
alter table agent_run add column started_at timestamp with time zone;
alter table agent_run add column completed_at timestamp with time zone;
alter table agent_run add column input_tokens bigint default 0 not null;
alter table agent_run add column output_tokens bigint default 0 not null;
alter table agent_run add column total_tokens bigint default 0 not null;
alter table agent_run add column model_calls bigint default 0 not null;
alter table agent_run add column tool_calls bigint default 0 not null;
alter table agent_run add column runtime_millis bigint default 0 not null;
alter table agent_run add column estimated_cost_usd decimal(19, 6) default 0 not null;

-- Operational ownership: optimistic version and the fenced lease.
alter table agent_run add column row_version bigint default 0 not null;
alter table agent_run add column lease_owner varchar(128);
alter table agent_run add column lease_fence bigint default 0 not null;
alter table agent_run add column lease_until timestamp with time zone;
alter table agent_run add column active_since timestamp with time zone;
alter table agent_run add column updated_at timestamp with time zone;

alter table agent_run add constraint agent_run_cohort_uk unique (workspace_id, id, cohort_id);
alter table agent_run add constraint agent_run_block_reason_ck check (
    (lifecycle = 'BLOCKED') = (block_reason is not null)
    and (block_reason is null or block_reason in ('HUMAN_INTERACTION', 'AUTHORIZATION', 'DURABLE_OPERATION',
        'CHILD_RUN', 'SCHEDULED_WAKEUP', 'CAPACITY')));
alter table agent_run add constraint agent_run_lease_ck check (
    (lease_owner is null) = (lease_until is null)
    and (lease_owner is not null) = (lifecycle = 'ACTIVE')
    and (active_since is not null) = (lifecycle = 'ACTIVE')
    and lease_fence >= 0 and row_version >= 0);
alter table agent_run add constraint agent_run_sequence_ck check (
    event_sequence >= 0 and checkpoint_sequence >= 0 and (event_sequence = 0) = (last_event_digest is null));
alter table agent_run add constraint agent_run_usage_ck check (
    input_tokens >= 0 and output_tokens >= 0 and total_tokens = input_tokens + output_tokens and model_calls >= 0
    and tool_calls >= 0 and runtime_millis >= 0 and estimated_cost_usd >= 0);
alter table agent_run add constraint agent_run_limits_ck check (
    (limit_total_tokens is null or limit_total_tokens >= 0) and (limit_model_calls is null or limit_model_calls >= 0)
    and (limit_tool_calls is null or limit_tool_calls >= 0) and (limit_runtime_millis is null or limit_runtime_millis >= 0)
    and (limit_cost_usd is null or limit_cost_usd >= 0));
alter table agent_run add constraint agent_run_creation_facts_ck check (
    (trace_id is null) = (manifest_version is null) and (trace_id is null or char_length(trace_id) = 32));

-- Canonical, append-only run history. Correlation columns repeat the run's pins so every event is attributable
-- on its own; cohort_id is non-work evidence in the resumable-work catalog.
create table agent_run_event (
    workspace_id uuid not null, run_id uuid not null, sequence bigint not null, id uuid not null,
    event_type varchar(64) not null, schema_version integer not null, payload_json text not null,
    payload_digest varchar(71) not null, event_digest varchar(71) not null, trace_id varchar(32), span_id varchar(16),
    release_id uuid not null, deployment_id uuid not null, revision_id uuid not null, cohort_id uuid not null,
    actor varchar(160) not null, lease_owner varchar(128), lease_fence bigint not null,
    created_at timestamp with time zone not null,
    primary key (workspace_id, run_id, sequence),
    unique (workspace_id, id),
    foreign key (workspace_id, run_id, cohort_id) references agent_run(workspace_id, id, cohort_id),
    constraint agent_run_event_sequence_ck check (sequence > 0 and schema_version > 0 and lease_fence >= 0),
    constraint agent_run_event_digest_ck check (char_length(payload_digest) = 71 and payload_digest like 'sha256:%'
        and char_length(event_digest) = 71 and event_digest like 'sha256:%')
);
create index agent_run_event_type_idx on agent_run_event(workspace_id, run_id, event_type);

-- Immutable checkpoints; the latest one accelerates recovery and never replaces event truth.
create table agent_run_checkpoint (
    workspace_id uuid not null, run_id uuid not null, sequence bigint not null, id uuid not null,
    cohort_id uuid not null, event_sequence bigint not null, payload_json text not null,
    payload_digest varchar(71) not null, lease_owner varchar(128) not null, lease_fence bigint not null,
    created_at timestamp with time zone not null,
    primary key (workspace_id, run_id, sequence),
    unique (workspace_id, id),
    foreign key (workspace_id, run_id, cohort_id) references agent_run(workspace_id, id, cohort_id),
    foreign key (workspace_id, run_id, event_sequence) references agent_run_event(workspace_id, run_id, sequence),
    constraint agent_run_checkpoint_sequence_ck check (sequence > 0 and lease_fence > 0)
);

-- Modeled external effects: the intent is durable before invocation, and a committed result is replayed rather
-- than repeated. Only digests of the request are stored.
create table agent_run_effect (
    workspace_id uuid not null, run_id uuid not null, effect_key varchar(200) not null, id uuid not null,
    cohort_id uuid not null, request_digest varchar(71) not null, state varchar(16) not null,
    attempts integer not null, result_json text, resolution varchar(1024), lease_owner varchar(128),
    lease_fence bigint not null, started_at timestamp with time zone not null,
    completed_at timestamp with time zone,
    primary key (workspace_id, run_id, effect_key),
    unique (workspace_id, id),
    foreign key (workspace_id, run_id, cohort_id) references agent_run(workspace_id, id, cohort_id),
    constraint agent_run_effect_state_ck check (state in ('PENDING', 'COMMITTED', 'FAILED', 'ABANDONED')
        and attempts > 0 and (state = 'PENDING') = (completed_at is null)
        and (state in ('COMMITTED', 'FAILED')) = (result_json is not null)
        and (state = 'ABANDONED') = (resolution is not null))
);

-- The resumable-work ledger. run_id is null only for
-- categories whose work has no run yet (for example schedules, owned by #57).
create table cohort_work_item (
    workspace_id uuid not null, id uuid not null, cohort_id uuid not null, run_id uuid,
    category varchar(16) not null, subject_id uuid not null, state varchar(16) not null,
    created_at timestamp with time zone not null, state_changed_at timestamp with time zone not null,
    terminal_at timestamp with time zone,
    primary key (workspace_id, id),
    foreign key (workspace_id, cohort_id) references runtime_cohort(workspace_id, id),
    foreign key (workspace_id, run_id, cohort_id) references agent_run(workspace_id, id, cohort_id),
    constraint cohort_work_item_category_ck check (category in ('CLAIM', 'OPERATION', 'CHECKPOINT', 'WAKEUP',
        'INBOX', 'INTERACTION', 'SCHEDULE', 'CHILD_WORK')),
    constraint cohort_work_item_state_ck check (state in ('PENDING', 'ACTIVE', 'TERMINAL')
        and (state = 'TERMINAL') = (terminal_at is not null))
);
create index cohort_work_item_cohort_idx on cohort_work_item(workspace_id, cohort_id, state);
create index cohort_work_item_run_idx on cohort_work_item(workspace_id, run_id, category, state);

-- Workers claim queued runs and runs whose lease expired.
create index agent_run_claim_idx on agent_run(lifecycle, lease_until, created_at);

-- Per-row delivery records of control-plane outbox consumers. Outbox rows stay append-only; delivery is tracked per
-- row rather than by a high-water sequence, because a lower sequence can commit after a higher one. A crash before
-- the record commits redelivers, so consumers must be idempotent.
create table control_plane_outbox_delivery (
    consumer varchar(64) not null, workspace_id uuid not null, outbox_id uuid not null,
    delivered_at timestamp with time zone not null,
    primary key (consumer, workspace_id, outbox_id),
    foreign key (workspace_id, outbox_id) references control_plane_outbox(workspace_id, id)
);
