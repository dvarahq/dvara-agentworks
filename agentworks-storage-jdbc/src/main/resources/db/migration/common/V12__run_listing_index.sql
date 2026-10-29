-- Issue #35: keyset pagination of /api/v1 collections. Each index serves one collection's order and its row-value
-- position predicate within one workspace, so a page seeks to its cursor instead of scanning earlier rows.
-- GET /api/v1/runs pages newest first by (created_at, id); deployment and release filters keep their V1 indexes.
-- Environments and definitions page by (name, id), which their unique (workspace_id, name) keys already serve.
-- Run history pages and streams read agent_run_event by its primary key (workspace_id, run_id, sequence).
create index agent_run_listing_idx on agent_run(workspace_id, created_at, id);
create index agent_release_listing_idx on agent_release(workspace_id, created_at, id);
create index agent_deployment_listing_idx on agent_deployment(workspace_id, created_at, id);
