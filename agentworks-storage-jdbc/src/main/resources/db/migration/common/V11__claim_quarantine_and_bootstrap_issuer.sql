-- Issue #181: durable claim quarantine, scoped by runtime compatibility identity. A run whose claim a worker cannot
-- complete (its history, manifest, or recorded facts cannot be interpreted by that node, or its claim keeps failing)
-- is recorded here for the refusing node's compatibility identity (its build and the event, checkpoint, and manifest
-- versions it reads). The claim query of every node of that identity skips the run until not_before, or, when
-- not_before is null, until an operator releases it (deletes the row); nodes of another identity ignore the row and
-- may claim the run. The exclusion is a predicate of the claim query rather than a bounded list of run ids, so any
-- number of quarantined runs is skipped and none can starve healthy runs. When the run ends, closed_at closes its
-- quarantines.
--
-- The row is the operator-visible signal (the administrative surface is #79). It carries no cohort and is not
-- resumable work, but the run it quarantines is: a quarantined run that has not ended still blocks its cohort's
-- quiescence, reported as the typed blocker QUARANTINED_RUN, until an operator releases, cancels, or fails it.
create table agent_run_claim_quarantine (
    workspace_id uuid not null,
    run_id uuid not null,
    compatibility varchar(128) not null,
    reason varchar(16) not null,
    code varchar(64) not null,
    quarantines integer not null,
    quarantined_by varchar(128) not null,
    quarantined_at timestamp with time zone not null,
    not_before timestamp with time zone,
    closed_at timestamp with time zone,
    primary key (workspace_id, run_id, compatibility),
    foreign key (workspace_id, run_id) references agent_run(workspace_id, id),
    constraint agent_run_claim_quarantine_ck check (reason in ('UNINTERPRETABLE', 'CLAIM_FAILING')
        and quarantines > 0 and (not_before is null or not_before > quarantined_at))
);

-- Issue #177: where the live bootstrap token was written (the issuing node's configured identity, agentworks.node-id,
-- and the file's path on it), so a node that starts without the file logs where the token is instead of replacing it.
alter table installation_bootstrap add column issued_by varchar(255);
alter table installation_bootstrap add column token_file varchar(1024);
