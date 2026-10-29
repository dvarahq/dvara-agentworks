-- Issue #185: the bootstrap reissue override is single-use. agentworks.security.bootstrap.reissue carries a one-time
-- value chosen by the operator (for example the current time). Every value any node has acted on is recorded here,
-- as a digest, while that node holds the installation_bootstrap row's lock, and a node started with a recorded value
-- never replaces the live token again. Every value is kept, not only the latest, so two nodes left with two different
-- values cannot take turns replacing each other's token: each value replaces it at most once, across all starts of all
-- nodes. The rows hold SHA-256 digests, never the values themselves.
create table installation_bootstrap_reissue (
    digest varchar(64) primary key,
    consumed_at timestamp with time zone not null,
    consumed_by varchar(255) not null
);
