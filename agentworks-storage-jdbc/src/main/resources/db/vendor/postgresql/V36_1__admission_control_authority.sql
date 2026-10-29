-- Issue #41 PostgreSQL authority: admission pins and decisions are append-only evidence.
create or replace function agentworks_guard_admission_pins()
returns trigger language plpgsql as $$
begin
    if row(new.admission_placement, new.admission_requires_sandbox, new.admission_policy_version)
       is distinct from
       row(old.admission_placement, old.admission_requires_sandbox, old.admission_policy_version) then
        raise exception 'agentworks:IMMUTABLE_PIN admission capacity pins cannot change'
            using errcode = 'restrict_violation';
    end if;
    return new;
end;
$$;
create trigger agent_run_admission_pins_guard before update on agent_run
for each row execute function agentworks_guard_admission_pins();

create trigger run_admission_decision_append_only before update or delete on run_admission_decision
for each row execute function agentworks_reject_history_mutation();
create trigger run_admission_decision_no_truncate before truncate on run_admission_decision
for each statement execute function agentworks_reject_history_mutation();

grant select, insert on run_admission_decision to agentworks_run_plane;
grant select on run_admission_decision to agentworks_control_plane;
grant select, insert, update on run_admission_fairness to agentworks_run_plane;
grant insert (admission_placement, admission_requires_sandbox, admission_policy_version) on agent_run
    to agentworks_run_plane;
