-- Evaluation definitions/results/baselines are tenant-scoped and immutable; only run progress may advance.
alter table evaluation_dataset enable row level security;
alter table evaluation_dataset force row level security;
create policy evaluation_dataset_tenant on evaluation_dataset using (workspace_id = current_setting('agentworks.workspace_id', true)::uuid) with check (workspace_id = current_setting('agentworks.workspace_id', true)::uuid);
alter table evaluation_case enable row level security;
alter table evaluation_case force row level security;
create policy evaluation_case_tenant on evaluation_case using (workspace_id = current_setting('agentworks.workspace_id', true)::uuid) with check (workspace_id = current_setting('agentworks.workspace_id', true)::uuid);
alter table evaluation_evaluator enable row level security;
alter table evaluation_evaluator force row level security;
create policy evaluation_evaluator_tenant on evaluation_evaluator using (workspace_id = current_setting('agentworks.workspace_id', true)::uuid) with check (workspace_id = current_setting('agentworks.workspace_id', true)::uuid);
alter table evaluation_run enable row level security;
alter table evaluation_run force row level security;
create policy evaluation_run_tenant on evaluation_run using (workspace_id = current_setting('agentworks.workspace_id', true)::uuid) with check (workspace_id = current_setting('agentworks.workspace_id', true)::uuid);
alter table evaluation_result enable row level security;
alter table evaluation_result force row level security;
create policy evaluation_result_tenant on evaluation_result using (workspace_id = current_setting('agentworks.workspace_id', true)::uuid) with check (workspace_id = current_setting('agentworks.workspace_id', true)::uuid);
alter table evaluation_baseline enable row level security;
alter table evaluation_baseline force row level security;
create policy evaluation_baseline_tenant on evaluation_baseline using (workspace_id = current_setting('agentworks.workspace_id', true)::uuid) with check (workspace_id = current_setting('agentworks.workspace_id', true)::uuid);

create trigger evaluation_dataset_immutable before update or delete on evaluation_dataset for each row execute function agentworks_reject_history_mutation();
create function agentworks_guard_evaluation_case() returns trigger language plpgsql as $$
declare retention_deadline timestamp with time zone;
begin
    if tg_op = 'DELETE' then
        raise exception 'agentworks:IMMUTABLE_HISTORY history cannot be deleted' using errcode = 'restrict_violation';
    end if;
    select expires_at into retention_deadline from evaluation_dataset
      where workspace_id = old.workspace_id and id = old.dataset_id and sensitive;
    if retention_deadline is null or retention_deadline > current_timestamp or old.purged_at is not null
       or new.workspace_id <> old.workspace_id or new.dataset_id <> old.dataset_id or new.case_id <> old.case_id
       or new.tags_json <> old.tags_json or new.input_digest <> old.input_digest
       or new.expected_digest <> old.expected_digest
       or new.input_json <> '{"$agentworksPurgedDigest":"' || old.input_digest || '"}'
       or new.expected_json <> '{"$agentworksPurgedDigest":"' || old.expected_digest || '"}'
       or new.purged_at is null then
        raise exception 'agentworks:IMMUTABLE_HISTORY evaluation case can only be purged after retention expires' using errcode = 'restrict_violation';
    end if;
    return new;
end $$;
create trigger evaluation_case_immutable before update or delete on evaluation_case for each row execute function agentworks_guard_evaluation_case();
create trigger evaluation_evaluator_immutable before update or delete on evaluation_evaluator for each row execute function agentworks_reject_history_mutation();
create trigger evaluation_result_immutable before update or delete on evaluation_result for each row execute function agentworks_reject_history_mutation();
create trigger evaluation_baseline_immutable before update or delete on evaluation_baseline for each row execute function agentworks_reject_history_mutation();

create function agentworks_guard_evaluation_run() returns trigger language plpgsql as $$
begin
    if old.workspace_id <> new.workspace_id or old.id <> new.id or old.dataset_id <> new.dataset_id
       or old.dataset_digest <> new.dataset_digest or old.evaluator_ids_json <> new.evaluator_ids_json
       or old.evaluator_set_digest <> new.evaluator_set_digest or old.subject_identity <> new.subject_identity
       or old.release_evidence_ref <> new.release_evidence_ref or old.budget_json <> new.budget_json
       or old.request_json <> new.request_json or old.request_digest <> new.request_digest
       or old.idempotency_key <> new.idempotency_key or old.total_results <> new.total_results
       or old.started_at <> new.started_at then
        raise exception 'agentworks:IMMUTABLE_EVALUATION evaluation run identity and plan cannot change' using errcode = 'restrict_violation';
    end if;
    if old.status = 'COMPLETED' or old.status = 'BUDGET_EXCEEDED' then
        raise exception 'agentworks:TERMINAL_EVALUATION terminal evaluation cannot resume' using errcode = 'check_violation';
    end if;
    if new.completed_results < old.completed_results or new.passed_results < old.passed_results then
        raise exception 'agentworks:EVALUATION_SUMMARY_REGRESSION evaluation progress cannot move backward' using errcode = 'check_violation';
    end if;
    return new;
end $$;
create trigger evaluation_run_guard before update on evaluation_run for each row execute function agentworks_guard_evaluation_run();
create trigger evaluation_run_no_delete before delete on evaluation_run for each row execute function agentworks_reject_history_mutation();

grant select, insert on evaluation_dataset, evaluation_case, evaluation_evaluator, evaluation_result, evaluation_baseline to agentworks_control_plane;
grant update (input_json, expected_json, purged_at) on evaluation_case to agentworks_control_plane;
grant select, insert, update on evaluation_run to agentworks_control_plane;
