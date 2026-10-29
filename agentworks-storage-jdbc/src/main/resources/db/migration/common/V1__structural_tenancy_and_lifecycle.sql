create table organization (
    id uuid primary key,
    name varchar(200) not null unique,
    created_at timestamp with time zone not null default current_timestamp
);

create table workspace (
    id uuid primary key,
    organization_id uuid not null references organization(id),
    name varchar(200) not null,
    created_at timestamp with time zone not null default current_timestamp,
    unique (organization_id, name),
    unique (id, organization_id)
);

create table workspace_membership (
    organization_id uuid not null,
    workspace_id uuid not null,
    subject_id uuid not null,
    membership_role varchar(32) not null,
    created_at timestamp with time zone not null default current_timestamp,
    primary key (workspace_id, subject_id),
    foreign key (workspace_id, organization_id) references workspace(id, organization_id)
);

create table agent_definition (
    workspace_id uuid not null references workspace(id), id uuid not null,
    primary key (workspace_id, id)
);
create table agent_version (
    workspace_id uuid not null, id uuid not null, definition_id uuid not null,
    primary key (workspace_id, id),
    foreign key (workspace_id, definition_id) references agent_definition(workspace_id, id)
);
create table agent_release (
    workspace_id uuid not null, id uuid not null, version_id uuid not null, release_name varchar(128) not null,
    primary key (workspace_id, id), unique (workspace_id, release_name), unique (workspace_id, id, version_id),
    foreign key (workspace_id, version_id) references agent_version(workspace_id, id)
);
create table agent_environment (
    workspace_id uuid not null, id uuid not null, name varchar(128) not null,
    primary key (workspace_id, id), unique (workspace_id, name)
);
create table agent_deployment (
    workspace_id uuid not null, id uuid not null, environment_id uuid not null, desired_release_id uuid not null,
    revision bigint not null default 0,
    primary key (workspace_id, id),
    foreign key (workspace_id, environment_id) references agent_environment(workspace_id, id),
    foreign key (workspace_id, desired_release_id) references agent_release(workspace_id, id)
);
create table agent_run (
    workspace_id uuid not null, id uuid not null, version_id uuid not null, release_id uuid not null,
    deployment_id uuid not null, environment_id uuid not null, created_at timestamp with time zone not null,
    primary key (workspace_id, id),
    foreign key (workspace_id, version_id) references agent_version(workspace_id, id),
    foreign key (workspace_id, release_id, version_id) references agent_release(workspace_id, id, version_id),
    foreign key (workspace_id, deployment_id) references agent_deployment(workspace_id, id),
    foreign key (workspace_id, environment_id) references agent_environment(workspace_id, id)
);

create index agent_run_deployment_idx on agent_run(workspace_id, deployment_id);
create index agent_run_release_idx on agent_run(workspace_id, release_id);
