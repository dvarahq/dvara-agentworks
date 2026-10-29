# Qualify the RC1 release

RC1 is ready only when one exact public commit, its immutable tag, its signed artifacts, and all qualification
evidence agree. A successful local build by itself is not a release.

The machine-readable policy is [`config/release/rc1.json`](../../config/release/rc1.json). Start from its
[`rc1-evidence-template.json`](../../config/release/rc1-evidence-template.json), and use the public
[go/no-go contract](../release/rc1-go-no-go.md) when recording the final decision.

## What must stay identical

- Version: `1.0.0-rc1`
- Tag: `v1.0.0-rc1`
- Source: `https://github.com/dvarahq/dvara-agentworks`
- Artifacts: Maven, CLI, multi-platform OCI, and Helm
- Minimum soak: 24 continuous hours on the exact release commit
- Minimum stabilization: seven complete days without changing that commit

Changing source after the tag invalidates the candidate. Never move or replace a release tag. If RC1 needs a source
fix after publication, prepare RC2 from a new reviewed commit.

## 1. Finish local qualification

Run the inexpensive policy checks first, then the full build and journeys:

```bash
python3 scripts/release/verify_rc1_readiness.py --policy-only
python3 scripts/ci/verify_repository_policy.py
python3 scripts/ci/test_policy_gates.py
python3 scripts/ci/test_supply_chain.py
./mvnw -B -ntp clean verify
./mvnw -B -ntp -Pjourney -pl agentworks-journey -am verify
scripts/release/build-release-candidate.sh qualification v1.0.0-rc1 target/oss-release-candidate
python3 scripts/release/verify_release_candidate.py \
  target/oss-release-candidate --phase build --allow-qualification
```

Do not repeatedly dispatch hosted builds while the source is changing. Fix every local failure first. Record the
exact commit and evidence for repository policy, traceability, documentation, compatibility, supply chain, security,
full regression, walking skeleton, install, upgrade, rollback, backup restore, disaster recovery, and load gates.

## 2. Freeze the public commit

Before creating the tag:

1. Confirm the public tree is clean and all required checks are green.
2. Confirm issue #14 is closed with its merged review evidence.
3. Confirm there is no unresolved BLOCKER or HIGH finding.
4. Configure `main`, the release tag, and the `oss-release` environment so only reviewed work and an approved
   `grabdoc` release can publish.
5. Create the immutable `v1.0.0-rc1` tag at the reviewed public commit.

Any accepted source change restarts qualification and stabilization on a new commit.

## 3. Build and verify the protected candidate

Run the existing **Protected OSS release** workflow once for the immutable tag. Its build stage must complete before
the protected `oss-release` environment is approved. Download the resulting signed candidate without changing it.
Record the workflow URL, source commit, manifest digest, checksum digest, signatures, SBOMs, provenance, and artifact
digests.

Use a separate clean environment to download only the public RC artifacts and verification material. Verify their
Sigstore identity, checksums, SBOMs, provenance, install, supported upgrade, rollback, backup restore, disaster
recovery, security, compatibility, load behavior, and documented quickstart. The protected and independent passes
must have different environment identities and evidence references.

## 4. Run the soak and stabilization window

Run the 24-hour soak against the same tagged commit and artifact identities. Preserve the raw samples and final
report. A failed, interrupted, or changed-commit run does not count.

Stabilization begins no earlier than the immutable freeze and lasts at least 168 hours. Every remaining MEDIUM or LOW
finding needs an owner, user or operator impact, and disposition. An open BLOCKER or HIGH finding prevents `GO`.

## 5. Record the decision

Copy the evidence template to an evidence directory outside the repository, fill it only with observed facts, and
verify it:

```bash
python3 scripts/release/verify_rc1_readiness.py --evidence /path/to/rc1-evidence.json
```

Only `rc1-readiness=GO` authorizes the final go/no-go record. Attach the evidence and exact digests to
[public issue #6](https://github.com/dvarahq/dvara-agentworks/issues/6) and the immutable GitHub release. Until then,
the verdict is `PENDING`.

## Stop conditions

- A local gate fails: fix it before tagging.
- The protected or independent pass fails: preserve the evidence and prepare a new candidate.
- Install, upgrade, rollback, or recovery fails: stop and diagnose without altering published artifacts.
- A vulnerability or BLOCKER/HIGH finding remains: do not approve publication.
- Source changes after freeze: invalidate the old candidate and restart the window.
