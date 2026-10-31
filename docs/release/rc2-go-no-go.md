# RC2 go/no-go contract

**Current verdict: PENDING.**

This page defines what must be true before DVARA AgentWorks `1.0.0-rc2` can receive a `GO`. It does not claim that
external release evidence already exists.

## GO requires all of the following

- The immutable `v1.0.0-rc2` tag identifies the reviewed public release commit.
- The protected release workflow produces signed Maven, CLI, OCI, and Helm artifacts from that tag.
- A different clean environment independently verifies and exercises only those public artifacts.
- Repository policy, traceability, documentation, compatibility, supply chain, security, full regression, walking
  skeleton, install, upgrade, rollback, backup restore, disaster recovery, load, and soak gates pass for the same
  commit.
- The continuous exact-commit soak runs for at least 24 hours and passes.
- The unchanged candidate completes at least 168 hours of stabilization.
- No BLOCKER or HIGH finding remains open. Every MEDIUM or LOW finding has an owner, impact, and disposition.
- `scripts/release/verify_rc2_readiness.py --evidence ...` returns `rc2-readiness=GO` against the downloaded signed
  bundle and final evidence.

## Where the final decision lives

The immutable source tag keeps this contract in its pre-decision `PENDING` state. After the evidence exists, record
the observed `GO` or `NO-GO`, exact commit, artifact digests, verification output, findings, and evidence links on
public issue #6 and the immutable GitHub prerelease. This avoids changing source merely to rewrite history after the
stabilization window.

If any required fact is absent, mismatched, or unverifiable, the verdict remains `PENDING` or becomes `NO-GO`.
