-- Issue #183: an operator's durable request to pause a run at its next safe boundary. A queued run pauses at once
-- (QUEUED -> PAUSED, with its RUN_PAUSED event naming the operator). An active run's worker holds its lease, and no
-- control event may be written under a live lease, so the request is recorded here instead: one row per run, written
-- under the run's row lock (lock order: the run, then its request). The worker observes it with every durable write and
-- at its claim, and pauses at its next safe boundary: never inside an effect, whose outcome is recorded first. The
-- RUN_PAUSED event consumes the row and names the requesting actor; a resume withdraws it, and a run that blocks or ends
-- removes it. The row carries no cohort and is not resumable work: the run it names already is.
create table agent_run_pause_request (
    workspace_id uuid not null,
    run_id uuid not null,
    requested_by varchar(255) not null,
    requested_at timestamp with time zone not null,
    reason varchar(1024),
    primary key (workspace_id, run_id),
    foreign key (workspace_id, run_id) references agent_run(workspace_id, id)
);
