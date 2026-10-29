-- PostgreSQL rejects immutable artifact rewrites and lifecycle resurrection below the Java boundary.
create or replace function agentworks_guard_artifact_object()
returns trigger language plpgsql as $$
begin
    if row(old.workspace_id, old.id, old.organization_id, old.media_type, old.content_size, old.content_digest,
            old.created_at, old.expires_at, old.classification, old.redacted, old.provenance_source,
            old.provenance_actor, old.run_id, old.release_id, old.revision_id, old.cohort_id, old.input_refs)
       is distinct from
       row(new.workspace_id, new.id, new.organization_id, new.media_type, new.content_size, new.content_digest,
            new.created_at, new.expires_at, new.classification, new.redacted, new.provenance_source,
            new.provenance_actor, new.run_id, new.release_id, new.revision_id, new.cohort_id, new.input_refs) then
        raise exception 'agentworks:IMMUTABLE_ARTIFACT artifact content facts cannot change';
    end if;
    if old.lifecycle_state = 'EXPIRED' and new.lifecycle_state = 'ACTIVE'
       or old.lifecycle_state = 'DELETED' and new.lifecycle_state <> 'DELETED' then
        raise exception 'agentworks:ARTIFACT_RESURRECTION artifact lifecycle cannot move backward';
    end if;
    if old.validation_state in ('ACCEPTED', 'REJECTED') and new.validation_state = 'PENDING' then
        raise exception 'agentworks:ARTIFACT_VALIDATION_REOPENED terminal validation cannot become pending';
    end if;
    return new;
end;
$$;

create trigger artifact_object_guard before update on artifact_object
for each row execute function agentworks_guard_artifact_object();

create or replace function agentworks_guard_artifact_pin()
returns trigger language plpgsql as $$
begin
    if not exists (
        select 1 from artifact_object a
        where a.workspace_id = new.workspace_id and a.id = new.artifact_id
          and a.organization_id = new.organization_id and a.lifecycle_state = 'ACTIVE'
    ) then
        raise exception 'agentworks:INVALID_ARTIFACT_PIN pin owner or lifecycle does not match';
    end if;
    return new;
end;
$$;

create trigger artifact_pin_guard before insert or update on artifact_pin
for each row execute function agentworks_guard_artifact_pin();
