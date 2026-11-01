#!/usr/bin/env python3
"""Fail-closed OSS release supply-chain policy and evidence verifier (issue #65)."""

from __future__ import annotations

import argparse
import datetime as dt
import hashlib
import json
import os
import re
import subprocess
import sys
from pathlib import Path

ROOT = Path(os.environ.get("AGENTWORKS_REPOSITORY_ROOT", Path(__file__).resolve().parents[2]))
POLICY_PATH = ROOT / "config/supply-chain/policy.json"
EXCEPTION_PATH = ROOT / "config/supply-chain/vulnerability-exceptions.json"

TEXT_SUFFIXES = {
    "", ".bash", ".conf", ".css", ".env", ".html", ".java", ".js", ".json", ".md", ".properties",
    ".py", ".sh", ".sql", ".toml", ".txt", ".xml", ".yaml", ".yml",
}
SECRET_PATTERNS = {
    "private key": re.compile(r"-----BEGIN (?:[A-Z ]+ )?PRIVATE KEY-----"),
    "AWS access key": re.compile(r"\b(?:AKIA|ASIA)[A-Z0-9]{16}\b"),
    "GitHub token": re.compile(r"\b(?:gh[oprsu]_[A-Za-z0-9]{30,}|github_pat_[A-Za-z0-9_]{40,})\b"),
    "Slack token": re.compile(r"\bxox[baprs]-[A-Za-z0-9-]{20,}\b"),
    "literal credential": re.compile(
        r"(?i)\b(?:api[_-]?key|client[_-]?secret|access[_-]?token|password)\b\s*[:=]\s*['\"]"
        r"(?!change-me|example|redacted|test-only)[^'\"\s]{16,}['\"]"
    ),
}


def load_json(path: Path) -> dict:
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as error:
        raise ValueError(f"invalid JSON {path.relative_to(ROOT)}: {error}") from error


def tracked_files() -> list[Path]:
    result = subprocess.run(
        ["git", "ls-files", "--cached", "--others", "--exclude-standard", "-z"],
        cwd=ROOT, capture_output=True, check=False,
    )
    if result.returncode == 0:
        return [ROOT / item.decode() for item in result.stdout.split(b"\0") if item]
    return [path for path in ROOT.rglob("*") if path.is_file() and not any(
        part in {".git", "target", "node_modules", "dist"} for part in path.parts)]


def secret_problems(policy: dict) -> list[str]:
    problems: list[str] = []
    today = dt.datetime.now(dt.timezone.utc).date()
    exceptions = {(item.get("path"), item.get("matchSha256")): item
                  for item in policy.get("secretExceptions", [])}
    used: set[tuple[str, str]] = set()
    for path in tracked_files():
        relative = path.relative_to(ROOT).as_posix()
        lowered = path.name.lower()
        if lowered.endswith((".pem", ".key", ".p12", ".pfx")) or "secret" in lowered:
            # Policy/configuration and negative-test names may describe secrets; only key containers are always banned.
            if lowered.endswith((".pem", ".key", ".p12", ".pfx")):
                problems.append(f"secret key container is tracked: {relative}")
        if not path.is_file() or path.suffix.lower() not in TEXT_SUFFIXES or path.stat().st_size > 2_000_000:
            continue
        try:
            text = path.read_text(encoding="utf-8")
        except UnicodeDecodeError:
            continue
        for label, pattern in SECRET_PATTERNS.items():
            match = pattern.search(text)
            if match:
                match_hash = hashlib.sha256(match.group(0).encode()).hexdigest()
                key = (relative, match_hash)
                exception = exceptions.get(key)
                if exception:
                    try:
                        expires = dt.date.fromisoformat(exception.get("expires", ""))
                    except ValueError:
                        problems.append(f"secret exception for {relative} has invalid expiry")
                        continue
                    if expires < today or expires > today + dt.timedelta(days=90):
                        problems.append(f"secret exception for {relative} is expired or exceeds 90 days")
                        continue
                    if not exception.get("reason") or not exception.get("reviewedBy"):
                        problems.append(f"secret exception for {relative} lacks reason or reviewer")
                        continue
                    used.add(key)
                    continue
                line = text.count("\n", 0, match.start()) + 1
                problems.append(f"{label} material in {relative}:{line}")
    for key in exceptions.keys() - used:
        problems.append(f"unused secret exception: {key[0]} {key[1]}")
    return problems


def exception_problems(policy: dict, today: dt.date | None = None) -> list[str]:
    today = today or dt.datetime.now(dt.timezone.utc).date()
    data = load_json(EXCEPTION_PATH)
    problems: list[str] = []
    if data.get("version") != 1 or not isinstance(data.get("exceptions"), list):
        return ["vulnerability exception catalog must be version 1 with an exceptions array"]
    seen: set[tuple[str, str]] = set()
    for index, entry in enumerate(data["exceptions"]):
        prefix = f"vulnerability exception {index}"
        identifier = entry.get("id", "")
        package = entry.get("package", "")
        if not re.fullmatch(r"(?:CVE-\d{4}-\d{4,}|GHSA-[23456789cfghjmpqrvwx]{4}-[23456789cfghjmpqrvwx]{4}-[23456789cfghjmpqrvwx]{4})", identifier):
            problems.append(f"{prefix} has invalid id")
        identity = (identifier, package)
        if identity in seen:
            problems.append(f"{prefix} duplicates {identifier} for {package}")
        seen.add(identity)
        try:
            expires = dt.date.fromisoformat(entry.get("expires", ""))
        except ValueError:
            problems.append(f"{prefix} has invalid expiry")
            continue
        if expires < today:
            problems.append(f"{prefix} expired on {expires.isoformat()}")
        if expires > today + dt.timedelta(days=90):
            problems.append(f"{prefix} expiry exceeds 90 days")
        for field in ("package", "affectedVersion", "exploitability", "reason", "owner", "reviewedBy", "reviewUrl"):
            if not isinstance(entry.get(field), str) or not entry[field].strip():
                problems.append(f"{prefix} lacks {field}")
        if entry.get("owner") == entry.get("reviewedBy"):
            problems.append(f"{prefix} must have an independent reviewer")
        if entry.get("exploitability") not in {"not-exploitable", "mitigated"}:
            problems.append(f"{prefix} must state not-exploitable or mitigated")
        if not str(entry.get("reviewUrl", "")).startswith("https://github.com/dvarahq/"):
            problems.append(f"{prefix} reviewUrl must be DvaraHQ GitHub evidence")
    expected = policy["vulnerabilities"]["exceptionFile"]
    if (ROOT / expected).resolve() != EXCEPTION_PATH.resolve():
        problems.append("policy vulnerability exception file does not match verifier authority")
    return problems


def action_pinning_problems() -> list[str]:
    problems: list[str] = []
    for workflow in sorted((ROOT / ".github/workflows").glob("*.yml")):
        text = workflow.read_text(encoding="utf-8")
        for action in re.findall(r"uses:\s*([^\s#]+)", text):
            if action.startswith("./"):
                continue
            if not re.fullmatch(r"[^@]+@[0-9a-f]{40}", action):
                problems.append(f"{workflow.name} action is not commit-pinned: {action}")
    return problems


def workflow_problems(policy: dict) -> list[str]:
    path = ROOT / ".github/workflows/release.yml"
    if not path.is_file():
        return ["missing protected release workflow"]
    text = path.read_text(encoding="utf-8")
    problems: list[str] = []
    if re.search(r"^\s*pull_request(?:_target)?:", text, re.MULTILINE):
        problems.append("release workflow must never run for pull requests")
    for required in (
        "workflow_dispatch:", "refs/tags/", f"environment: {policy['protectedEnvironment']}",
        "id-token: write", "attestations: write", "contents: write", "github.repository == 'dvarahq/dvara-agentworks'",
        "scripts/ci/verify_supply_chain.py", "scripts/release/build-release-candidate.sh", "trivy-action@",
        "cosign-installer@", "attest-build-provenance@",
        "github.ref == format('refs/tags/{0}', inputs.tag)",
        "chmod 0755 target/oss-release-candidate/artifacts/cli/agentworks",
        "Verify the release archive preserves the executable CLI",
        'test -x "$archive_check/oss-release-candidate/artifacts/cli/agentworks"',
        '"$archive_check/oss-release-candidate/artifacts/cli/agentworks" --version',
        "prerelease: ${{ contains(inputs.tag, '-') }}",
    ):
        if required not in text:
            problems.append(f"release workflow is missing {required}")
    build = re.search(r"(?ms)^  build:\n(.*?)(?=^  [a-zA-Z0-9_-]+:\n|\Z)", text)
    if not build:
        problems.append("release workflow lacks isolated build job")
    else:
        block = build.group(1)
        if "environment:" in block or "id-token: write" in block or "secrets." in block:
            problems.append("untrusted release build job can reach an environment, OIDC, or secret")
        if "contents: read" not in block:
            problems.append("release build job must declare contents: read")
    approve = re.search(r"(?ms)^  approve-and-publish:\n(.*?)(?=^  [a-zA-Z0-9_-]+:\n|\Z)", text)
    if not approve or "needs: build" not in approve.group(1):
        problems.append("protected publish job must consume the isolated build job")
    elif approve:
        block = approve.group(1)
        archive = re.search(r'-czf\s+"([^"]+\.tar\.gz)"', block)
        published = re.search(r'(?m)^\s+(target/agentworks-\$\{\{ inputs\.tag \}\}\.tar\.gz)\s*$', block)
        if not archive or not published or archive.group(1) != published.group(1):
            problems.append("release archive output does not match published asset path")
    return problems


def audit_report_workflow_problems(policy: dict) -> list[str]:
    path = ROOT / ".github/workflows/audit-report.yml"
    if not path.is_file():
        return ["missing protected GA audit-report signing workflow"]
    text = path.read_text(encoding="utf-8")
    problems: list[str] = []
    if re.search(r"^\s*pull_request(?:_target)?:", text, re.MULTILINE):
        problems.append("audit-report workflow must never run for pull requests")
    for required in (
        "workflow_dispatch:", "github.ref == format('refs/tags/{0}', inputs.tag)",
        f"environment: {policy['protectedEnvironment']}", "timeout-minutes: 5",
        "contents: write", "id-token: write", "cosign-installer@",
        "scripts/release/verify_release_candidate.py", "scripts/release/verify_ga_audit_report.py",
        "gh release download", "cmp ", "cosign sign-blob",
        "cosign verify-blob", "gh release upload", "ga-audit-report.sigstore.json",
    ):
        if required not in text:
            problems.append(f"audit-report workflow is missing {required}")
    for forbidden in ("build-release-candidate.sh", "./mvnw", "docker build", "--clobber"):
        if forbidden in text:
            problems.append(f"audit-report workflow must not contain {forbidden}")
    return problems


def license_problems(policy: dict, sbom: Path | None) -> list[str]:
    problems: list[str] = []
    for required in ("legal/oss/LICENSE", "legal/oss/NOTICE", "legal/oss/SOURCE-OFFER.md", "legal/oss/DCO"):
        if not (ROOT / required).is_file():
            problems.append(f"missing {required}")
    notice = (ROOT / "legal/oss/NOTICE").read_text(encoding="utf-8")
    for package in load_json(ROOT / "config/frontend-assets.json")["packages"]:
        if package["name"] not in notice:
            problems.append(f"NOTICE omits bundled frontend package {package['name']}")
    if sbom is None:
        return problems
    data = json.loads(sbom.read_text(encoding="utf-8"))
    allowed = set(policy["licenses"]["allowed"])
    aliases = policy["licenses"]["aliases"]
    private = tuple(policy["privateDependencyPrefixes"])
    for component in data.get("components", []):
        identity = component.get("purl") or f"{component.get('group', '')}:{component.get('name', '')}"
        if any(value in identity or value.replace(":", "/") in identity for value in private):
            problems.append(f"private dependency in SBOM: {identity}")
        licenses = []
        for item in component.get("licenses", []):
            value = item.get("license", {}).get("id") or item.get("license", {}).get("name")
            if value:
                licenses.append(aliases.get(value, value))
        if not licenses:
            problems.append(f"unknown license for {identity}")
        elif not any(value in allowed for value in licenses):
            problems.append(f"incompatible license for {identity}: {licenses}")
    return problems


def vulnerability_problems(policy: dict, report: Path | None) -> list[str]:
    if report is None:
        return []
    exceptions = {(entry["id"], entry["package"]): entry for entry in load_json(EXCEPTION_PATH)["exceptions"]}
    blocked = set(policy["vulnerabilities"]["blockingSeverities"])
    problems: list[str] = []
    data = load_json(report)
    for result in data.get("Results", []):
        for finding in result.get("Vulnerabilities") or []:
            identifier = finding.get("VulnerabilityID", "")
            package = finding.get("PkgName", "")
            exception = exceptions.get((identifier, package))
            excepted = exception is not None and exception.get("affectedVersion") == finding.get("InstalledVersion")
            if finding.get("Severity") in blocked and not excepted:
                problems.append(f"unexcepted {finding.get('Severity')} vulnerability {identifier} in {package or '<unknown>'}")
    return problems


def repository_problems(policy: dict) -> list[str]:
    problems: list[str] = []
    pom = (ROOT / "pom.xml").read_text(encoding="utf-8")
    if "<project.build.outputTimestamp>" not in pom:
        problems.append("Maven reproducible outputTimestamp is not configured")
    dockerfile = (ROOT / "Dockerfile").read_text(encoding="utf-8")
    if f"ARG SOURCE={policy['canonicalSource']}" not in dockerfile:
        problems.append("OCI source label does not default to the canonical public repository")
    dependency_check = (ROOT / "scripts/ci/run_dependency_check.sh").read_text(encoding="utf-8")
    if "-DfailBuildOnCVSS=7" not in dependency_check or "-DfailOnError=true" not in dependency_check:
        problems.append("dependency vulnerability scan is not fail-closed at CVSS 7")
    core_ci = (ROOT / ".github/workflows/core-ci.yml").read_text(encoding="utf-8")
    pr_verification = (ROOT / "scripts/ci/pr-verification.sh").read_text(encoding="utf-8")
    dco_is_wired = all(token in core_ci for token in ("scripts/ci/pr-verification.sh", "BASE_SHA", "HEAD_SHA"))
    dco_is_fail_closed = all(token in pr_verification for token in
                             ("scripts/ci/verify_dco.sh", '"$BASE_SHA"', '"$HEAD_SHA"'))
    if not dco_is_wired or not dco_is_fail_closed:
        problems.append("pull requests do not enforce DCO sign-off")
    return problems


def problems(sbom: Path | None = None, vulnerability_report: Path | None = None) -> list[str]:
    policy = load_json(POLICY_PATH)
    found: list[str] = []
    if policy.get("version") != 1 or policy.get("canonicalRepository") != "dvarahq/dvara-agentworks":
        found.append("supply-chain policy identity is invalid")
    found += exception_problems(policy)
    found += action_pinning_problems()
    found += workflow_problems(policy)
    found += audit_report_workflow_problems(policy)
    found += repository_problems(policy)
    found += secret_problems(policy)
    found += license_problems(policy, sbom)
    found += vulnerability_problems(policy, vulnerability_report)
    return found


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--sbom", type=Path)
    parser.add_argument("--vulnerability-report", type=Path)
    args = parser.parse_args()
    found = problems(args.sbom, args.vulnerability_report)
    if found:
        raise SystemExit("supply-chain=FAIL: " + "; ".join(found))
    digest = hashlib.sha256(POLICY_PATH.read_bytes()).hexdigest()
    print(f"supply-chain=PASS policy_sha256={digest} secrets=clean exceptions=valid")


if __name__ == "__main__":
    main()
