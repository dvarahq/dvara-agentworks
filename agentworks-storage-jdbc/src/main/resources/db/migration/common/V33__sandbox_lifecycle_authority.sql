-- Issue #40: durable ownership and cleanup state for externally allocated sandboxes. The provider call remains
-- outside the database transaction; PROVISIONING is committed first and gives reconciliation a stable identity.

alter table agent_run add constraint agent_run_sandbox_pins_uk
    unique (workspace_id, id, release_id, revision_id, cohort_id);

create table sandbox_resource (
    workspace_id uuid not null,
    id uuid not null,
    work_item_id uuid not null,
    organization_id uuid not null,
    run_id uuid not null,
    release_id uuid not null,
    revision_id uuid not null,
    cohort_id uuid not null,
    provider varchar(32) not null,
    state varchar(24) not null,
    expires_at timestamp with time zone not null,
    cleanup_after timestamp with time zone not null,
    cleanup_owner varchar(128),
    cleanup_fence bigint default 0 not null,
    cleanup_until timestamp with time zone,
    cleanup_failures integer default 0 not null,
    last_failure_code varchar(64),
    created_at timestamp with time zone not null,
    activated_at timestamp with time zone,
    updated_at timestamp with time zone not null,
    terminal_at timestamp with time zone,
    primary key (workspace_id, id),
    unique (workspace_id, work_item_id),
    foreign key (workspace_id, organization_id) references workspace(id, organization_id),
    foreign key (workspace_id, run_id, release_id, revision_id, cohort_id)
        references agent_run(workspace_id, id, release_id, revision_id, cohort_id),
    constraint sandbox_resource_provider_ck check (
        char_length(provider) between 1 and 32
        and provider = lower(provider)
        and provider not like '% %'),
    constraint sandbox_resource_state_ck check (state in
        ('PROVISIONING', 'ACTIVE', 'CLEANUP_PENDING', 'CLEANING', 'TERMINAL')),
    constraint sandbox_resource_lease_ck check (
        (state = 'CLEANING') = (cleanup_owner is not null)
        and (cleanup_owner is null) = (cleanup_until is null)
        and cleanup_fence >= 0),
    constraint sandbox_resource_time_ck check (
        expires_at >= created_at and cleanup_after >= created_at and updated_at >= created_at
        and (activated_at is null or activated_at >= created_at)
        and (state = 'TERMINAL') = (terminal_at is not null)),
    constraint sandbox_resource_failure_ck check (
        cleanup_failures >= 0
        and (last_failure_code is null or (
            char_length(last_failure_code) between 1 and 64
            and last_failure_code = upper(last_failure_code)
            and last_failure_code not like '% %')))
);

create index sandbox_resource_cleanup_idx
    on sandbox_resource(workspace_id, state, cleanup_after, cleanup_until, created_at);
create index sandbox_resource_run_idx on sandbox_resource(workspace_id, run_id, state);
