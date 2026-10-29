-- PostgreSQL is the production authority and rejects forged digests even when a writer bypasses the Java contract.
alter table artifact_object add constraint artifact_object_digest_hex_ck
    check (content_digest ~ '^sha256:[0-9a-f]{64}$');
