#!/usr/bin/env python3
"""Fail-closed verification for one atomic public GA artifact family."""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import shutil
import subprocess
import sys
import tarfile
import zipfile
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Iterable

ROOT = Path(os.environ.get("AGENTWORKS_REPOSITORY_ROOT", Path(__file__).resolve().parents[2]))
POLICY = Path("config/release/ga-publication.json")
TEMPLATE = Path("config/release/ga-publication-evidence-template.json")
COMMIT = re.compile(r"[0-9a-f]{40}")
SHA256 = re.compile(r"[0-9a-f]{64}")
PUBLIC_REPOSITORY = "https://github.com/dvarahq/dvara-agentworks"
PUBLIC_GITHUB_REPOSITORY = "dvarahq/dvara-agentworks"
ALLOWED_ACTOR = "grabdoc"
REQUIRED_FAMILIES = (
    "maven-bom", "maven-modules", "oci", "helm", "cli", "source", "checksums", "sbom", "provenance",
)
PUBLIC_DESTINATIONS = {
    "maven-bom": "https://repo1.maven.org/maven2/com/dvarahq/agentworks/",
    "maven-modules": "https://repo1.maven.org/maven2/com/dvarahq/agentworks/",
    "oci": "ghcr.io/dvarahq/dvara-agentworks",
    "helm": "oci://ghcr.io/dvarahq/charts/agentworks",
    "cli": f"{PUBLIC_REPOSITORY}/releases/tag/v1.0.0",
    "source": f"{PUBLIC_REPOSITORY}/releases/tag/v1.0.0",
    "checksums": f"{PUBLIC_REPOSITORY}/releases/tag/v1.0.0",
    "sbom": f"{PUBLIC_REPOSITORY}/releases/tag/v1.0.0",
    "provenance": f"{PUBLIC_REPOSITORY}/releases/tag/v1.0.0",
}
REQUIRED_GATES = (
    "maven-consumer", "oci-multiarch", "helm-install", "cli-smoke", "source-build",
    "tamper-rejection", "staging-failure", "atomic-promotion", "compromise-response",
)
PRIVATE_MARKERS = (
    "dvara-agentworks-" + "ee",
    "dvara-agent-os-" + "enterprise",
    "com.dvarahq.agentworks." + "enterprise",
    "Dvara AgentWorks " + "Enterprise",
)


def reject_json_constant(value: str) -> None:
    raise ValueError(f"non-standard JSON constant {value}")


def load(path: Path) -> Any:
    return json.loads(path.read_text(encoding="utf-8"), parse_constant=reject_json_constant)


def digest(path: Path) -> str:
    value = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            value.update(block)
    return value.hexdigest()


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


def strings(value: Any) -> Iterable[str]:
    if isinstance(value, str):
        yield value
    elif isinstance(value, dict):
        for key, item in value.items():
            yield str(key)
            yield from strings(item)
    elif isinstance(value, list):
        for item in value:
            yield from strings(item)


def marker_problem(value: str) -> str | None:
    lowered = value.lower()
    for marker in PRIVATE_MARKERS:
        if marker.lower() in lowered:
            return marker
    return None


def scan_bytes(data: bytes, label: str, problems: list[str]) -> None:
    if len(data) > 8 * 1024 * 1024 or b"\0" in data:
        return
    text = data.decode("utf-8", errors="ignore")
    if marker := marker_problem(text):
        problems.append(f"private marker {marker!r} appears in public asset {label}")


def scan_public_asset(path: Path, label: str, problems: list[str]) -> None:
    if marker := marker_problem(path.name):
        problems.append(f"private marker {marker!r} appears in public asset name {label}")
    try:
        if zipfile.is_zipfile(path):
            with zipfile.ZipFile(path) as archive:
                members = archive.infolist()
                if len(members) > 10000 or sum(info.file_size for info in members) > 64 * 1024 * 1024:
                    problems.append(f"public archive exceeds scan bounds: {label}")
                    return
                for info in members:
                    if marker := marker_problem(info.filename):
                        problems.append(f"private marker {marker!r} appears in archive member {label}:{info.filename}")
                    if not info.is_dir() and info.file_size <= 8 * 1024 * 1024:
                        scan_bytes(archive.read(info), f"{label}:{info.filename}", problems)
        elif tarfile.is_tarfile(path):
            with tarfile.open(path, "r:*") as archive:
                members = archive.getmembers()
                if len(members) > 10000 or sum(member.size for member in members) > 64 * 1024 * 1024:
                    problems.append(f"public archive exceeds scan bounds: {label}")
                    return
                for member in members:
                    if marker := marker_problem(member.name):
                        problems.append(f"private marker {marker!r} appears in archive member {label}:{member.name}")
                    if member.isfile() and member.size <= 8 * 1024 * 1024:
                        stream = archive.extractfile(member)
                        if stream is not None:
                            scan_bytes(stream.read(), f"{label}:{member.name}", problems)
        else:
            scan_bytes(path.read_bytes(), label, problems)
    except (OSError, tarfile.TarError, zipfile.BadZipFile) as error:
        problems.append(f"cannot scan public asset {label}: {error}")


def policy_problems(root: Path = ROOT) -> list[str]:
    try:
        policy = load(root / POLICY)
        template = load(root / TEMPLATE)
    except (OSError, ValueError) as error:
        return [f"invalid GA publication policy/template: {error}"]
    if not isinstance(policy, dict) or not isinstance(template, dict):
        return ["GA publication policy and template must be JSON objects"]
    problems: list[str] = []
    expected_release = {"version": "1.0.0", "tag": "v1.0.0", "repository": PUBLIC_REPOSITORY}
    if policy.get("schemaVersion") != 1 or template.get("schemaVersion") != 1:
        problems.append("GA publication policy and template must use schema version 1")
    if policy.get("release") != expected_release:
        problems.append("GA publication release identity is not canonical")
    if policy.get("allowedGitHubActors") != [ALLOWED_ACTOR]:
        problems.append("GitHub publication authority must contain only grabdoc")
    if policy.get("requiredPrerequisiteIssues") != [35, 7]:
        problems.append("GA publication prerequisites must be public RC5 issue #35 then audit issue #7")
    families = policy.get("requiredArtifactFamilies", [])
    destinations = policy.get("destinations", {})
    if families != list(REQUIRED_FAMILIES):
        problems.append("GA publication artifact families differ from the frozen nine-family contract")
    if destinations != PUBLIC_DESTINATIONS:
        problems.append("GA publication destinations differ from the frozen public destinations")
    gates = policy.get("requiredVerificationGates", [])
    if gates != list(REQUIRED_GATES):
        problems.append("GA publication verification gates differ from the frozen nine-gate contract")
    if policy.get("allowedVerdicts") != ["PASS", "FAIL"]:
        problems.append("GA publication verdicts must be exactly PASS or FAIL")
    signing = policy.get("indexSigning", {})
    if signing != {
        "certificateIdentity": f"{PUBLIC_REPOSITORY}/.github/workflows/release.yml@refs/tags/v1.0.0",
        "certificateOidcIssuer": "https://token.actions.githubusercontent.com",
    }:
        problems.append("artifact index signer is not the canonical protected GA workflow")
    expected_template_release = expected_release | {"commit": None, "dirty": None}
    if template.get("release") != expected_template_release or template.get("verdict") != "PENDING":
        problems.append("GA publication template must be PENDING for the canonical GA identity")
    if template.get("github") != {"actor": None, "repository": PUBLIC_GITHUB_REPOSITORY}:
        problems.append("GA publication template GitHub identity differs from policy")
    prerequisites = template.get("prerequisites", [])
    if not isinstance(prerequisites, list) or len(prerequisites) != 2 \
            or not all(isinstance(item, dict) for item in prerequisites) \
            or [item.get("issue") for item in prerequisites] != [35, 7]:
        problems.append("GA publication template prerequisite inventory differs from policy")
    if template.get("artifacts") != [] or template.get("gates") != {}:
        problems.append("GA publication template cannot pre-attest artifacts or gates")
    for value in strings(policy):
        if marker := marker_problem(value):
            problems.append(f"private marker {marker!r} appears in GA publication policy")
    return problems


def verify_command(command: list[str], root: Path, label: str, problems: list[str], timeout: int = 300) -> None:
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
    release = release if isinstance(release, dict) else {}
    commit = release.get("commit")
    if evidence.get("schemaVersion") != 1:
        problems.append("evidence schemaVersion is not 1")
    if any(release.get(field) != policy["release"][field] for field in ("version", "tag", "repository")):
        problems.append("evidence release identity differs from GA publication policy")
    if not isinstance(commit, str) or not COMMIT.fullmatch(commit) or release.get("dirty") is not False:
        problems.append("GA publication commit is not exact and clean")
    github = evidence.get("github", {})
    if github != {"actor": ALLOWED_ACTOR, "repository": PUBLIC_GITHUB_REPOSITORY}:
        problems.append("GitHub publication actor/repository is not the sole allowed identity")

    prerequisites = evidence.get("prerequisites", [])
    if not isinstance(prerequisites, list) or len(prerequisites) != 2 \
            or not all(isinstance(item, dict) for item in prerequisites) \
            or [item.get("issue") for item in prerequisites] != [35, 7]:
        problems.append("GA publication prerequisite evidence differs from public issues #35 and #7")
    else:
        for item in prerequisites:
            number = item.get("issue")
            path = resolve(base, item.get("evidence"))
            if item.get("status") != "CLOSED" or item.get("commit") != commit:
                problems.append(f"issue #{number} is not closed for the exact GA commit")
            if path is None or not path.is_file():
                problems.append(f"issue #{number} evidence path is missing")
            elif not isinstance(item.get("evidenceSha256"), str) \
                    or not SHA256.fullmatch(item["evidenceSha256"]) \
                    or digest(path) != item["evidenceSha256"]:
                problems.append(f"issue #{number} evidence SHA-256 does not match")
            else:
                try:
                    prerequisite = load(path)
                except (OSError, ValueError) as error:
                    problems.append(f"cannot inspect issue #{number} evidence: {error}")
                    continue
                if not isinstance(prerequisite, dict):
                    problems.append(f"issue #{number} evidence is not an object")
                    continue
                nested_release = prerequisite.get("release", {})
                nested_release = nested_release if isinstance(nested_release, dict) else {}
                prerequisite_commit = prerequisite.get("commit", nested_release.get("commit"))
                accepted = {35: {"GO"}, 7: {"PASS", "PASS_WITH_NON_BLOCKING_FOLLOW_UPS"}}
                if prerequisite_commit != commit or prerequisite.get("verdict") not in accepted[number]:
                    problems.append(f"issue #{number} evidence does not authorize the exact GA commit")

    candidate = evidence.get("candidate", {})
    candidate = candidate if isinstance(candidate, dict) else {}
    candidate_path = resolve(base, candidate.get("path"))
    if candidate_path is None or not candidate_path.is_dir():
        problems.append("GA candidate directory is missing")
    else:
        for name, field in (("MANIFEST.json", "manifestSha256"), ("SHA256SUMS", "checksumsSha256")):
            path = candidate_path / name
            if not path.is_file() or candidate.get(field) != digest(path):
                problems.append(f"candidate {name} digest does not match")
        try:
            source = load(candidate_path / "SOURCE.json")
            manifest = load(candidate_path / "MANIFEST.json")
        except (OSError, ValueError) as error:
            problems.append(f"GA candidate identity is invalid: {error}")
        else:
            if not isinstance(source, dict) or not isinstance(manifest, dict):
                problems.append("GA candidate identity files are not objects")
                source = {}
                manifest = {}
            expected_source = {"repository": PUBLIC_REPOSITORY, "tag": "v1.0.0", "commit": commit,
                               "releaseQualified": True, "githubActor": ALLOWED_ACTOR}
            if any(source.get(key) != value for key, value in expected_source.items()):
                problems.append("GA candidate does not name the exact release-qualified public commit")
            if manifest.get("source") != source:
                problems.append("GA candidate manifest source differs from SOURCE.json")
        if verify_external:
            verify_command([sys.executable, str(root / "scripts/release/verify_release_candidate.py"),
                            str(candidate_path), "--phase", "release", "--expected-commit", str(commit)],
                           root, "GA release candidate", problems)

    inventory_path = bound_file(evidence.get("inventory"), base, "publication inventory", problems)
    inventory: dict[str, Any] = {}
    if inventory_path is not None:
        try:
            inventory = load(inventory_path)
        except (OSError, ValueError) as error:
            problems.append(f"publication inventory is invalid: {error}")
        else:
            if not isinstance(inventory, dict):
                problems.append("publication inventory is not an object")
                inventory = {}
            if inventory.get("release") != {"version": "1.0.0", "tag": "v1.0.0", "commit": commit}:
                problems.append("publication inventory names a different release")
            if inventory.get("githubActor") != ALLOWED_ACTOR:
                problems.append("publication inventory GitHub actor is not grabdoc")
            for value in strings(inventory):
                if marker := marker_problem(value):
                    problems.append(f"private marker {marker!r} appears in publication inventory")

    artifacts = evidence.get("artifacts", [])
    families: set[str] = set()
    identities: set[tuple[str, str]] = set()
    inventory_assets = inventory.get("artifacts", []) if isinstance(inventory, dict) else []
    if not isinstance(artifacts, list) or not artifacts:
        problems.append("published artifact list is empty")
        artifacts = []
    if artifacts != inventory_assets:
        problems.append("evidence artifact list differs from the digest-bound inventory")
    for index, artifact in enumerate(artifacts):
        if not isinstance(artifact, dict):
            problems.append(f"artifact {index} is not an object")
            continue
        family = artifact.get("family")
        if not isinstance(family, str):
            problems.append(f"artifact {index} family is not a string")
            continue
        families.add(family)
        identity = (str(artifact.get("destination")), str(artifact.get("name")))
        if identity in identities:
            problems.append(f"artifact {index} duplicates a published destination/name")
        identities.add(identity)
        path = resolve(base, artifact.get("path"))
        expected = artifact.get("sha256")
        if family not in policy["requiredArtifactFamilies"]:
            problems.append(f"artifact {index} has unsupported family {family}")
        elif artifact.get("destination") != policy["destinations"][family]:
            problems.append(f"artifact {index} destination differs from policy")
        if artifact.get("status") != "PUBLISHED" or artifact.get("commit") != commit:
            problems.append(f"artifact {index} is not published from the exact GA commit")
        if path is None or not path.is_file():
            problems.append(f"artifact {index} local evidence path is missing")
        elif not isinstance(expected, str) or not SHA256.fullmatch(expected) or digest(path) != expected:
            problems.append(f"artifact {index} SHA-256 does not match")
        else:
            scan_public_asset(path, f"artifact {index}", problems)
        for value in strings(artifact):
            if marker := marker_problem(value):
                problems.append(f"private marker {marker!r} appears in artifact {index} metadata")
        if "SNAPSHOT" in str(artifact.get("name", "")).upper():
            problems.append(f"artifact {index} uses a snapshot identity")
    if families != set(policy["requiredArtifactFamilies"]):
        problems.append("published artifacts do not cover exactly all required families")

    gates = evidence.get("gates", {})
    if not isinstance(gates, dict) or set(gates) != set(policy["requiredVerificationGates"]):
        problems.append("publication evidence does not cover exactly all verification gates")
        gates = gates if isinstance(gates, dict) else {}
    for name in policy["requiredVerificationGates"]:
        result = gates.get(name, {})
        if not isinstance(result, dict) or result.get("status") != "PASS" or result.get("commit") != commit:
            problems.append(f"publication gate {name} did not pass for the exact GA commit")
        else:
            bound_file(result.get("evidence"), base, f"publication gate {name} evidence", problems)

    promotion = evidence.get("promotion", {})
    promotion = promotion if isinstance(promotion, dict) else {}
    published_at = iso(promotion.get("publishedAt"), "promotion.publishedAt", problems)
    advertised_at = iso(promotion.get("advertisedAt"), "promotion.advertisedAt", problems)
    if promotion.get("status") != "COMPLETE":
        problems.append("artifact-family promotion is not complete")
    if published_at is not None and advertised_at is not None and advertised_at < published_at:
        problems.append("artifact family was advertised before publication completed")
    if advertised_at is not None and advertised_at > observed:
        problems.append("promotion advertisement time is in the future")

    index = evidence.get("index", {})
    index = index if isinstance(index, dict) else {}
    index_path = bound_file(index, base, "public artifact index", problems)
    bundle = bound_file({"path": index.get("sigstoreBundlePath"),
                         "sha256": index.get("sigstoreBundleSha256")},
                        base, "artifact index Sigstore bundle", problems)
    signing = policy["indexSigning"]
    if index.get("certificateIdentity") != signing["certificateIdentity"] \
            or index.get("certificateOidcIssuer") != signing["certificateOidcIssuer"]:
        problems.append("artifact index signer is not the canonical protected GA workflow")
    if index_path is not None:
        try:
            index_data = load(index_path)
        except (OSError, ValueError) as error:
            problems.append(f"public artifact index is invalid: {error}")
        else:
            if not isinstance(index_data, dict):
                problems.append("public artifact index is not an object")
                index_data = {}
            expected_index = {"version": "1.0.0", "tag": "v1.0.0", "commit": commit,
                              "githubActor": ALLOWED_ACTOR, "artifacts": artifacts}
            if any(index_data.get(key) != value for key, value in expected_index.items()):
                problems.append("public artifact index differs from exact GA evidence")
            for value in strings(index_data):
                if marker := marker_problem(value):
                    problems.append(f"private marker {marker!r} appears in public artifact index")
    if verify_external and index_path is not None and bundle is not None:
        cosign = shutil.which("cosign")
        if cosign is None:
            problems.append("artifact index signature verification requires cosign")
        else:
            verify_command([cosign, "verify-blob", "--bundle", str(bundle),
                            "--certificate-identity", signing["certificateIdentity"],
                            "--certificate-oidc-issuer", signing["certificateOidcIssuer"], str(index_path)],
                           root, "public artifact index signature", problems)

    verdict = evidence.get("verdict")
    if verdict != "PASS":
        problems.append("GA publication evidence has no explicit PASS verdict")
    verdict_at = iso(evidence.get("verdictAt"), "verdictAt", problems)
    if verdict_at is not None and verdict_at > observed:
        problems.append("verdictAt is in the future")
    return problems


def main() -> int:
    parser = argparse.ArgumentParser()
    group = parser.add_mutually_exclusive_group(required=True)
    group.add_argument("--policy-only", action="store_true")
    group.add_argument("--evidence", type=Path)
    parser.add_argument("--no-external", action="store_true", help=argparse.SUPPRESS)
    args = parser.parse_args()
    try:
        problems = policy_problems(ROOT) if args.policy_only else evidence_problems(
            load(args.evidence), ROOT, evidence_base=args.evidence.resolve().parent,
            verify_external=not args.no_external)
    except (OSError, ValueError) as error:
        problems = [f"invalid GA publication input: {error}"]
    if problems:
        print("ga-publication=PENDING" if args.policy_only else "ga-publication=FAIL")
        for problem in problems:
            print(f"- {problem}")
        return 1
    print("ga-publication=READY" if args.policy_only else "ga-publication=PASS")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
