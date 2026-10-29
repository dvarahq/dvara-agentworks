-- Issue #53: immutable evaluation definitions, restart-safe results, and baselines.
create table evaluation_dataset (
    workspace_id uuid not null,
    id uuid not null,
    name varchar(160) not null,
    version integer not null,
    content_digest varchar(71) not null,
    sensitive boolean not null,
    expires_at timestamp with time zone,
    created_at timestamp with time zone not null,
    primary key (workspace_id, id),
    unique (workspace_id, name, version),
    constraint evaluation_dataset_version_ck check (version > 0),
    constraint evaluation_dataset_digest_ck check (char_length(content_digest) = 71 and content_digest like 'sha256:%'),
    constraint evaluation_dataset_retention_ck check (not sensitive or expires_at is not null)
);

create table evaluation_case (
    workspace_id uuid not null,
    dataset_id uuid not null,
    case_id varchar(128) not null,
    input_json text not null,
    expected_json text not null,
    tags_json text not null,
    input_digest varchar(71) not null,
    expected_digest varchar(71) not null,
    purged_at timestamp with time zone,
    primary key (workspace_id, dataset_id, case_id),
    foreign key (workspace_id, dataset_id) references evaluation_dataset(workspace_id, id),
    constraint evaluation_case_input_digest_ck check (char_length(input_digest) = 71 and input_digest like 'sha256:%'),
    constraint evaluation_case_expected_digest_ck check (char_length(expected_digest) = 71 and expected_digest like 'sha256:%'),
    constraint evaluation_case_purge_ck check (purged_at is null or
        (input_json = '{"$agentworksPurgedDigest":"' || input_digest || '"}' and
         expected_json = '{"$agentworksPurgedDigest":"' || expected_digest || '"}'))
);

create table evaluation_evaluator (
    workspace_id uuid not null,
    id uuid not null,
    name varchar(160) not null,
    version integer not null,
    evaluator_type varchar(32) not null,
    configuration_json text not null,
    content_digest varchar(71) not null,
    created_at timestamp with time zone not null,
    primary key (workspace_id, id),
    unique (workspace_id, name, version),
    constraint evaluation_evaluator_version_ck check (version > 0),
    constraint evaluation_evaluator_type_ck check (evaluator_type in ('EXACT','REGEX','JSON_SCHEMA','TOOL_TRACE','POLICY','LATENCY','COST','CUSTOM_JAVA')),
    constraint evaluation_evaluator_digest_ck check (char_length(content_digest) = 71 and content_digest like 'sha256:%')
);

create table evaluation_run (
    workspace_id uuid not null,
    id uuid not null,
    dataset_id uuid not null,
    dataset_digest varchar(71) not null,
    evaluator_ids_json text not null,
    evaluator_set_digest varchar(71) not null,
    subject_identity varchar(256) not null,
    release_evidence_ref varchar(256) not null,
    budget_json text not null,
    request_json text not null,
    request_digest varchar(71) not null,
    idempotency_key varchar(128) not null,
    status varchar(32) not null,
    cancellation_requested boolean not null,
    completed_results integer not null,
    total_results integer not null,
    passed_results integer not null,
    consumed_cost_usd numeric(20,8) not null,
    maximum_latency_ms bigint not null,
    report_digest varchar(71) not null,
    failure_code varchar(96) not null,
    started_at timestamp with time zone not null,
    completed_at timestamp with time zone,
    primary key (workspace_id, id),
    unique (workspace_id, idempotency_key),
    foreign key (workspace_id, dataset_id) references evaluation_dataset(workspace_id, id),
    constraint evaluation_run_status_ck check (status in ('RUNNING','COMPLETED','FAILED','CANCELLED','BUDGET_EXCEEDED')),
    constraint evaluation_run_counts_ck check (completed_results >= 0 and total_results > 0 and passed_results >= 0 and passed_results <= completed_results and completed_results <= total_results),
    constraint evaluation_run_usage_ck check (consumed_cost_usd >= 0 and maximum_latency_ms >= 0),
    constraint evaluation_run_digest_ck check (char_length(dataset_digest) = 71 and dataset_digest like 'sha256:%' and char_length(evaluator_set_digest) = 71 and evaluator_set_digest like 'sha256:%' and char_length(request_digest) = 71 and request_digest like 'sha256:%'),
    constraint evaluation_run_terminal_ck check ((status = 'RUNNING' and completed_at is null) or (status <> 'RUNNING' and completed_at is not null))
);
create index evaluation_run_list_idx on evaluation_run(workspace_id, started_at desc, id desc);

create table evaluation_result (
    workspace_id uuid not null,
    run_id uuid not null,
    case_id varchar(128) not null,
    evaluator_id uuid not null,
    passed boolean not null,
    score numeric(12,9) not null,
    result_code varchar(96) not null,
    evidence_json text not null,
    evidence_digest varchar(71) not null,
    created_at timestamp with time zone not null,
    primary key (workspace_id, run_id, case_id, evaluator_id),
    foreign key (workspace_id, run_id) references evaluation_run(workspace_id, id),
    foreign key (workspace_id, evaluator_id) references evaluation_evaluator(workspace_id, id),
    constraint evaluation_result_score_ck check (score >= 0 and score <= 1),
    constraint evaluation_result_digest_ck check (char_length(evidence_digest) = 71 and evidence_digest like 'sha256:%')
);

create table evaluation_baseline (
    workspace_id uuid not null,
    id uuid not null,
    name varchar(160) not null,
    version integer not null,
    run_id uuid not null,
    report_digest varchar(71) not null,
    thresholds_json text not null,
    cohort_json text not null,
    created_at timestamp with time zone not null,
    primary key (workspace_id, id),
    unique (workspace_id, name, version),
    foreign key (workspace_id, run_id) references evaluation_run(workspace_id, id),
    constraint evaluation_baseline_version_ck check (version > 0),
    constraint evaluation_baseline_digest_ck check (char_length(report_digest) = 71 and report_digest like 'sha256:%')
);
