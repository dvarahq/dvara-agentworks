-- Workspace-pinned workers order claimable runs by creation time. Keep terminal history out of the ordered probe so
-- a long-running installation does not rescan and sort its completed runs on every claim. The few live ACTIVE rows
-- remain in the index because an expired lease must be recoverable by the same query.
create index agent_run_workspace_claim_queue_idx
    on agent_run(workspace_id, executor_type, created_at, id)
    where lifecycle in ('QUEUED', 'ACTIVE');
