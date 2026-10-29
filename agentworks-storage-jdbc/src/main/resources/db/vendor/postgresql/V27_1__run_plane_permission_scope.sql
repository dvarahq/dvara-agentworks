-- The live authority derives organization scope from the bound workspace; grant only those two columns.
grant select (id, organization_id) on workspace to agentworks_run_plane;
