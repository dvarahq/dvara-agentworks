-- Issue #34 on H2 (local development only): backfill agent_run.executor_type (V9) from each run's pinned release
-- manifest. H2 has no JSON path functions, so the value is read from the canonical manifest text, which the release
-- service writes compact with sorted keys: "executor":{"requiredCapabilities":[...],"type":"<type>"}. Required
-- capabilities are enum names, so the first "type":" after "executor":{ is the executor type. H2 carries none of the
-- PostgreSQL guards (V*_1 under db/vendor/postgresql).
update agent_run set executor_type = (
    select substring(rel.manifest_json,
               locate('"type":"', rel.manifest_json, locate('"executor":{', rel.manifest_json)) + 8,
               locate('"', rel.manifest_json,
                      locate('"type":"', rel.manifest_json, locate('"executor":{', rel.manifest_json)) + 8)
               - (locate('"type":"', rel.manifest_json, locate('"executor":{', rel.manifest_json)) + 8))
    from agent_release rel
    where rel.workspace_id = agent_run.workspace_id and rel.id = agent_run.release_id
      and locate('"executor":{', rel.manifest_json) > 0)
where executor_type is null;
