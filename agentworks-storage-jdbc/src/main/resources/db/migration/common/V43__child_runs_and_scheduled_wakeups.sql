-- Issue #57: bounded parent-directed children and database-time scheduled wakeups.
alter table agent_run drop constraint agent_run_admission_ck;
alter table agent_run add constraint agent_run_admission_ck check (
    (admission = 'ROOT' and routing_generation_id is not null and routing_target_ordinal is not null
        and parent_run_id is null and continuation_kind is null and root_run_id = id)
    or (admission = 'CHILD' and routing_generation_id is not null and routing_target_ordinal is not null
        and parent_run_id is not null and continuation_kind = 'CHILD' and root_run_id <> id)
    or (admission = 'CONTINUATION' and routing_generation_id is null and routing_target_ordinal is null
        and parent_run_id is not null and continuation_kind in ('CHILD', 'RETRY', 'COMPENSATION')
        and idempotency_key_digest is null));
alter table run_admission_decision drop constraint run_admission_decision_class_ck;
alter table run_admission_decision add constraint run_admission_decision_class_ck
    check (admission_class in ('ROOT', 'CHILD', 'CONTINUATION'));

create table child_run (
    workspace_id uuid not null,
    id uuid not null,
    parent_run_id uuid not null,
    root_run_id uuid not null,
    child_run_id uuid not null,
    parent_cohort_id uuid not null,
    child_release_id uuid not null,
    child_deployment_id uuid not null,
    child_revision_id uuid not null,
    child_cohort_id uuid not null,
    depth integer not null,
    idempotency_key varchar(128) not null,
    request_digest varchar(71) not null,
    task_text text not null,
    context_json text not null,
    tools_json text not null,
    budget_json text not null,
    budget_tokens bigint not null,
    budget_cost_usd decimal(19,6) not null,
    failure_policy varchar(24) not null,
    status varchar(16) not null,
    terminal_code varchar(128),
    created_at timestamp with time zone not null,
    terminal_at timestamp with time zone,
    primary key (workspace_id, id),
    unique (workspace_id, child_run_id),
    unique (workspace_id, parent_run_id, idempotency_key),
    foreign key (workspace_id, parent_run_id, parent_cohort_id)
        references agent_run(workspace_id, id, cohort_id),
    foreign key (workspace_id, root_run_id) references agent_run(workspace_id, id),
    foreign key (workspace_id, child_run_id, child_cohort_id)
        references agent_run(workspace_id, id, cohort_id),
    foreign key (workspace_id, child_revision_id, child_deployment_id)
        references deployment_revision(workspace_id, id, deployment_id),
    foreign key (workspace_id, child_revision_id, child_release_id)
        references deployment_revision(workspace_id, id, release_id),
    constraint child_run_depth_ck check (depth > 0),
    constraint child_run_budget_ck check (budget_tokens >= 0 and budget_cost_usd >= 0),
    constraint child_run_status_ck check (status in ('STARTING', 'RUNNING', 'SUCCEEDED', 'FAILED', 'CANCELLED')
        and (status in ('SUCCEEDED', 'FAILED', 'CANCELLED')) = (terminal_at is not null)
        and (status in ('SUCCEEDED', 'FAILED', 'CANCELLED')) = (terminal_code is not null)),
    constraint child_run_failure_policy_ck check (failure_policy in ('RETURN_OUTCOME', 'FAIL_PARENT')),
    constraint child_run_digest_ck check (char_length(request_digest) = 71 and request_digest like 'sha256:%')
);
create index child_run_parent_idx on child_run(workspace_id, parent_run_id, status, created_at, id);
create index child_run_reconcile_idx on child_run(workspace_id, status, created_at, id);

create table scheduled_wakeup (
    workspace_id uuid not null,
    id uuid not null,
    session_id uuid not null,
    run_id uuid not null,
    release_id uuid not null,
    deployment_id uuid not null,
    revision_id uuid not null,
    cohort_id uuid not null,
    cohort_transition_version bigint not null,
    idempotency_key varchar(128) not null,
    request_digest varchar(71) not null,
    correlation_id varchar(128) not null,
    due_at timestamp with time zone not null,
    payload_json text not null,
    status varchar(16) not null,
    created_at timestamp with time zone not null,
    delivered_at timestamp with time zone,
    primary key (workspace_id, id),
    unique (workspace_id, run_id, idempotency_key),
    foreign key (workspace_id, session_id, run_id) references agent_session_run(workspace_id, session_id, run_id),
    foreign key (workspace_id, run_id, cohort_id) references agent_run(workspace_id, id, cohort_id),
    constraint scheduled_wakeup_status_ck check (status in ('SCHEDULED', 'DELIVERED', 'CANCELLED')
        and (status = 'DELIVERED') = (delivered_at is not null)),
    constraint scheduled_wakeup_version_ck check (cohort_transition_version > 0),
    constraint scheduled_wakeup_digest_ck check (char_length(request_digest) = 71 and request_digest like 'sha256:%')
);
create index scheduled_wakeup_due_idx on scheduled_wakeup(status, due_at, workspace_id, id);
create index scheduled_wakeup_run_idx on scheduled_wakeup(workspace_id, run_id, status, due_at, id);
