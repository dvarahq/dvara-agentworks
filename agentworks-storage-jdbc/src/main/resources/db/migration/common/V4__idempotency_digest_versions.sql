-- Issue #155: idempotency keys and request fingerprints become versioned, purpose-separated HMAC-SHA-256
-- digests. Existing rows keep version 0, the unkeyed SHA-256 digests written by issue #31.
alter table lifecycle_idempotency add column digest_version integer default 0 not null;
alter table agent_run add column idempotency_digest_version integer;
update agent_run set idempotency_digest_version = 0 where idempotency_key_digest is not null;
alter table agent_run add constraint agent_run_idempotency_version_ck
    check ((idempotency_key_digest is null) = (idempotency_digest_version is null));
-- A miss proves a key unused only when every record in scope carries a retained key version.
create index lifecycle_idempotency_version_idx on lifecycle_idempotency(workspace_id, namespace, digest_version);
create index agent_run_idempotency_version_idx on agent_run(workspace_id, deployment_id, idempotency_digest_version);
