# Changelog

All notable changes to Dvara AgentWorks Core are recorded here. The project follows Semantic Versioning; release
candidate entries remain `Unreleased` until an immutable public tag and its release-qualified artifacts exist.

## [1.0.0-rc4] - Unreleased

### Fixed

- The signed release manifest now binds the command-line client to mode `0755`, and release verification rejects
  missing or changed executable permissions.
- Protected publication restores the signed mode after GitHub artifact transfer, runs the verified command-line
  client, and extracts the final release archive to prove it remains executable.
- Release workflows dispatched from any ref other than the exact candidate tag skip the costly isolated build.
- GitHub releases for prerelease tags are marked as prereleases automatically.

### Changed

- The Maven reactor, Helm chart, Compose distribution, and qualification policy are version-frozen at
  `1.0.0-rc4`.
- RC4 replaces RC3 after independent RC3 verification found that the hosted artifact handoff removed the executable
  bit before the final release archive was created.

## [1.0.0-rc3] - 2026-10-31

### Fixed

- Public traceability and security checks now validate only evidence that is intentionally present in the public
  source tree, while the private source keeps its additional internal checks.
- The observability guide and executable contract use the same metric and diagnostic names.
- Release evidence preserves executable permissions on the command-line client before hosted artifact transfer.
- RC qualification, final audit, and GA publication policies route evidence through active public issues.

### Changed

- The Maven reactor, Helm chart, Compose distribution, and qualification policy are version-frozen at
  `1.0.0-rc3`.
- RC3 replaces RC2 after independent RC2 qualification found release-blocking verification-contract defects.

### Known limitations

- RC3 was published for evaluation, but independent archive extraction recorded `NO-GO`: the packaged command-line
  client was mode `0644` and could not run directly.
- The RC3 tag and published evidence remain immutable; RC4 supersedes that candidate with a mode-bound release
  contract and a complete qualification rerun.

## [1.0.0-rc2] - 2026-10-31

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

- RC2 was published for evaluation, but independent qualification recorded `NO-GO`; it is not approved for
  production use.
- Current limitations and deferred 1.1 work remain listed in `docs/limitations.md`; no deferred feature is silently
  represented as part of RC2.

## [1.0.0-rc1] - Not published

- The immutable RC1 tag was retained for audit history, but no GitHub release was published. RC2 supersedes that
  candidate with the release-archive publication fix and a complete qualification rerun.
