-- Issue #181 PostgreSQL authority for the claim quarantine (V11).
-- * The Run Plane records quarantines (insert, and update of its own identity's earlier row) and closes them when a
--   run ends (update of closed_at); it reads them in its claim query. It cannot delete one: releasing a quarantine is
--   an operator action.
-- * The Control Plane lists quarantines, reports quarantined runs as quiescence blockers, and releases them (delete),
--   through JdbcClaimQuarantineRepository; its administrative API and audit are #79. It may also update a row, for
--   example to close or shorten it.
-- Neither plane gains any privilege on runs: a quarantine write only locks the run row (the Run Plane's existing
-- column update grants permit the lock) and never changes it. The bootstrap columns added by V11 are covered by
-- V7_1's table-level Control Plane grants on installation_bootstrap.
grant select, insert, update on agent_run_claim_quarantine to agentworks_run_plane;
grant select, update, delete on agent_run_claim_quarantine to agentworks_control_plane;
