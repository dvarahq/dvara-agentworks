\set ON_ERROR_STOP on

-- This is intentionally a report, not a delete job. AgentWorks 1.0 retains audit/provenance, events, checkpoints,
-- idempotency tombstones, and active dependencies. Operators partition these append-only tables by time only after
-- an archive has passed the restore drill documented in the runbook.
select 'agent_run_event' as relation, count(*) as rows, min(occurred_at) as oldest, max(occurred_at) as newest
  from agent_run_event
union all
select 'agent_run_checkpoint', count(*), min(created_at), max(created_at) from agent_run_checkpoint
union all
select 'control_plane_outbox', count(*), min(created_at), max(created_at) from control_plane_outbox
union all
select 'auth_audit_event', count(*), min(occurred_at), max(occurred_at) from auth_audit_event
order by relation;

select lifecycle, count(*) from agent_run group by lifecycle order by lifecycle;
select case when quarantined_at is not null then 'QUARANTINED' else 'RETRY_SCHEDULED' end as recovery_state,
       count(*)
  from control_plane_outbox_attempt
 group by recovery_state order by recovery_state;
