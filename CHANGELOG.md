# Changelog

All notable changes to Dvara AgentWorks Core are recorded here. The project follows Semantic Versioning; release
candidate entries remain `Unreleased` until an immutable public tag and its release-qualified artifacts exist.

## [1.0.0-rc2] - Unreleased

### Added

- The complete OSS 1.0 control plane, durable run plane, Java SDK, CLI, Web Console, provider-neutral model gateway,
  tool/MCP/A2A/external-executor integrations, evaluation, memory, artifact, security, observability, packaging, and
  operator surfaces described in the public documentation.
- Reproducible Maven, OCI, Helm, and CLI release-candidate bundles with checksums, SBOMs, provenance, signatures,
  vulnerability policy, compatibility evidence, and independent verification.
- A fail-closed RC2 freeze and go/no-go contract requiring two distinct qualification passes, an exact-commit soak,
  blocker disposition, and a seven-day stabilization window.

### Security

- Structural workspace isolation, PostgreSQL RLS and least-privilege plane roles, authenticated API/session access,
  credential references with just-in-time resolution, redacted diagnostics, append-only evidence, and bounded sandbox
  controls are enabled by default.

### Changed

- The Maven reactor, Helm chart, and Compose distribution are version-frozen at `1.0.0-rc2`.
- The protected release workflow now writes the source archive to the same path used by GitHub release publication,
  and the supply-chain policy rejects future path drift.
- Core module directories, Maven artifacts, packaged filenames, and build selectors use the `agentworks-*` prefix;
  stable serialized schema, protocol, and problem identifiers remain unchanged.

### Known limitations

- This entry is not a release announcement. Until the protected public workflow produces and independently verifies
  the signed candidate, the authoritative RC verdict remains `PENDING`.
- Current limitations and deferred 1.1 work remain listed in `docs/limitations.md`; no deferred feature is silently
  represented as part of RC2.

## [1.0.0-rc1] - Not published

- The immutable RC1 tag was retained for audit history, but no GitHub release was published. RC2 supersedes that
  candidate with the release-archive publication fix and a complete qualification rerun.
