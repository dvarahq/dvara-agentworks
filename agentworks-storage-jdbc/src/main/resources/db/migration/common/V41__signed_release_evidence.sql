-- Issue #54: immutable canonical signed release bundles. They outlive cohort retirement.
create table release_evidence_bundle (
    workspace_id uuid not null,
    release_id uuid not null,
    bundle_json text not null,
    bundle_digest varchar(71) not null,
    manifest_digest varchar(71) not null,
    signing_key_id varchar(256) not null,
    signed_at timestamp with time zone not null,
    imported_at timestamp with time zone not null,
    primary key (workspace_id, release_id),
    foreign key (workspace_id, release_id) references agent_release(workspace_id, id),
    constraint release_bundle_digest_ck check
        (char_length(bundle_digest) = 71 and bundle_digest like 'sha256:%' and
         char_length(manifest_digest) = 71 and manifest_digest like 'sha256:%')
);
