# Issue specification workflow

Every implementation issue must use identifiers already defined by the repository requirement, use-case, test-case, ADR, Phase 0, or detailed-ID catalogs.

Before implementation, validate the issue's `## Traceability` section by generating a temporary snapshot with `scripts/ci/issue_spec.py`. When the issue is completed, commit the final snapshot under `config/traceability/issue-specs/<issue>.json`, add the matching evidence file and implementation record, and append the issue number to the traceability index.

Do not add a detailed ID merely to preserve generated or historical wording. Add a new requirement through the accepted requirements and ADR change process when the behavior is normative.
