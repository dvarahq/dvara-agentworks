#!/usr/bin/env python3
"""Validate the RC1 freeze contract and, when supplied, final go/no-go evidence."""

from __future__ import annotations

import argparse
import hashlib
import json
import math
import os
import re
import subprocess
import sys
import xml.etree.ElementTree as ET
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

ROOT = Path(os.environ.get("AGENTWORKS_REPOSITORY_ROOT", Path(__file__).resolve().parents[2]))
POLICY = Path("config/release/rc1.json")
TEMPLATE = Path("config/release/rc1-evidence-template.json")
SHA256 = re.compile(r"[0-9a-f]{64}")
COMMIT = re.compile(r"[0-9a-f]{40}")
SEMVER_RC = re.compile(r"(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)-rc[1-9][0-9]*")
NS = {"m": "http://maven.apache.org/POM/4.0.0"}


def reject_json_constant(value: str) -> None:
    raise ValueError(f"non-standard JSON constant {value}")


def load(path: Path) -> dict[str, Any]:
    return json.loads(path.read_text(encoding="utf-8"), parse_constant=reject_json_constant)


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def finite_number(value: Any) -> bool:
    if isinstance(value, bool) or not isinstance(value, (int, float)):
        return False
    try:
        return math.isfinite(value)
    except OverflowError:
        return False


def iso(value: Any, label: str, problems: list[str]) -> datetime | None:
    if not isinstance(value, str):
        problems.append(f"{label} is not an ISO-8601 timestamp")
        return None
    try:
        parsed = datetime.fromisoformat(value.replace("Z", "+00:00"))
    except ValueError:
        problems.append(f"{label} is not an ISO-8601 timestamp")
        return None
    if parsed.tzinfo is None:
        problems.append(f"{label} has no timezone")
        return None
    return parsed.astimezone(timezone.utc)


def pom_version(path: Path, parent: bool = False) -> str | None:
    root = ET.parse(path).getroot()
    query = "m:parent/m:version" if parent else "m:version"
    return root.findtext(query, namespaces=NS)


def policy_problems(root: Path = ROOT) -> list[str]:
    problems: list[str] = []
    try:
        policy = load(root / POLICY)
        template = load(root / TEMPLATE)
    except (OSError, ValueError) as error:
        return [f"invalid RC policy/template: {error}"]

    release = policy.get("release", {})
    version = release.get("version", "")
    tag = release.get("tag")
    if policy.get("schemaVersion") != 1 or not SEMVER_RC.fullmatch(version):
        problems.append("RC policy has no supported schema/version")
    if tag != f"v{version}":
        problems.append("RC tag does not match the frozen version")
    if release.get("canonicalRepository") != "dvarahq/dvara-agentworks" \
            or release.get("canonicalSource") != "https://github.com/dvarahq/dvara-agentworks":
        problems.append("RC canonical public source is not exact")

    freeze = policy.get("freeze", {})
    if freeze.get("state") != "CANDIDATE" or freeze.get("requireFullGateRerunAfterChange") is not True:
        problems.append("RC freeze must be a candidate and rerun every gate after each accepted change")
    if freeze.get("allowedAfterFreeze") != ["BLOCKER", "HIGH", "APPROVED_RELEASE_CRITICAL_MEDIUM"]:
        problems.append("post-freeze change classes are broader than the approved contract")
    if policy.get("minimumSoakHours", 0) < 24 or policy.get("minimumStabilizationHours", 0) < 168:
        problems.append("RC soak or stabilization window is too short")

    required_issues = policy.get("requiredPriorReleaseBlockers", [])
    if required_issues != [1, 2, 14, 26, 59, 60, 61, 62, 63, 64, 65, 66, 82]:
        problems.append("prior release-blocker inventory is incomplete or reordered")
    if policy.get("requiredArtifactKinds") != ["maven", "cli", "oci", "helm"]:
        problems.append("RC artifact-kind contract is incomplete")
    if policy.get("requiredPasses") != ["protected-ci", "independent-clean"]:
        problems.append("RC requires protected CI and an independent clean pass")
    required_gates = policy.get("requiredGates", [])
    if len(required_gates) != len(set(required_gates)) or set(required_gates) != {
        "repository-policy", "traceability", "documentation", "compatibility", "supply-chain", "security",
        "full-regression", "walking-skeleton", "clean-install", "supported-upgrade", "rollback",
        "backup-restore", "disaster-recovery", "load", "soak",
    }:
        problems.append("RC required-gate inventory is incomplete or duplicated")

    try:
        if pom_version(root / "pom.xml") != version:
            problems.append("root Maven version differs from the frozen RC version")
        for module in ET.parse(root / "pom.xml").getroot().findall("m:modules/m:module", NS):
            path = root / (module.text or "") / "pom.xml"
            if pom_version(path, parent=True) != version:
                problems.append(f"{path.relative_to(root)} parent version differs from the frozen RC version")
        journey = root / "agentworks-journey/pom.xml"
        if pom_version(journey, parent=True) != version:
            problems.append("journey parent version differs from the frozen RC version")
    except (OSError, ET.ParseError) as error:
        problems.append(f"cannot inspect Maven version freeze: {error}")

    try:
        chart = (root / "deploy/helm/agentworks/Chart.yaml").read_text(encoding="utf-8")
        compose = (root / "deploy/compose/compose.yaml").read_text(encoding="utf-8")
        changelog = (root / "CHANGELOG.md").read_text(encoding="utf-8")
        if not re.search(rf"(?m)^version: {re.escape(version)}$", chart) \
                or not re.search(rf'(?m)^appVersion: "{re.escape(version)}"$', chart):
            problems.append("Helm chart/app versions differ from the frozen RC version")
        if f"AGENTWORKS_VERSION:-{version}" not in compose:
            problems.append("Compose default differs from the frozen RC version")
        if f"## [{version}] - Unreleased" not in changelog:
            problems.append("CHANGELOG has no frozen RC1 candidate section")
    except OSError as error:
        problems.append(f"cannot inspect distribution version freeze: {error}")

    if template.get("schemaVersion") != 1 or template.get("verdict") != "PENDING":
        problems.append("RC evidence template must be schema v1 and PENDING")
    if template.get("release", {}).get("version") != version or template.get("release", {}).get("tag") != tag:
        problems.append("RC evidence template identity differs from policy")
    if [entry.get("issue") for entry in template.get("prerequisites", [])] != required_issues:
        problems.append("RC evidence template prerequisite inventory differs from policy")
    if set(template.get("passes", {})) != set(policy.get("requiredPasses", [])):
        problems.append("RC evidence template pass inventory differs from policy")
    return problems


def release_bundle_problems(artifacts: dict[str, Any], commit: str, root: Path,
                            evidence_base: Path) -> list[str]:
    problems: list[str] = []
    if not isinstance(commit, str) or not COMMIT.fullmatch(commit):
        return ["signed release bundle cannot be verified without an exact release commit"]
    bundle_value = artifacts.get("bundlePath")
    if not isinstance(bundle_value, str) or not bundle_value.strip():
        return ["artifacts.bundlePath does not identify the downloaded signed release bundle"]
    bundle = Path(bundle_value).expanduser()
    if not bundle.is_absolute():
        bundle = evidence_base / bundle
    bundle = bundle.resolve()
    manifest = bundle / "MANIFEST.json"
    checksums = bundle / "SHA256SUMS"
    source_path = bundle / "SOURCE.json"
    try:
        if sha256(manifest) != artifacts.get("manifestSha256"):
            problems.append("artifacts.manifestSha256 does not match the signed release bundle")
        if sha256(checksums) != artifacts.get("checksumsSha256"):
            problems.append("artifacts.checksumsSha256 does not match the signed release bundle")
        source = load(source_path)
    except (OSError, ValueError) as error:
        return [f"cannot inspect the signed release bundle: {error}"]
    if not isinstance(source, dict):
        return ["signed release bundle SOURCE.json is not an object"]
    policy = load(root / POLICY)["release"]
    if source.get("repository") != policy["canonicalSource"] \
            or source.get("tag") != policy["tag"] \
            or source.get("commit") != commit \
            or source.get("releaseQualified") is not True:
        problems.append("signed release bundle is not the canonical release-qualified tag and commit")
    try:
        result = subprocess.run([
            sys.executable, str(root / "scripts/release/verify_release_candidate.py"), str(bundle),
            "--phase", "release", "--expected-commit", commit,
        ], cwd=root, capture_output=True, text=True, check=False, timeout=120)
    except (OSError, subprocess.TimeoutExpired) as error:
        return problems + [f"cannot run signed release bundle verification: {error}"]
    if result.returncode:
        detail = (result.stderr or result.stdout).strip()
        problems.append("signed release bundle verification failed" + (f": {detail}" if detail else ""))
    return problems


def evidence_problems(evidence: dict[str, Any], root: Path = ROOT, *, verify_external: bool = True,
                      evidence_base: Path | None = None, now: datetime | None = None) -> list[str]:
    problems = policy_problems(root)
    if problems:
        return problems
    if not isinstance(evidence, dict):
        return ["evidence root is not an object"]
    policy = load(root / POLICY)
    release_policy = policy["release"]
    release = evidence.get("release", {})
    if not isinstance(release, dict):
        problems.append("release is not an object")
        release = {}
    commit = release.get("commit", "")
    if evidence.get("schemaVersion") != 1:
        problems.append("evidence schemaVersion is not 1")
    if release.get("version") != release_policy["version"] or release.get("tag") != release_policy["tag"]:
        problems.append("evidence release identity differs from policy")
    if release.get("repository") != release_policy["canonicalSource"]:
        problems.append("evidence repository is not the canonical public source")
    if not isinstance(commit, str) or not COMMIT.fullmatch(commit) or release.get("dirty") is not False:
        problems.append("release commit is not exact and clean")
    frozen_at = iso(release.get("frozenAt"), "release.frozenAt", problems)

    artifacts = evidence.get("artifacts", {})
    if not isinstance(artifacts, dict):
        problems.append("artifacts is not an object")
        artifacts = {}
    if artifacts.get("releaseQualified") is not True or artifacts.get("independentlyVerified") is not True:
        problems.append("artifacts are not release-qualified and independently verified")
    for field in ("manifestSha256", "checksumsSha256"):
        value = artifacts.get(field)
        if not isinstance(value, str) or not SHA256.fullmatch(value):
            problems.append(f"artifacts.{field} is not an exact SHA-256")
    if artifacts.get("kinds") != policy["requiredArtifactKinds"]:
        problems.append("artifact kinds differ from policy")
    if verify_external:
        problems.extend(release_bundle_problems(artifacts, commit, root,
                                                evidence_base or Path.cwd()))

    prerequisites = evidence.get("prerequisites", [])
    if not isinstance(prerequisites, list):
        problems.append("prerequisites is not a list")
        prerequisites = []
    elif not all(isinstance(entry, dict) for entry in prerequisites):
        problems.append("prerequisites contains a non-object entry")
        prerequisites = [entry for entry in prerequisites if isinstance(entry, dict)]
    if [entry.get("issue") for entry in prerequisites] != policy["requiredPriorReleaseBlockers"]:
        problems.append("prerequisite issue inventory differs from policy")
    for entry in prerequisites:
        status = entry.get("status")
        disposition = entry.get("disposition")
        if status == "CLOSED":
            if not isinstance(disposition, str) or not disposition.strip():
                problems.append(f"issue #{entry.get('issue')} has no closure evidence")
        elif status == "REMOVED_BY_ADR":
            if not isinstance(disposition, str) or not re.search(r"ADR-[0-9]{3}", disposition):
                problems.append(f"issue #{entry.get('issue')} has no approved scope ADR")
        else:
            problems.append(f"issue #{entry.get('issue')} is not closed or removed by ADR")

    passes = evidence.get("passes", {})
    if not isinstance(passes, dict):
        problems.append("passes is not an object")
        passes = {}
    if set(passes) != set(policy["requiredPasses"]):
        problems.append("qualification passes differ from policy")
    environments: set[str] = set()
    pass_evidence: set[str] = set()
    for name in policy["requiredPasses"]:
        record = passes.get(name, {})
        if not isinstance(record, dict):
            problems.append(f"{name} pass is not an object")
            record = {}
        if record.get("status") != "PASS" or record.get("commit") != commit:
            problems.append(f"{name} did not pass for the release commit")
        environment = record.get("environment")
        reference = record.get("evidence")
        if not isinstance(environment, str) or not environment.strip():
            problems.append(f"{name} has no environment identity")
        else:
            environments.add(environment)
        if not isinstance(reference, str) or not reference.strip():
            problems.append(f"{name} has no evidence reference")
        else:
            pass_evidence.add(reference)
    if len(environments) != len(policy["requiredPasses"]):
        problems.append("qualification passes are not from distinct environments")
    if len(pass_evidence) != len(policy["requiredPasses"]):
        problems.append("qualification passes reuse the same evidence")

    gates = evidence.get("gates", {})
    if not isinstance(gates, dict):
        problems.append("gates is not an object")
        gates = {}
    if set(gates) != set(policy["requiredGates"]):
        problems.append("gate evidence differs from the complete required set")
    for name in policy["requiredGates"]:
        record = gates.get(name, {})
        if not isinstance(record, dict):
            problems.append(f"gate {name} is not an object")
            record = {}
        if record.get("status") != "PASS" or record.get("commit") != commit:
            problems.append(f"gate {name} did not pass for the release commit")
        if not isinstance(record.get("evidence"), str) or not record["evidence"].strip():
            problems.append(f"gate {name} has no evidence reference")

    soak = evidence.get("soak", {})
    if not isinstance(soak, dict):
        problems.append("soak is not an object")
        soak = {}
    duration = soak.get("durationHours")
    if soak.get("commit") != commit or soak.get("verdict") != "PASS" \
            or not finite_number(duration) or duration < policy["minimumSoakHours"]:
        problems.append("soak did not pass the minimum duration on the release commit")
    if not isinstance(soak.get("evidence"), str) or not soak["evidence"].strip():
        problems.append("soak has no evidence reference")

    findings = evidence.get("findings", [])
    if not isinstance(findings, list):
        problems.append("findings is not a list")
    else:
        for index, finding in enumerate(findings):
            if not isinstance(finding, dict):
                problems.append(f"finding {index} is not an object")
                continue
            severity = finding.get("severity")
            status = finding.get("status")
            if severity not in {"BLOCKER", "HIGH", "MEDIUM", "LOW"} or status not in {"OPEN", "RESOLVED"}:
                problems.append(f"finding {index} has invalid severity/status")
            for field in ("owner", "impact", "disposition"):
                if not isinstance(finding.get(field), str) or not finding[field].strip():
                    problems.append(f"finding {index} has no {field}")
            if severity in {"BLOCKER", "HIGH"} and status == "OPEN":
                problems.append(f"finding {index} is an unresolved {severity}")

    stabilization = evidence.get("stabilization", {})
    if not isinstance(stabilization, dict):
        problems.append("stabilization is not an object")
        stabilization = {}
    started = iso(stabilization.get("startedAt"), "stabilization.startedAt", problems)
    ended = iso(stabilization.get("endedAt"), "stabilization.endedAt", problems)
    verdict_at = iso(evidence.get("verdictAt"), "verdictAt", problems)
    observed_now = now or datetime.now(timezone.utc)
    for label, timestamp in (("release.frozenAt", frozen_at), ("stabilization.startedAt", started),
                             ("stabilization.endedAt", ended), ("verdictAt", verdict_at)):
        if timestamp and timestamp > observed_now:
            problems.append(f"{label} is in the future")
    if started and frozen_at and started < frozen_at:
        problems.append("stabilization started before the release freeze")
    if started and ended and (ended - started).total_seconds() < policy["minimumStabilizationHours"] * 3600:
        problems.append("stabilization window is shorter than policy")
    if ended and verdict_at and verdict_at < ended:
        problems.append("go/no-go verdict predates stabilization completion")
    if evidence.get("verdict") != "GO":
        problems.append("go/no-go verdict is not GO")
    return problems


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--policy-only", action="store_true")
    parser.add_argument("--evidence", type=Path)
    args = parser.parse_args()
    if args.policy_only and args.evidence:
        parser.error("choose --policy-only or --evidence")
    if args.evidence:
        try:
            evidence = load(args.evidence)
        except (OSError, ValueError) as error:
            raise SystemExit(f"rc1-readiness=FAIL: invalid evidence: {error}") from error
        problems = evidence_problems(evidence, evidence_base=args.evidence.resolve().parent)
        if problems:
            raise SystemExit("rc1-readiness=FAIL: " + "; ".join(problems))
        print(f"rc1-readiness=GO commit={evidence['release']['commit']} tag={evidence['release']['tag']}")
        return
    problems = policy_problems()
    if problems:
        raise SystemExit("rc1-policy=FAIL: " + "; ".join(problems))
    policy = load(ROOT / POLICY)
    print(f"rc1-readiness=PENDING tag={policy['release']['tag']} external-evidence-required=true")


if __name__ == "__main__":
    main()
