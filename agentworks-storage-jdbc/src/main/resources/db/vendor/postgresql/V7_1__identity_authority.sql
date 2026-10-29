-- Issue #74 PostgreSQL authority for identities, sessions, API tokens, bootstrap, and audit (ADR-002, ADR-012).
-- Application code follows these rules; the triggers make the reference database enforce them for direct SQL,
-- stale writers, and plane roles. Rejections carry an `agentworks:` code in the message.

-- The authentication audit trail is append-only.
create trigger auth_audit_event_append_only before update or delete on auth_audit_event
    for each row execute function agentworks_reject_history_mutation();
create trigger auth_audit_event_no_truncate before truncate on auth_audit_event
    for each statement execute function agentworks_reject_history_mutation();

-- A user's identity, organization, and identity provider never change; users are disabled, never deleted.
create function agentworks_protect_user_account() returns trigger
    language plpgsql as $$
begin
    if tg_op = 'DELETE' or new.id <> old.id or new.organization_id <> old.organization_id
            or new.username <> old.username or new.identity_provider <> old.identity_provider
            or new.created_at <> old.created_at then
        raise exception 'agentworks:IMMUTABLE_IDENTITY user identity and organization are immutable'
            using errcode = 'restrict_violation';
    end if;
    return new;
end;
$$;
create trigger user_account_protect before update or delete on user_account
    for each row execute function agentworks_protect_user_account();

-- A membership never moves to another workspace, organization, or subject; only its role changes.
create function agentworks_protect_membership() returns trigger
    language plpgsql as $$
begin
    if new.workspace_id <> old.workspace_id or new.organization_id <> old.organization_id
            or new.subject_id <> old.subject_id or new.created_at <> old.created_at then
        raise exception 'agentworks:IMMUTABLE_IDENTITY membership identity is immutable'
            using errcode = 'restrict_violation';
    end if;
    return new;
end;
$$;
create trigger workspace_membership_protect before update on workspace_membership
    for each row execute function agentworks_protect_membership();

-- Sessions and API tokens keep their binding forever and revocation is irreversible. Only use metadata and the
-- revocation itself may change; membership removal may still delete them through the cascading foreign key.
create function agentworks_protect_session() returns trigger
    language plpgsql as $$
begin
    if (new.id, new.session_digest, new.organization_id, new.user_id, new.workspace_id, new.created_at,
            new.expires_at)
            is distinct from (old.id, old.session_digest, old.organization_id, old.user_id, old.workspace_id,
            old.created_at, old.expires_at)
            or (old.revoked_at is not null and (new.revoked_at, new.revoked_reason)
                is distinct from (old.revoked_at, old.revoked_reason)) then
        raise exception 'agentworks:IMMUTABLE_CREDENTIAL session binding is immutable and revocation is final'
            using errcode = 'restrict_violation';
    end if;
    return new;
end;
$$;
create trigger auth_session_protect before update on auth_session
    for each row execute function agentworks_protect_session();

create function agentworks_protect_api_token() returns trigger
    language plpgsql as $$
begin
    if (new.id, new.token_digest, new.token_prefix, new.name, new.organization_id, new.workspace_id, new.user_id,
            new.token_role, new.created_at, new.expires_at)
            is distinct from (old.id, old.token_digest, old.token_prefix, old.name, old.organization_id,
            old.workspace_id, old.user_id, old.token_role, old.created_at, old.expires_at)
            or (old.revoked_at is not null and (new.revoked_at, new.revoked_by)
                is distinct from (old.revoked_at, old.revoked_by)) then
        raise exception 'agentworks:IMMUTABLE_CREDENTIAL token binding is immutable and revocation is final'
            using errcode = 'restrict_violation';
    end if;
    return new;
end;
$$;
create trigger api_token_protect before update on api_token
    for each row execute function agentworks_protect_api_token();

-- The bootstrap path completes once and never reopens.
create function agentworks_protect_bootstrap() returns trigger
    language plpgsql as $$
begin
    if tg_op = 'DELETE' or old.state = 'COMPLETED' then
        raise exception 'agentworks:BOOTSTRAP_COMPLETED the installation bootstrap is complete'
            using errcode = 'restrict_violation';
    end if;
    return new;
end;
$$;
create trigger installation_bootstrap_protect before update or delete on installation_bootstrap
    for each row execute function agentworks_protect_bootstrap();

-- Identity and access are Control Plane authority (ADR-002, ADR-012). The Run Plane receives nothing: it never
-- reads users, password hashes, credential digests, memberships, bootstrap state, or the audit trail.
grant select, insert, update on user_account, auth_session, api_token, installation_bootstrap
    to agentworks_control_plane;
grant delete on auth_session to agentworks_control_plane;
grant insert, update, delete on workspace_membership to agentworks_control_plane;
grant select, insert on auth_audit_event to agentworks_control_plane;
