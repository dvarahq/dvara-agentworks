-- Issue #30: workspace-scoped AgentDefinition lineage and immutable AgentVersion content.
-- Adding NOT NULL columns without defaults intentionally fails if schema-V1 placeholder rows exist:
-- no Core writer populated these tables, and inventing names or specifications would forge history.

alter table agent_definition add column name varchar(63) not null;
alter table agent_definition add column description varchar(1024);
alter table agent_definition add column created_at timestamp with time zone not null;
alter table agent_definition add constraint agent_definition_workspace_name_uk unique (workspace_id, name);

-- spec_json holds the canonical AgentSpec bytes exactly; a JSON column type could reorder or reformat them
-- and break digest verification.
alter table agent_version add column sequence_number bigint not null;
alter table agent_version add column version_label varchar(64) not null;
alter table agent_version add column api_version varchar(64) not null;
alter table agent_version add column spec_json text not null;
alter table agent_version add column spec_digest varchar(71) not null;
alter table agent_version add column created_at timestamp with time zone not null;
alter table agent_version add constraint agent_version_label_uk unique (workspace_id, definition_id, version_label);
alter table agent_version add constraint agent_version_sequence_uk unique (workspace_id, definition_id, sequence_number);
alter table agent_version add constraint agent_version_sequence_ck check (sequence_number > 0);
alter table agent_version add constraint agent_version_digest_ck
    check (char_length(spec_digest) = 71 and spec_digest like 'sha256:%');
