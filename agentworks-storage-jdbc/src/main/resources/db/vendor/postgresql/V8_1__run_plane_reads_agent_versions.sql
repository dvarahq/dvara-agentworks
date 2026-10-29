-- Issue #34: a stateless worker executes a run from its pinned AgentVersion (instructions and other authored
-- configuration), read by id within the run's workspace and accepted only when its digest equals the one the
-- release manifest recorded. AgentVersions are immutable (V2_1), so the Run Plane gains read access only; it still
-- cannot author definitions, versions, or releases. Version 8 has no common (H2) migration: nothing changes in the
-- shared schema.
grant select on agent_version to agentworks_run_plane;
