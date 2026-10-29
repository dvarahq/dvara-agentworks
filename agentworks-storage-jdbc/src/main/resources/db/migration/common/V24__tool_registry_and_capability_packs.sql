-- Issue #38: tenant-scoped, versioned tool metadata and capability packs. Executable code and credentials are
-- deliberately absent. Version rows and membership are immutable evidence; lifecycle state is separate.

create table agent_tool_version (
    workspace_id uuid not null references workspace(id),
    namespace varchar(64) not null,
    name varchar(64) not null,
    version varchar(128) not null,
    tool_type varchar(16) not null,
    summary varchar(512) not null,
    description varchar(4096) not null,
    input_schema text not null,
    schema_hash varchar(71) not null,
    summary_tokens integer not null,
    detail_tokens integer not null,
    created_at timestamp with time zone not null,
    primary key (workspace_id, namespace, name, version),
    constraint agent_tool_type_ck check (tool_type in ('SPRING', 'JAVA', 'MCP', 'HTTP', 'SHELL', 'FILESYSTEM', 'A2A', 'AGENT')),
    constraint agent_tool_hash_ck check (char_length(schema_hash) = 71 and schema_hash like 'sha256:%'),
    constraint agent_tool_tokens_ck check (summary_tokens > 0 and detail_tokens >= summary_tokens)
);

create table agent_tool_lifecycle (
    workspace_id uuid not null,
    namespace varchar(64) not null,
    name varchar(64) not null,
    version varchar(128) not null,
    state varchar(16) not null,
    updated_at timestamp with time zone not null,
    primary key (workspace_id, namespace, name, version),
    foreign key (workspace_id, namespace, name, version)
        references agent_tool_version(workspace_id, namespace, name, version),
    constraint agent_tool_state_ck check (state in ('AVAILABLE', 'ACTIVE', 'DISABLED', 'DENIED'))
);

create table agent_tool_tag (
    workspace_id uuid not null,
    namespace varchar(64) not null,
    name varchar(64) not null,
    version varchar(128) not null,
    tag varchar(64) not null,
    primary key (workspace_id, namespace, name, version, tag),
    foreign key (workspace_id, namespace, name, version)
        references agent_tool_version(workspace_id, namespace, name, version)
);

create table agent_tool_compatibility (
    workspace_id uuid not null,
    namespace varchar(64) not null,
    name varchar(64) not null,
    version varchar(128) not null,
    requirement varchar(128) not null,
    primary key (workspace_id, namespace, name, version, requirement),
    foreign key (workspace_id, namespace, name, version)
        references agent_tool_version(workspace_id, namespace, name, version)
);

create table capability_pack_version (
    workspace_id uuid not null references workspace(id),
    pack_id varchar(128) not null,
    version varchar(128) not null,
    created_at timestamp with time zone not null,
    primary key (workspace_id, pack_id, version)
);

create table capability_pack_lifecycle (
    workspace_id uuid not null,
    pack_id varchar(128) not null,
    version varchar(128) not null,
    state varchar(16) not null,
    updated_at timestamp with time zone not null,
    primary key (workspace_id, pack_id, version),
    foreign key (workspace_id, pack_id, version)
        references capability_pack_version(workspace_id, pack_id, version),
    constraint capability_pack_state_ck check (state in ('AVAILABLE', 'ACTIVE', 'DISABLED', 'DENIED'))
);

create table capability_pack_tool (
    workspace_id uuid not null,
    pack_id varchar(128) not null,
    pack_version varchar(128) not null,
    ordinal integer not null,
    tool_namespace varchar(64) not null,
    tool_name varchar(64) not null,
    tool_version varchar(128) not null,
    primary key (workspace_id, pack_id, pack_version, ordinal),
    unique (workspace_id, pack_id, pack_version, tool_namespace, tool_name, tool_version),
    foreign key (workspace_id, pack_id, pack_version)
        references capability_pack_version(workspace_id, pack_id, version),
    foreign key (workspace_id, tool_namespace, tool_name, tool_version)
        references agent_tool_version(workspace_id, namespace, name, version),
    constraint capability_pack_ordinal_ck check (ordinal >= 0)
);

create index agent_tool_discovery_idx
    on agent_tool_version(workspace_id, namespace, name, version);
create index agent_tool_tag_search_idx
    on agent_tool_tag(workspace_id, tag, namespace, name, version);
