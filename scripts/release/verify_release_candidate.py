#!/usr/bin/env python3
"""Verify a release candidate without trusting its manifest."""

from __future__ import annotations

import argparse
import hashlib
import json
import re
import shutil
import stat
import subprocess
from pathlib import Path

KINDS = {"maven", "cli", "oci", "helm"}
CANONICAL_SOURCE = "https://github.com/dvarahq/dvara-agentworks"


def digest(path: Path) -> str:
    value = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            value.update(block)
    return value.hexdigest()


def safe(root: Path, relative: str) -> Path:
    path = (root / relative).resolve()
    if root.resolve() not in path.parents:
        raise ValueError(f"path escapes candidate: {relative}")
    return path


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("candidate", type=Path)
    parser.add_argument("--phase", choices=("build", "release"), default="build")
    parser.add_argument("--expected-commit")
    parser.add_argument("--allow-qualification", action="store_true")
    parser.add_argument("--public-key", type=Path,
                        help="local qualification only; production verification always uses the workflow OIDC identity")
    args = parser.parse_args()
    root = args.candidate.resolve()
    problems: list[str] = []
    try:
        manifest = json.loads((root / "MANIFEST.json").read_text(encoding="utf-8"))
        source = json.loads((root / "SOURCE.json").read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as error:
        raise SystemExit(f"release-candidate=FAIL: invalid manifest/source: {error}") from error
    if source.get("repository") != CANONICAL_SOURCE or not re.fullmatch(r"[0-9a-f]{40}", source.get("commit", "")):
        problems.append("source identity is not canonical and immutable")
    if not isinstance(source.get("sourceDateEpoch"), int) or source["sourceDateEpoch"] < 315532802:
        problems.append("source epoch is outside the supported reproducible archive range")
    if manifest.get("source") != source:
        problems.append("manifest source record differs from SOURCE.json")
    if args.expected_commit and source.get("commit") != args.expected_commit:
        problems.append("candidate commit differs from expected commit")
    if not source.get("releaseQualified") and not args.allow_qualification:
        problems.append("qualification bundle cannot be released")
    if source.get("releaseQualified") is True and source.get("githubActor") != "grabdoc":
        problems.append("release-qualified candidate requires GitHub actor grabdoc")
    if source.get("releaseQualified") is False and "githubActor" in source:
        problems.append("qualification candidate cannot claim a GitHub actor")
    actual_files = {path.relative_to(root).as_posix() for path in root.rglob("*") if path.is_file()}
    checksums: dict[str, str] = {}
    for line in (root / "SHA256SUMS").read_text(encoding="utf-8").splitlines():
        match = re.fullmatch(r"([0-9a-f]{64})  ([^\n]+)", line)
        if not match or match.group(2) in checksums:
            problems.append("checksum manifest is malformed or duplicated")
            continue
        checksums[match.group(2)] = match.group(1)
    expected_covered = actual_files - {"SHA256SUMS", "SHA256SUMS.sigstore.json"}
    if set(checksums) != expected_covered:
        problems.append("checksum manifest does not cover exactly every unsigned candidate file")
    for relative, expected in checksums.items():
        try:
            if digest(safe(root, relative)) != expected:
                problems.append(f"checksum mismatch: {relative}")
        except (OSError, ValueError) as error:
            problems.append(str(error))
    kinds: set[str] = set()
    recorded_artifacts: set[str] = set()
    cli_artifacts = 0
    for artifact in manifest.get("artifacts", []):
        kinds.add(artifact.get("kind"))
        recorded_artifacts.add(artifact.get("path", ""))
        try:
            path = safe(root, artifact["path"])
            sha256 = digest(path)
            if sha256 != artifact.get("sha256"):
                problems.append(f"manifest digest mismatch: {artifact.get('path')}")
            if artifact.get("kind") == "cli":
                cli_artifacts += 1
                if artifact.get("mode") != "0755":
                    problems.append("CLI artifact manifest must require mode 0755")
                if stat.S_IMODE(path.stat().st_mode) != 0o755:
                    problems.append("packaged CLI mode differs from signed 0755 contract")
            sbom = json.loads(safe(root, artifact["sbom"]).read_text(encoding="utf-8"))
            provenance = json.loads(safe(root, artifact["provenance"]).read_text(encoding="utf-8"))
            if sbom["packages"][0]["checksums"][0]["checksumValue"] != sha256:
                problems.append(f"SBOM digest mismatch: {artifact.get('path')}")
            subject = provenance["subject"][0]
            if subject["name"] != artifact["path"] or subject["digest"]["sha256"] != sha256:
                problems.append(f"provenance subject mismatch: {artifact.get('path')}")
            resolved = provenance["predicate"]["buildDefinition"]["resolvedDependencies"][0]
            if resolved["digest"]["gitCommit"] != source["commit"]:
                problems.append(f"provenance commit mismatch: {artifact.get('path')}")
        except (KeyError, IndexError, OSError, ValueError, json.JSONDecodeError) as error:
            problems.append(f"invalid artifact evidence: {error}")
    if kinds != KINDS:
        problems.append(f"artifact kinds differ: {sorted(kinds)}")
    if cli_artifacts != 1:
        problems.append(f"release candidate must contain exactly one CLI artifact, found {cli_artifacts}")
    actual_artifacts = {path.relative_to(root).as_posix() for path in (root / "artifacts").rglob("*") if path.is_file()}
    if recorded_artifacts != actual_artifacts:
        problems.append("manifest does not record exactly every artifact")
    for name, expected in manifest.get("legal", {}).items():
        try:
            if digest(safe(root, f"legal/{name}")) != expected:
                problems.append(f"legal digest mismatch: {name}")
        except (OSError, ValueError) as error:
            problems.append(str(error))
    if args.phase == "release":
        bundle = root / "SHA256SUMS.sigstore.json"
        cosign = shutil.which("cosign")
        if not bundle.is_file() or cosign is None:
            problems.append("release verification requires cosign and SHA256SUMS.sigstore.json")
        else:
            verification = [cosign, "verify-blob", "--bundle", str(bundle)]
            if args.public_key:
                if not args.allow_qualification:
                    problems.append("a local public key is permitted only for an explicit qualification")
                verification += ["--key", str(args.public_key), "--insecure-ignore-tlog"]
            else:
                verification += [
                    "--certificate-oidc-issuer", "https://token.actions.githubusercontent.com",
                    "--certificate-identity-regexp",
                    r"^https://github.com/dvarahq/dvara-agentworks/.github/workflows/release.yml@refs/tags/v",
                ]
            verification.append(str(root / "SHA256SUMS"))
            result = subprocess.run(verification, capture_output=True, text=True, check=False)
            if result.returncode:
                problems.append("Sigstore verification failed: " + (result.stderr or result.stdout).strip())
    if problems:
        raise SystemExit("release-candidate=FAIL: " + "; ".join(problems))
    print(f"release-candidate=PASS artifacts={len(manifest['artifacts'])} commit={source['commit']} phase={args.phase}")


if __name__ == "__main__":
    main()
