-- Issue #42: stable newest-first keyset scans for all interactions and exact-status pending/resolved views.
create index human_interaction_list_idx
    on human_interaction(workspace_id, created_at desc, id desc);
create index human_interaction_status_list_idx
    on human_interaction(workspace_id, status, created_at desc, id desc);
