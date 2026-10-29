alter table promotion_approval enable row level security;
alter table promotion_approval force row level security;
create policy promotion_approval_tenant on promotion_approval
    using (workspace_id = current_setting('agentworks.workspace_id', true)::uuid)
    with check (workspace_id = current_setting('agentworks.workspace_id', true)::uuid);
grant select, insert, update on promotion_approval to agentworks_control_plane;
revoke all on promotion_approval from agentworks_run_plane;

create function agentworks_guard_promotion_approval_mutation()
returns trigger
language plpgsql
as $$
begin
    if tg_op = 'DELETE' then
        raise exception 'promotion approvals are immutable' using errcode = '23514';
    end if;
    if old.status <> 'REQUESTED'
       or new.status <> 'APPROVED'
       or new.workspace_id <> old.workspace_id
       or new.id <> old.id
       or new.environment_id <> old.environment_id
       or new.policy_digest <> old.policy_digest
       or new.release_id <> old.release_id
       or new.requested_by <> old.requested_by
       or new.created_at <> old.created_at
       or new.approved_by is null
       or new.approved_at is null then
        raise exception 'promotion approvals allow only the REQUESTED to APPROVED transition'
            using errcode = '23514';
    end if;
    return new;
end
$$;

create trigger promotion_approval_immutable
before update or delete on promotion_approval
for each row execute function agentworks_guard_promotion_approval_mutation();
