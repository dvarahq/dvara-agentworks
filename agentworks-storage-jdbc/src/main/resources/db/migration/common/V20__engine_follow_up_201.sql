-- Issue #201 (the follow-ups of PR #199). PostgreSQL grants are in V20_1 under db/vendor/postgresql.

-- Medium 1: the bootstrap reissue override (#185) records, the first time any node sees a value, which token was live
-- then (target_digest; null when none was), in a transaction of its own that commits before the value is acted on. A
-- value replaces only that token: a token issued after the value was first seen, for example by the node that won the
-- race to issue the first token, is never replaced by it, even when recording the value as consumed failed and the node
-- is restarted with the same value. Rows are kept, hold SHA-256 digests only, and are never changed.
create table installation_bootstrap_reissue_seen (
    digest varchar(64) primary key,
    seen_at timestamp with time zone not null,
    seen_by varchar(255) not null,
    target_digest varchar(64)
);

-- Low 1: an operator who repeats another operator's standing pause request (#183) is audited as REPEATED once per
-- standing request, not on every retry. One row per operator who repeated the request; it goes with the request.
create table agent_run_pause_repeat (
    workspace_id uuid not null,
    run_id uuid not null,
    actor varchar(255) not null,
    repeated_at timestamp with time zone not null,
    primary key (workspace_id, run_id, actor),
    foreign key (workspace_id, run_id) references agent_run_pause_request(workspace_id, run_id) on delete cascade
);
