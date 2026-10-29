-- Issue #206 (PostgreSQL).

-- Low 1: V20_1 grants the Control Plane update on installation_bootstrap_reissue_seen.target_digest so that completing
-- the bootstrap can forget which token a sighting named (PR #205 review, low 9). A column grant cannot say to which
-- value, so the Control Plane could also point a sighting at any other token digest, and a reissue value would then
-- replace that token. A sighting's target may now only be cleared: an update that sets target_digest to anything but
-- null (or leaves it unchanged), or changes any other column, is refused. A new sighting, likewise, names either no
-- token or the token stored now (a node records the one live when it first sees a value, or none when it has
-- expired), never another one (#206 review). This corrects V20's comment that rows are "never changed": they are never
-- changed except to clear target_digest.
--
-- Scope (PR #208 review): the trigger fires for every role that inserts or updates rows; a role that may alter the
-- table (its owner, the migration user, or a superuser) can disable it, as with every guard here. Deleting a sighting
-- is not guarded because no plane may delete one: V20_1 grants the Control Plane select, insert, and update of
-- target_digest only, and the Run Plane nothing. The guard runs with the inserting role's privileges, so its check of
-- installation_bootstrap relies on that role's select on it, which the Control Plane has (V7_1); a role without it
-- fails closed (permission denied). It is defense in depth: the Control Plane may update a pending bootstrap row
-- itself (V7_1), which it needs to issue tokens.
create function agentworks_guard_bootstrap_reissue_seen() returns trigger
    language plpgsql as $$
begin
    if tg_op = 'INSERT' then
        if new.target_digest is not null and not exists (select 1 from installation_bootstrap b
                where b.token_digest = new.target_digest) then
            raise exception 'agentworks:REISSUE_SIGHTING_IMMUTABLE a new sighting names no token or the stored one'
                using errcode = 'check_violation';
        end if;
        return new;
    end if;
    if new.digest is distinct from old.digest or new.seen_at is distinct from old.seen_at
            or new.seen_by is distinct from old.seen_by
            or (new.target_digest is not null and new.target_digest is distinct from old.target_digest) then
        raise exception 'agentworks:REISSUE_SIGHTING_IMMUTABLE a bootstrap reissue sighting only forgets its target'
            using errcode = 'check_violation';
    end if;
    return new;
end;
$$;
do $$
begin
    execute format('alter function agentworks_guard_bootstrap_reissue_seen() set search_path = %I, pg_temp',
        current_schema());
end;
$$;
create trigger installation_bootstrap_reissue_seen_guard before insert or update on installation_bootstrap_reissue_seen
    for each row execute function agentworks_guard_bootstrap_reissue_seen();
