#!/usr/bin/env python3
"""Deliberate negative and reproducibility fixtures for the issue #65 gates."""

from __future__ import annotations

import io
import hashlib
import json
import os
import shutil
import subprocess
import tarfile
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
VERIFY = ROOT / "scripts/ci/verify_supply_chain.py"


def tracked_copy(destination: Path) -> Path:
    files = subprocess.check_output(
        ["git", "ls-files", "--cached", "--others", "--exclude-standard"], cwd=ROOT, text=True
    ).splitlines()
    for relative in files:
        source = ROOT / relative
        if source.is_file():
            target = destination / relative
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(source, target)
    return destination


def run_verify(root: Path, *arguments: str) -> subprocess.CompletedProcess[str]:
    environment = os.environ.copy()
    environment["AGENTWORKS_REPOSITORY_ROOT"] = str(root)
    return subprocess.run(["python3", str(VERIFY), *arguments], cwd=root, env=environment,
                          capture_output=True, text=True, check=False)


def reject(name: str, expected: str, mutate) -> None:
    with tempfile.TemporaryDirectory() as directory:
        root = tracked_copy(Path(directory))
        arguments = mutate(root) or []
        result = run_verify(root, *arguments)
        output = result.stdout + result.stderr
        if result.returncode == 0 or expected not in output:
            raise SystemExit(f"supply-chain-fixtures=FAIL: {name} was not rejected ({expected!r}): {output}")
    print(f"{name}=REJECTED")


def replace(root: Path, relative: str, old: str, new: str) -> None:
    path = root / relative
    text = path.read_text(encoding="utf-8")
    if old not in text:
        raise SystemExit(f"supply-chain-fixtures=FAIL: missing fixture anchor {old!r}")
    path.write_text(text.replace(old, new, 1), encoding="utf-8")


def secret(root: Path) -> list[str]:
    assignment = "api_" + "key=" + '"' + "fixture-credential-123456789" + '"\n'
    (root / "malicious.env").write_text(assignment, encoding="utf-8")
    return []


def expired(root: Path) -> list[str]:
    path = root / "config/supply-chain/vulnerability-exceptions.json"
    data = json.loads(path.read_text())
    data["exceptions"] = [{"id": "CVE-2026-12345", "package": "fixture", "affectedVersion": "1.0",
                           "exploitability": "mitigated", "expires": "2020-01-01", "reason": "fixture",
                           "owner": "owner", "reviewedBy": "reviewer",
                           "reviewUrl": "https://github.com/dvarahq/dvara-agentworks/issues/1"}]
    path.write_text(json.dumps(data), encoding="utf-8")
    return []


def sbom(root: Path, license_id: str = "GPL-3.0-only", identity: str = "pkg:maven/example/bad@1") -> list[str]:
    path = root / "fixture-sbom.json"
    path.write_text(json.dumps({"components": [{"purl": identity,
                                                 "licenses": [{"license": {"id": license_id}}]}]}), encoding="utf-8")
    return ["--sbom", str(path)]


def vulnerabilities(root: Path) -> list[str]:
    path = root / "trivy.json"
    path.write_text(json.dumps({"Results": [{"Vulnerabilities": [
        {"VulnerabilityID": "CVE-2026-99999", "PkgName": "fixture", "Severity": "HIGH"}
    ]}]}), encoding="utf-8")
    return ["--vulnerability-report", str(path)]


def wrong_version_exception(root: Path) -> list[str]:
    path = root / "trivy.json"
    path.write_text(json.dumps({"Results": [{"Vulnerabilities": [
        {"VulnerabilityID": "CVE-2026-78669", "PkgName": "stdlib", "InstalledVersion": "v1.26.7",
         "Severity": "HIGH"}
    ]}]}), encoding="utf-8")
    return ["--vulnerability-report", str(path)]


reject("secret_material", "literal credential material", secret)
reject("expired_exception", "expired on", expired)
reject("unpinned_action", "action is not commit-pinned",
       lambda root: replace(root, ".github/workflows/release.yml",
                            "actions/checkout@3d3c42e5aac5ba805825da76410c181273ba90b1", "actions/checkout@v7") or [])
reject("pr_release_credentials", "release workflow must never run for pull requests",
       lambda root: replace(root, ".github/workflows/release.yml", "  workflow_dispatch:", "  pull_request:\n  workflow_dispatch:") or [])
reject("unprotected_signer", "release workflow is missing environment: oss-release",
       lambda root: replace(root, ".github/workflows/release.yml", "environment: oss-release", "environment: staging") or [])
reject("missing_release_archive", "release archive output does not match published asset path",
       lambda root: replace(root, ".github/workflows/release.yml",
                            '-czf "target/agentworks-${{ inputs.tag }}.tar.gz" -C target',
                            '-C target -czf "agentworks-${{ inputs.tag }}.tar.gz"') or [])
reject("unknown_or_incompatible_license", "incompatible license", lambda root: sbom(root))
reject("private_dependency", "private dependency", lambda root: sbom(
    root, "Apache-2.0", "pkg:maven/com.dvarahq.agentworks.enterprise/private-runtime@1"))
reject("unreviewed_high_vulnerability", "unexcepted HIGH vulnerability", vulnerabilities)
reject("wrong_version_vulnerability_exception", "unexcepted HIGH vulnerability", wrong_version_exception)


with tempfile.TemporaryDirectory() as directory:
    root = tracked_copy(Path(directory))
    result = run_verify(root)
    if result.returncode:
        raise SystemExit("supply-chain-fixtures=FAIL: unmodified tree fails: " + result.stdout + result.stderr)

with tempfile.TemporaryDirectory() as directory:
    scratch = Path(directory)
    source = scratch / "agentworks"
    (source / "templates").mkdir(parents=True)
    (source / "Chart.yaml").write_text("apiVersion: v2\nname: agentworks\nversion: 1.0.0\n", encoding="utf-8")
    (source / "templates/deployment.yaml").write_text("kind: Deployment\n", encoding="utf-8")
    first, second = scratch / "first.tgz", scratch / "second.tgz"
    command = ["python3", str(ROOT / "scripts/release/package_chart.py"), "--source", str(source),
               "--source-date-epoch", "1700000000"]
    subprocess.run([*command, "--output", str(first)], check=True)
    os.utime(source / "Chart.yaml", (1800000000, 1800000000))
    subprocess.run([*command, "--output", str(second)], check=True)
    if first.read_bytes() != second.read_bytes():
        raise SystemExit("supply-chain-fixtures=FAIL: chart package is not reproducible")
print("chart_rebuild=IDENTICAL")


def oci(path: Path, amd: str, arm: str, attestation: str) -> None:
    nested = {"manifests": [
        {"digest": amd, "platform": {"os": "linux", "architecture": "amd64"}},
        {"digest": arm, "platform": {"os": "linux", "architecture": "arm64"}},
        {"digest": attestation, "annotations": {"vnd.docker.reference.type": "attestation-manifest"}},
    ]}
    content = json.dumps(nested).encode()
    digest = hashlib.sha256(content).hexdigest()
    index = {"manifests": [{
        "digest": f"sha256:{digest}",
        "mediaType": "application/vnd.oci.image.index.v1+json",
    }]}
    with tarfile.open(path, "w") as archive:
        nested_info = tarfile.TarInfo(f"blobs/sha256/{digest}")
        nested_info.size = len(content)
        archive.addfile(nested_info, io.BytesIO(content))
        content = json.dumps(index).encode()
        info = tarfile.TarInfo("index.json")
        info.size = len(content)
        archive.addfile(info, io.BytesIO(content))


with tempfile.TemporaryDirectory() as directory:
    scratch = Path(directory)
    first, second, changed = scratch / "first.tar", scratch / "second.tar", scratch / "changed.tar"
    oci(first, "sha256:" + "a" * 64, "sha256:" + "b" * 64, "sha256:" + "c" * 64)
    oci(second, "sha256:" + "a" * 64, "sha256:" + "b" * 64, "sha256:" + "d" * 64)
    oci(changed, "sha256:" + "e" * 64, "sha256:" + "b" * 64, "sha256:" + "d" * 64)
    verifier = ["python3", str(ROOT / "scripts/release/verify_oci_reproducibility.py")]
    subprocess.run([*verifier, str(first), str(second)], check=True)
    result = subprocess.run([*verifier, str(first), str(changed)], capture_output=True, text=True)
    if result.returncode == 0 or "runnable manifests differ" not in result.stderr + result.stdout:
        raise SystemExit("supply-chain-fixtures=FAIL: changed OCI runnable manifest was accepted")
print("oci_runnable_manifest_changed=REJECTED")

with tempfile.TemporaryDirectory() as directory:
    scratch = Path(directory)
    artifacts = scratch / "artifacts"
    for kind, name in (("maven", "core.jar"), ("cli", "agentworks"),
                       ("oci", "agentworks.oci.tar"), ("helm", "agentworks.tgz")):
        path = artifacts / kind / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(f"{kind}-fixture".encode())
    candidate = scratch / "candidate"
    create = ["python3", str(ROOT / "scripts/release/create_release_evidence.py"),
              "--artifacts", str(artifacts), "--output", str(candidate),
              "--commit", "a" * 40, "--tag", "v1.0.0", "--source-date-epoch", "1700000000",
              "--github-actor", "grabdoc"]
    subprocess.run(create, cwd=ROOT, check=True)
    sentinel = candidate / "caller-owned-sentinel"
    sentinel.write_text("preserve\n", encoding="utf-8")
    repeated = subprocess.run(create, cwd=ROOT, capture_output=True, text=True)
    if repeated.returncode == 0 or "output already exists" not in repeated.stderr + repeated.stdout \
            or sentinel.read_text(encoding="utf-8") != "preserve\n":
        raise SystemExit("supply-chain-fixtures=FAIL: existing release output was overwritten")
    sentinel.unlink()
    print("existing_release_output=REJECTED")
    verify = ["python3", str(ROOT / "scripts/release/verify_release_candidate.py"), str(candidate),
              "--phase", "build", "--expected-commit", "a" * 40]
    subprocess.run(verify, cwd=ROOT, check=True)
    fake_bin = scratch / "bin"
    fake_bin.mkdir()
    cosign_log = scratch / "cosign.args"
    fake_cosign = fake_bin / "cosign"
    fake_cosign.write_text(f"#!/bin/sh\nprintf '%s\\n' \"$@\" > {cosign_log}\n", encoding="utf-8")
    fake_cosign.chmod(0o755)
    (candidate / "SHA256SUMS.sigstore.json").write_text("{}\n", encoding="utf-8")
    public_key = scratch / "cosign.pub"
    public_key.write_text("qualification-only\n", encoding="utf-8")
    release_verify = [*verify[:-4], "--phase", "release", "--allow-qualification",
                      "--public-key", str(public_key), "--expected-commit", "a" * 40]
    environment = {**os.environ, "PATH": f"{fake_bin}:{os.environ['PATH']}"}
    subprocess.run(release_verify, cwd=ROOT, env=environment, check=True)
    arguments = cosign_log.read_text(encoding="utf-8").splitlines()
    if "--insecure-ignore-tlog" not in arguments or "--key" not in arguments:
        raise SystemExit("supply-chain-fixtures=FAIL: offline qualification did not isolate transparency-log lookup")
    print("offline_signature_qualification=PASS")
    (candidate / "artifacts/cli/agentworks").write_bytes(b"tampered")
    result = subprocess.run(verify, cwd=ROOT, capture_output=True, text=True)
    if result.returncode == 0 or "checksum mismatch" not in result.stderr + result.stdout:
        raise SystemExit("supply-chain-fixtures=FAIL: tampered release artifact was accepted")
print("release_artifact_tampered=REJECTED")
print("supply-chain-fixtures=PASS fixtures=16")
