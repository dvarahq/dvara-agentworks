-- Issue #185 PostgreSQL authority for the consumed bootstrap reissue values (V13). The installation bootstrap is
-- Control Plane authority (V7_1): the Control Plane reads and records consumed values and can neither change nor
-- remove one, so a value once consumed stays consumed. The Run Plane receives nothing.
grant select, insert on installation_bootstrap_reissue to agentworks_control_plane;
