-- Issue #34: the executor type a run's release pins (manifest executor.type) becomes an immutable creation fact of
-- the run, like its other pins, so a worker's claim query selects only runs it can execute. It is set at admission;
-- the vendor migrations (V9_1) backfill existing runs from their release manifests and, on PostgreSQL, guard it.
alter table agent_run add column executor_type varchar(128);
