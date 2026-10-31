#!/usr/bin/env python3
"""Validate the final GA audit contract and independently signed exact-RC evidence."""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import shutil
import subprocess
import sys
import xml.etree.ElementTree as ET
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

ROOT = Path(os.environ.get("AGENTWORKS_REPOSITORY_ROOT", Path(__file__).resolve().parents[2]))
POLICY = Path("config/release/ga-audit.json")
TEMPLATE = Path("config/release/ga-audit-evidence-template.json")
COMMIT = re.compile(r"[0-9a-f]{40}")
SHA256 = re.compile(r"[0-9a-f]{64}")
ARCH_ID = re.compile(r"ARCH-(?:00[1-9]|01[0-9]|02[0-2])")
NS = {"m": "http://maven.apache.org/POM/4.0.0"}


def reject_json_constant(value: str) -> None:
    raise ValueError(f"non-standard JSON constant {value}")


def load(path: Path) -> dict[str, Any]:
    return json.loads(path.read_text(encoding="utf-8"), parse_constant=reject_json_constant)


def digest(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def resolve(base: Path, value: Any) -> Path | None:
    if not isinstance(value, str) or not value.strip():
        return None
    path = Path(value).expanduser()
    return path.resolve() if path.is_absolute() else (base / path).resolve()


def bound_file(record: Any, base: Path, label: str, problems: list[str]) -> Path | None:
    if not isinstance(record, dict):
        problems.append(f"{label} is not an object")
        return None
    path = resolve(base, record.get("path"))
    expected = record.get("sha256")
    if path is None or not path.is_file():
        problems.append(f"{label} path is missing")
        return None
    if not isinstance(expected, str) or not SHA256.fullmatch(expected) or digest(path) != expected:
        problems.append(f"{label} SHA-256 does not match")
    return path


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


def test_reference_exists(root: Path, reference: Any) -> bool:
    if not isinstance(reference, str) or "#" not in reference:
        return False
    relative, method = reference.split("#", 1)
    path = root / relative
    if not path.is_file() or not method:
        return False
    if method == "*":
        return True
    text = path.read_text(encoding="utf-8")
    if path.suffix == ".py":
        return re.search(rf"^\s*def\s+{re.escape(method)}\s*\(", text, re.MULTILINE) is not None
    return re.search(rf"\b{re.escape(method)}\s*\(", text) is not None


def production_java(root: Path):
    for module in root.glob("agentworks-*"):
        yield from module.glob("src/main/java/**/*.java")


def local_independence_problems(root: Path, policy: dict[str, Any]) -> list[str]:
    problems: list[str] = []
    forbidden = tuple(policy["forbiddenCoreDependencies"])
    for pom in [root / "pom.xml", *root.glob("agentworks-*/pom.xml")]:
        try:
            document = ET.parse(pom).getroot()
        except (OSError, ET.ParseError) as error:
            problems.append(f"cannot inspect {pom.relative_to(root)}: {error}")
            continue
        for dependency in document.findall("m:dependencies/m:dependency", NS):
            group = dependency.findtext("m:groupId", default="", namespaces=NS)
            artifact = dependency.findtext("m:artifactId", default="", namespaces=NS)
            coordinate = f"{group}:{artifact}"
            if any(group == item or group.startswith(item + ".") for item in forbidden):
                problems.append(f"forbidden Core dependency {coordinate} in {pom.relative_to(root)}")
    deferred = tuple(value.replace(".", "/") for value in policy["deferredProductionPackages"])
    for source in production_java(root):
        relative = source.relative_to(root).as_posix()
        normalized = relative.replace("/src/main/java/", "/")
        if any(marker in normalized for marker in deferred):
            problems.append(f"deferred production package exists: {relative}")
    return problems


def policy_problems(root: Path = ROOT) -> list[str]:
    try:
        policy = load(root / POLICY)
        template = load(root / TEMPLATE)
    except (OSError, ValueError) as error:
        return [f"invalid GA policy/template: {error}"]
    problems: list[str] = []
    if policy.get("schemaVersion") != 1 or template.get("schemaVersion") != 1:
        problems.append("GA policy and evidence template must use schema version 1")
    release = policy.get("release", {})
    if release != {"version": "1.0.0-rc4", "tag": "v1.0.0-rc4",
                    "canonicalSource": "https://github.com/dvarahq/dvara-agentworks"}:
        problems.append("GA policy release identity differs from the frozen RC")
    if policy.get("requiredPrerequisiteIssues") != [33]:
        problems.append("GA prerequisite inventory must contain public RC4 issue #33")
    if policy.get("allowedVerdicts") != ["PASS", "PASS_WITH_NON_BLOCKING_FOLLOW_UPS", "FAIL"]:
        problems.append("GA verdict vocabulary differs from ADR-024")
    if policy.get("reportSigning") != {
        "certificateIdentity": "https://github.com/dvarahq/dvara-agentworks/.github/workflows/release.yml@refs/tags/v1.0.0-rc4",
        "certificateOidcIssuer": "https://token.actions.githubusercontent.com",
    }:
        problems.append("GA report signer is not the canonical protected release workflow")
    required_audits = policy.get("requiredAudits", [])
    if len(required_audits) != 7 or len(set(required_audits)) != 7:
        problems.append("GA audit inventory must contain seven unique audit classes")
    invariants = policy.get("invariants", [])
    ids = [entry.get("id") for entry in invariants if isinstance(entry, dict)]
    expected = [f"ARCH-{number:03d}" for number in range(1, 23)]
    if ids != expected or len(invariants) != 22:
        problems.append("GA policy must define exactly ordered ARCH-001 through ARCH-022")
    for entry in invariants:
        if not isinstance(entry, dict):
            problems.append("GA invariant is not an object")
            continue
        identifier = entry.get("id", "unknown")
        if not ARCH_ID.fullmatch(str(identifier)) or not isinstance(entry.get("name"), str):
            problems.append(f"invalid invariant identity {identifier}")
        for kind in ("positive", "negative"):
            references = entry.get(kind)
            if not isinstance(references, list) or not references:
                problems.append(f"{identifier} has no {kind} evidence")
            else:
                for reference in references:
                    if not test_reference_exists(root, reference):
                        problems.append(f"{identifier} references missing {kind} test {reference}")
    expected_template_release = {
        "version": release.get("version"), "tag": release.get("tag"),
        "repository": release.get("canonicalSource"), "commit": None, "dirty": None,
    }
    if template.get("release") != expected_template_release or template.get("verdict") != "PENDING":
        problems.append("GA evidence template must be PENDING and share the frozen RC identity")
    prerequisites = template.get("prerequisites", [])
    if [entry.get("issue") for entry in prerequisites if isinstance(entry, dict)] != [33]:
        problems.append("GA evidence template prerequisite inventory differs from policy")
    if template.get("invariants") != {} or template.get("audits") != {}:
        problems.append("GA evidence template cannot pre-attest invariant or audit results")
    problems.extend(local_independence_problems(root, policy))
    return problems


def verify_command(command: list[str], root: Path, label: str, problems: list[str], timeout: int = 180) -> None:
    try:
        result = subprocess.run(command, cwd=root, capture_output=True, text=True, check=False, timeout=timeout)
    except (OSError, subprocess.TimeoutExpired) as error:
        problems.append(f"cannot verify {label}: {error}")
        return
    if result.returncode:
        detail = (result.stderr or result.stdout).strip()
        problems.append(f"{label} verification failed" + (f": {detail}" if detail else ""))


def evidence_problems(evidence: dict[str, Any], root: Path = ROOT, *, evidence_base: Path | None = None,
                      verify_external: bool = True, now: datetime | None = None) -> list[str]:
    problems = policy_problems(root)
    if problems:
        return problems
    if not isinstance(evidence, dict):
        return ["evidence root is not an object"]
    policy = load(root / POLICY)
    base = evidence_base or Path.cwd()
    observed = (now or datetime.now(timezone.utc)).astimezone(timezone.utc)
    release = evidence.get("release", {})
    if not isinstance(release, dict):
        problems.append("release is not an object"); release = {}
    commit = release.get("commit")
    if evidence.get("schemaVersion") != 1:
        problems.append("evidence schemaVersion is not 1")
    if release.get("version") != policy["release"]["version"] or release.get("tag") != policy["release"]["tag"] \
            or release.get("repository") != policy["release"]["canonicalSource"]:
        problems.append("evidence release identity differs from GA policy")
    if not isinstance(commit, str) or not COMMIT.fullmatch(commit) or release.get("dirty") is not False:
        problems.append("GA release commit is not exact and clean")

    prerequisites = evidence.get("prerequisites", [])
    if not isinstance(prerequisites, list) or [item.get("issue") for item in prerequisites if isinstance(item, dict)] != [33]:
        problems.append("GA prerequisite evidence differs from public RC4 issue #33")
    else:
        for item in prerequisites:
            number = item.get("issue")
            path = resolve(base, item.get("evidence"))
            if item.get("status") != "CLOSED" or item.get("commit") != commit:
                problems.append(f"issue #{number} is not closed for the exact RC commit")
            if path is None or not path.is_file():
                problems.append(f"issue #{number} evidence path is missing")
            elif not isinstance(item.get("evidenceSha256"), str) \
                    or not SHA256.fullmatch(item["evidenceSha256"]) \
                    or digest(path) != item["evidenceSha256"]:
                problems.append(f"issue #{number} evidence SHA-256 does not match")
            elif number == 33:
                try:
                    readiness = load(path)
                except (OSError, ValueError) as error:
                    problems.append(f"cannot inspect public issue #33 evidence: {error}")
                else:
                    readiness_artifacts = readiness.get("artifacts", {})
                    outer_artifacts = evidence.get("artifacts", {})
                    outer_artifacts = outer_artifacts if isinstance(outer_artifacts, dict) else {}
                    if readiness.get("release", {}).get("commit") != commit or readiness.get("verdict") != "GO":
                        problems.append("public issue #33 evidence does not authorize GO for the exact RC commit")
                    if not isinstance(readiness_artifacts, dict) \
                            or readiness_artifacts.get("manifestSha256") != outer_artifacts.get("manifestSha256") \
                            or readiness_artifacts.get("checksumsSha256") != outer_artifacts.get("checksumsSha256"):
                        problems.append("public issue #33 evidence names different RC artifacts")

    artifacts = evidence.get("artifacts", {})
    if not isinstance(artifacts, dict):
        problems.append("artifacts is not an object"); artifacts = {}
    for field in ("manifestSha256", "checksumsSha256"):
        if not isinstance(artifacts.get(field), str) or not SHA256.fullmatch(artifacts[field]):
            problems.append(f"artifacts.{field} is not an exact SHA-256")
    rc_evidence = resolve(base, artifacts.get("rcEvidencePath"))
    if rc_evidence is None or not rc_evidence.is_file():
        problems.append("artifacts.rcEvidencePath does not identify final public issue #33 evidence")
    else:
        try:
            rc_data = load(rc_evidence)
        except (OSError, ValueError) as error:
            problems.append(f"cannot inspect final public issue #33 evidence: {error}")
        else:
            if rc_data.get("release", {}).get("commit") != commit:
                problems.append("final public issue #33 evidence names a different RC commit")
            rc_artifacts = rc_data.get("artifacts", {})
            if rc_artifacts.get("manifestSha256") != artifacts.get("manifestSha256") \
                    or rc_artifacts.get("checksumsSha256") != artifacts.get("checksumsSha256"):
                problems.append("GA artifact digests differ from final public issue #33 evidence")
        if verify_external:
            verify_command([sys.executable, str(root / "scripts/release/verify_rc4_readiness.py"),
                            "--evidence", str(rc_evidence)], root, "RC4 evidence", problems)

    invariant_results = evidence.get("invariants", {})
    expected_ids = [entry["id"] for entry in policy["invariants"]]
    if not isinstance(invariant_results, dict) or set(invariant_results) != set(expected_ids):
        problems.append("GA evidence does not cover exactly all 22 frozen invariants")
        invariant_results = invariant_results if isinstance(invariant_results, dict) else {}
    for identifier in expected_ids:
        result = invariant_results.get(identifier, {})
        if not isinstance(result, dict) or result.get("status") != "PASS" or result.get("commit") != commit:
            problems.append(f"{identifier} did not pass for the exact RC commit")
            continue
        for kind in ("positiveEvidence", "negativeEvidence"):
            bound_file(result.get(kind), base, f"{identifier} {kind}", problems)

    audits = evidence.get("audits", {})
    if not isinstance(audits, dict) or set(audits) != set(policy["requiredAudits"]):
        problems.append("GA evidence does not cover the complete audit inventory")
        audits = audits if isinstance(audits, dict) else {}
    for name in policy["requiredAudits"]:
        result = audits.get(name, {})
        if not isinstance(result, dict) or result.get("status") != "PASS" or result.get("commit") != commit:
            problems.append(f"audit {name} did not pass with exact-commit evidence")
        else:
            bound_file(result.get("evidence"), base, f"audit {name} evidence", problems)

    findings = evidence.get("findings", [])
    if not isinstance(findings, list):
        problems.append("findings is not a list")
    else:
        for index, finding in enumerate(findings):
            if not isinstance(finding, dict):
                problems.append(f"finding {index} is not an object"); continue
            severity, status = finding.get("severity"), finding.get("status")
            if severity not in {"BLOCKER", "HIGH", "MEDIUM", "LOW"} or status not in {"OPEN", "RESOLVED", "ACCEPTED"}:
                problems.append(f"finding {index} has invalid severity or status")
            if severity in {"BLOCKER", "HIGH"} and status != "RESOLVED":
                problems.append(f"unresolved {severity} finding blocks GA")
            for field in ("owner", "impact", "disposition"):
                if not isinstance(finding.get(field), str) or not finding[field].strip():
                    problems.append(f"finding {index} has no {field}")

    report = evidence.get("report", {})
    if not isinstance(report, dict):
        problems.append("report is not an object"); report = {}
    report_path = resolve(base, report.get("path"))
    if report_path is None or not report_path.is_file():
        problems.append("signed final report path is missing")
    elif digest(report_path) != report.get("sha256"):
        problems.append("signed final report SHA-256 does not match")
    identity = report.get("certificateIdentity")
    issuer = report.get("certificateOidcIssuer")
    signing = policy["reportSigning"]
    if identity != signing["certificateIdentity"] or issuer != signing["certificateOidcIssuer"]:
        problems.append("report signer is not the canonical protected release workflow")
    bundle_record = {"path": report.get("sigstoreBundlePath"), "sha256": report.get("sigstoreBundleSha256")}
    verified_bundle = bound_file(bundle_record, base, "report Sigstore bundle", problems)
    if identity == signing["certificateIdentity"] and issuer == signing["certificateOidcIssuer"] \
            and verify_external and report_path is not None and report_path.is_file():
        cosign = shutil.which("cosign")
        if cosign is None or verified_bundle is None:
            problems.append("signed final report verification requires cosign and a Sigstore bundle")
        else:
            verify_command([cosign, "verify-blob", "--bundle", str(verified_bundle),
                            "--certificate-identity", identity, "--certificate-oidc-issuer", issuer,
                            str(report_path)], root, "signed final report", problems)

    verdict = evidence.get("verdict")
    if verdict not in policy["allowedVerdicts"] or verdict == "FAIL":
        problems.append("GA evidence has no passing explicit verdict")
    verdict_at = iso(evidence.get("verdictAt"), "verdictAt", problems)
    if verdict_at is not None and verdict_at > observed:
        problems.append("verdictAt is in the future")
    if report_path is not None and report_path.is_file():
        try:
            signed_report = load(report_path)
        except (OSError, ValueError) as error:
            problems.append(f"signed final report is not valid JSON: {error}")
        else:
            expected_report = {
                "commit": commit, "verdict": verdict,
                "manifestSha256": artifacts.get("manifestSha256"),
                "checksumsSha256": artifacts.get("checksumsSha256"),
            }
            if any(signed_report.get(field) != value for field, value in expected_report.items()):
                problems.append("signed final report identity, artifact digests, or verdict differs from evidence")
    return problems


def main() -> int:
    parser = argparse.ArgumentParser()
    group = parser.add_mutually_exclusive_group(required=True)
    group.add_argument("--policy-only", action="store_true")
    group.add_argument("--evidence", type=Path)
    args = parser.parse_args()
    try:
        problems = policy_problems(ROOT) if args.policy_only else evidence_problems(
            load(args.evidence), ROOT, evidence_base=args.evidence.resolve().parent)
    except (OSError, ValueError) as error:
        problems = [f"invalid GA audit input: {error}"]
    if problems:
        print("ga-audit=PENDING" if args.policy_only else "ga-audit=FAIL")
        for problem in problems:
            print(f"- {problem}")
        return 1
    print("ga-audit=READY" if args.policy_only else "ga-audit=PASS")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
