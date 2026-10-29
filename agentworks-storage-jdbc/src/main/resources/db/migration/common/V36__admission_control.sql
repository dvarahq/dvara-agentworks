-- Issue #41: durable, tenant-scoped admission decisions and immutable capacity pins.
-- Existing runs predate this contract and retain null pins; every newly admitted run writes the full tuple.
alter table agent_run add column admission_placement varchar(16);
alter table agent_run add column admission_requires_sandbox boolean;
alter table agent_run add column admission_policy_version varchar(64);

alter table agent_run add constraint agent_run_admission_capacity_ck check (
    (admission_placement is null and admission_requires_sandbox is null and admission_policy_version is null)
    or (admission_placement in ('SHARED', 'ISOLATED', 'DEDICATED')
        and admission_requires_sandbox is not null and admission_policy_version is not null));

create index agent_run_admission_workspace_idx
    on agent_run(workspace_id, lifecycle, created_at);
create index agent_run_admission_deployment_idx
    on agent_run(workspace_id, deployment_id, lifecycle, created_at);
create index agent_run_admission_placement_idx
    on agent_run(admission_placement, lifecycle, created_at);

-- This is decision evidence, not a request log: it deliberately stores no input, principal data, or secret.
create table run_admission_decision (
    workspace_id uuid not null,
    id uuid not null,
    deployment_id uuid not null,
    run_id uuid,
    admission_class varchar(16) not null,
    outcome varchar(16) not null,
    event_type varchar(32) not null,
    code varchar(64) not null,
    placement varchar(16),
    policy_version varchar(64) not null,
    request_digest varchar(71) not null,
    workspace_outstanding bigint not null,
    deployment_outstanding bigint not null,
    workspace_queued bigint not null,
    deployment_queued bigint not null,
    sandbox_outstanding bigint not null,
    placement_outstanding bigint not null,
    created_at timestamp with time zone not null,
    primary key (workspace_id, id),
    foreign key (workspace_id, deployment_id) references agent_deployment(workspace_id, id),
    foreign key (workspace_id, run_id) references agent_run(workspace_id, id),
    constraint run_admission_decision_class_ck check (admission_class in ('ROOT', 'CONTINUATION')),
    constraint run_admission_decision_outcome_ck check (outcome in ('ACCEPTED', 'REJECTED', 'DEFERRED')),
    constraint run_admission_decision_event_ck check (event_type in ('ADMISSION_ACCEPTED', 'ADMISSION_REJECTED')
        and (outcome = 'ACCEPTED') = (event_type = 'ADMISSION_ACCEPTED')),
    constraint run_admission_decision_placement_ck check
        (placement is null or placement in ('SHARED', 'ISOLATED', 'DEDICATED')),
    constraint run_admission_decision_digest_ck check
        (char_length(request_digest) = 71 and request_digest like 'sha256:%'),
    constraint run_admission_decision_counts_ck check (workspace_outstanding >= 0
        and deployment_outstanding >= 0 and workspace_queued >= 0 and deployment_queued >= 0
        and sandbox_outstanding >= 0 and placement_outstanding >= 0)
);
create index run_admission_decision_rate_idx
    on run_admission_decision(workspace_id, outcome, created_at);
create index run_admission_decision_deployment_idx
    on run_admission_decision(workspace_id, deployment_id, created_at);

-- Database-time deficit round-robin cursor. Workers prefer a workspace not served recently, then its oldest run.
create table run_admission_fairness (
    workspace_id uuid not null primary key,
    last_claimed_at timestamp with time zone not null,
    foreign key (workspace_id) references workspace(id)
);
