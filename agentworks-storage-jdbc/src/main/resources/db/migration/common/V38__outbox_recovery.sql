-- Durable, per-consumer recovery state for the Control Plane outbox (#44).  The
-- outbox remains append-only; this table contains only bounded diagnostics and
-- scheduling state, never event or exception payloads.
create table control_plane_outbox_attempt (
    consumer varchar(64) not null,
    workspace_id uuid not null,
    outbox_id uuid not null,
    attempts integer not null,
    first_failed_at timestamp with time zone not null,
    last_failed_at timestamp with time zone not null,
    next_attempt_at timestamp with time zone,
    quarantined_at timestamp with time zone,
    last_error_code varchar(128) not null,
    last_error_digest varchar(71) not null,
    release_count integer not null default 0,
    last_released_at timestamp with time zone,
    primary key (consumer, workspace_id, outbox_id),
    foreign key (workspace_id, outbox_id) references control_plane_outbox(workspace_id, id),
    constraint control_plane_outbox_attempt_count_ck check (attempts > 0 and release_count >= 0),
    constraint control_plane_outbox_attempt_quarantine_ck check (
        (quarantined_at is null and next_attempt_at is not null)
        or (quarantined_at is not null and next_attempt_at is null))
);

create index control_plane_outbox_attempt_ready_idx
    on control_plane_outbox_attempt(consumer, next_attempt_at);
create index control_plane_outbox_attempt_quarantine_idx
    on control_plane_outbox_attempt(workspace_id, consumer, quarantined_at);
