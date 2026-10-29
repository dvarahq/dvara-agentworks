-- Issue #31: canonical release, environment, deployment, revision, cohort, routing, decision, outbox,
-- idempotency, and run-admission lifecycle (ADR-005, ADR-007, ADR-008, ADR-027).
-- Like V2, NOT NULL columns are added without defaults: schema-V1 placeholder rows fail the migration rather
-- than receiving invented lifecycle history. No Core writer populated these tables before this migration.

-- AgentVersion is referenced together with its definition so a release cannot cross lineages.
alter table agent_version add constraint agent_version_definition_uk unique (workspace_id, id, definition_id);

alter table agent_release add column definition_id uuid not null;
alter table agent_release add column manifest_json text not null;
alter table agent_release add column manifest_digest varchar(71) not null;
alter table agent_release add column created_at timestamp with time zone not null;
alter table agent_release add constraint agent_release_version_fk
    foreign key (workspace_id, version_id, definition_id) references agent_version(workspace_id, id, definition_id);
alter table agent_release add constraint agent_release_definition_uk unique (workspace_id, id, definition_id);
alter table agent_release add constraint agent_release_digest_ck
    check (char_length(manifest_digest) = 71 and manifest_digest like 'sha256:%');

-- Aliases are optional, workspace-scoped, audited convenience pointers; they never select environments.
create table release_alias (
    workspace_id uuid not null, name varchar(63) not null, release_id uuid not null,
    alias_version bigint not null, updated_at timestamp with time zone not null,
    primary key (workspace_id, name),
    foreign key (workspace_id, release_id) references agent_release(workspace_id, id),
    constraint release_alias_version_ck check (alias_version > 0)
);
create table release_alias_history (
    workspace_id uuid not null, id uuid not null, alias_name varchar(63) not null, alias_version bigint not null,
    release_id uuid not null, previous_release_id uuid, changed_at timestamp with time zone not null,
    primary key (workspace_id, id), unique (workspace_id, alias_name, alias_version),
    foreign key (workspace_id, release_id) references agent_release(workspace_id, id)
);

-- Customer-defined environments with a versioned promotion policy; names are free-form, not an enum.
alter table agent_environment add column description varchar(1024);
alter table agent_environment add column policy_version bigint not null;
alter table agent_environment add column promotion_policy_json text not null;
alter table agent_environment add column promotion_policy_digest varchar(71) not null;
alter table agent_environment add column created_at timestamp with time zone not null;
create table promotion_policy_version (
    workspace_id uuid not null, environment_id uuid not null, policy_version bigint not null,
    policy_json text not null, policy_digest varchar(71) not null, created_at timestamp with time zone not null,
    primary key (workspace_id, environment_id, policy_version),
    foreign key (workspace_id, environment_id) references agent_environment(workspace_id, id)
);

-- The logical deployment owns current desired state; `revision` is its current revision generation (ETag).
alter table agent_deployment add column definition_id uuid not null;
alter table agent_deployment add column name varchar(63) not null;
alter table agent_deployment add column current_revision_id uuid;
alter table agent_deployment add column current_routing_generation_id uuid;
alter table agent_deployment add column created_at timestamp with time zone not null;
alter table agent_deployment add constraint agent_deployment_name_uk unique (workspace_id, environment_id, name);
alter table agent_deployment add constraint agent_deployment_release_definition_fk
    foreign key (workspace_id, desired_release_id, definition_id)
    references agent_release(workspace_id, id, definition_id);

-- Immutable decisions: every promotion, rollback, and cohort command records its outcome and evidence.
create table lifecycle_decision (
    workspace_id uuid not null, id uuid not null, kind varchar(24) not null, outcome varchar(16) not null,
    environment_id uuid not null, definition_id uuid not null, deployment_id uuid, release_id uuid, cohort_id uuid,
    policy_version bigint, policy_digest varchar(71), gate_results_json text not null,
    actor varchar(128) not null, reason varchar(1024), revision_id uuid, routing_generation_id uuid,
    created_at timestamp with time zone not null,
    primary key (workspace_id, id),
    foreign key (workspace_id, environment_id) references agent_environment(workspace_id, id),
    constraint lifecycle_decision_kind_ck check (kind in
        ('PROMOTION', 'ROLLBACK', 'SUSPEND', 'RESUME', 'REVOKE', 'RETIRE')),
    constraint lifecycle_decision_outcome_ck check (outcome in ('ALLOWED', 'DENIED'))
);

create table deployment_revision (
    workspace_id uuid not null, id uuid not null, deployment_id uuid not null, generation bigint not null,
    release_id uuid not null, definition_id uuid not null, environment_id uuid not null,
    placement varchar(16) not null, configuration_json text not null, content_digest varchar(71) not null,
    cause varchar(16) not null, decision_id uuid not null, previous_revision_id uuid,
    created_at timestamp with time zone not null,
    primary key (workspace_id, id),
    unique (workspace_id, deployment_id, generation),
    unique (workspace_id, id, deployment_id),
    unique (workspace_id, id, release_id),
    unique (workspace_id, id, environment_id),
    foreign key (workspace_id, deployment_id) references agent_deployment(workspace_id, id),
    foreign key (workspace_id, release_id, definition_id) references agent_release(workspace_id, id, definition_id),
    foreign key (workspace_id, environment_id) references agent_environment(workspace_id, id),
    foreign key (workspace_id, decision_id) references lifecycle_decision(workspace_id, id),
    constraint deployment_revision_generation_ck check (generation > 0),
    constraint deployment_revision_placement_ck check (placement in ('SHARED', 'ISOLATED', 'DEDICATED')),
    constraint deployment_revision_cause_ck check (cause in ('PROMOTION', 'ROLLBACK', 'REDEPLOYMENT')),
    constraint deployment_revision_digest_ck check (char_length(content_digest) = 71 and content_digest like 'sha256:%')
);

-- Versioned operational state for one revision; immutable content lives in deployment_revision.
create table runtime_cohort (
    workspace_id uuid not null, id uuid not null, deployment_id uuid not null, revision_id uuid not null,
    state varchar(16) not null, transition_version bigint not null,
    state_changed_at timestamp with time zone not null, drain_started_at timestamp with time zone,
    drain_deadline timestamp with time zone, quiescent_since timestamp with time zone,
    retired_at timestamp with time zone, created_at timestamp with time zone not null,
    primary key (workspace_id, id),
    unique (workspace_id, revision_id),
    unique (workspace_id, id, revision_id),
    unique (workspace_id, id, deployment_id),
    foreign key (workspace_id, revision_id, deployment_id) references deployment_revision(workspace_id, id, deployment_id),
    constraint runtime_cohort_state_ck check (state in
        ('PENDING', 'ROUTABLE', 'SUSPENDED', 'DRAINING', 'QUIESCENT', 'RETIRED', 'REVOKED')),
    constraint runtime_cohort_version_ck check (transition_version > 0)
);
create table cohort_transition (
    workspace_id uuid not null, id uuid not null, cohort_id uuid not null, transition_version bigint not null,
    from_state varchar(16), to_state varchar(16) not null, cause varchar(32) not null, decision_id uuid,
    actor varchar(128) not null, reason varchar(1024), occurred_at timestamp with time zone not null,
    primary key (workspace_id, id),
    unique (workspace_id, cohort_id, transition_version),
    foreign key (workspace_id, cohort_id) references runtime_cohort(workspace_id, id)
);

-- Immutable routing decisions. Core validates a single full-weight target in application code; the schema
-- deliberately permits an explicitly authorized multi-target policy (ADR-027).
create table routing_generation (
    workspace_id uuid not null, id uuid not null, deployment_id uuid not null, generation bigint not null,
    policy varchar(24) not null, decision_id uuid not null, previous_generation_id uuid,
    created_at timestamp with time zone not null,
    primary key (workspace_id, id),
    unique (workspace_id, deployment_id, generation),
    unique (workspace_id, id, deployment_id),
    foreign key (workspace_id, deployment_id) references agent_deployment(workspace_id, id),
    foreign key (workspace_id, decision_id) references lifecycle_decision(workspace_id, id),
    constraint routing_generation_policy_ck check (policy in ('SINGLE_TARGET', 'EXPLICIT_MULTI_TARGET'))
);
create table routing_target (
    workspace_id uuid not null, generation_id uuid not null, ordinal integer not null,
    cohort_id uuid not null, revision_id uuid not null, weight integer not null,
    primary key (workspace_id, generation_id, ordinal),
    foreign key (workspace_id, generation_id) references routing_generation(workspace_id, id),
    foreign key (workspace_id, cohort_id, revision_id) references runtime_cohort(workspace_id, id, revision_id),
    constraint routing_target_weight_ck check (weight between 1 and 100)
);

-- Desired-state notifications commit atomically with the decision that caused them (ADR-008).
create table control_plane_outbox (
    workspace_id uuid not null, id uuid not null, sequence_number bigint generated by default as identity,
    event_type varchar(64) not null, decision_id uuid not null, payload_json text not null,
    created_at timestamp with time zone not null,
    primary key (workspace_id, id),
    unique (sequence_number),
    foreign key (workspace_id, decision_id) references lifecycle_decision(workspace_id, id)
);

-- Scoped lifecycle-command idempotency. Keys and fingerprints are stored only as SHA-256 digests; after the
-- replay window the detailed result is compacted to a tombstone that remains until namespace retirement.
create table idempotency_namespace (
    workspace_id uuid not null, namespace varchar(64) not null, state varchar(16) not null,
    retired_at timestamp with time zone,
    primary key (workspace_id, namespace),
    constraint idempotency_namespace_state_ck check (state in ('OPEN', 'RETIRED'))
);
create table lifecycle_idempotency (
    workspace_id uuid not null, namespace varchar(64) not null, key_digest varchar(64) not null,
    fingerprint_digest varchar(64) not null, result_kind varchar(32) not null, result_id uuid not null,
    result_status varchar(32) not null, result_json text, created_at timestamp with time zone not null,
    replay_until timestamp with time zone not null, compacted_at timestamp with time zone,
    primary key (workspace_id, namespace, key_digest),
    foreign key (workspace_id, namespace) references idempotency_namespace(workspace_id, namespace),
    constraint lifecycle_idempotency_compaction_ck check ((result_json is null) = (compacted_at is not null))
);

-- Runs pin the exact revision, cohort, and (for independent roots) routing generation and target.
alter table agent_run add column definition_id uuid not null;
alter table agent_run add column revision_id uuid not null;
alter table agent_run add column cohort_id uuid not null;
alter table agent_run add column admission varchar(16) not null;
alter table agent_run add column routing_generation_id uuid;
alter table agent_run add column routing_target_ordinal integer;
alter table agent_run add column parent_run_id uuid;
alter table agent_run add column root_run_id uuid not null;
alter table agent_run add column continuation_kind varchar(16);
alter table agent_run add column lifecycle varchar(16) not null;
alter table agent_run add column idempotency_key_digest varchar(64);
alter table agent_run add column request_fingerprint varchar(64);
alter table agent_run add constraint agent_run_release_definition_fk
    foreign key (workspace_id, release_id, definition_id) references agent_release(workspace_id, id, definition_id);
alter table agent_run add constraint agent_run_revision_deployment_fk
    foreign key (workspace_id, revision_id, deployment_id) references deployment_revision(workspace_id, id, deployment_id);
alter table agent_run add constraint agent_run_revision_release_fk
    foreign key (workspace_id, revision_id, release_id) references deployment_revision(workspace_id, id, release_id);
alter table agent_run add constraint agent_run_revision_environment_fk
    foreign key (workspace_id, revision_id, environment_id) references deployment_revision(workspace_id, id, environment_id);
alter table agent_run add constraint agent_run_cohort_fk
    foreign key (workspace_id, cohort_id, revision_id) references runtime_cohort(workspace_id, id, revision_id);
alter table agent_run add constraint agent_run_routing_fk
    foreign key (workspace_id, routing_generation_id, deployment_id) references routing_generation(workspace_id, id, deployment_id);
alter table agent_run add constraint agent_run_parent_fk foreign key (workspace_id, parent_run_id) references agent_run(workspace_id, id);
alter table agent_run add constraint agent_run_root_fk foreign key (workspace_id, root_run_id) references agent_run(workspace_id, id);
alter table agent_run add constraint agent_run_idempotency_uk unique (workspace_id, deployment_id, idempotency_key_digest);
alter table agent_run add constraint agent_run_lifecycle_ck check (lifecycle in
    ('CREATED', 'QUEUED', 'ACTIVE', 'BLOCKED', 'PAUSED', 'SUCCEEDED', 'FAILED', 'CANCELLED'));
alter table agent_run add constraint agent_run_admission_ck check (
    (admission = 'ROOT' and routing_generation_id is not null and routing_target_ordinal is not null
        and parent_run_id is null and continuation_kind is null and root_run_id = id)
    or (admission = 'CONTINUATION' and routing_generation_id is null and routing_target_ordinal is null
        and parent_run_id is not null and continuation_kind in ('CHILD', 'RETRY', 'COMPENSATION')
        and idempotency_key_digest is null));
alter table agent_deployment add constraint agent_deployment_current_revision_fk
    foreign key (workspace_id, current_revision_id, id) references deployment_revision(workspace_id, id, deployment_id);
alter table agent_deployment add constraint agent_deployment_current_routing_fk
    foreign key (workspace_id, current_routing_generation_id, id) references routing_generation(workspace_id, id, deployment_id);
create index agent_run_cohort_lifecycle_idx on agent_run(workspace_id, cohort_id, lifecycle);
create index deployment_revision_release_idx on deployment_revision(workspace_id, release_id);
