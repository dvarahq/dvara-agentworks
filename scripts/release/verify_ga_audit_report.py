#!/usr/bin/env python3
"""Verify the small, exact-release report accepted by the protected audit signer."""

from __future__ import annotations

import argparse
import json
import re
from pathlib import Path
from typing import Any

COMMIT = re.compile(r"[0-9a-f]{40}")
SHA256 = re.compile(r"[0-9a-f]{64}")
TAG = re.compile(r"v[0-9]+\.[0-9]+\.[0-9]+(?:-[0-9A-Za-z.-]+)?")
ALLOWED_VERDICTS = {"PASS", "PASS_WITH_NON_BLOCKING_FOLLOW_UPS"}
FIELDS = {
    "version", "tag", "repository", "commit", "verdict", "manifestSha256", "checksumsSha256"
}


def reject_json_constant(value: str) -> None:
    raise ValueError(f"non-standard JSON constant {value}")


def load(path: Path) -> dict[str, Any]:
    value = json.loads(path.read_text(encoding="utf-8"), parse_constant=reject_json_constant)
    if not isinstance(value, dict):
        raise ValueError("report root is not an object")
    return value


def problems(report: dict[str, Any], *, version: str, tag: str, repository: str, commit: str,
             manifest_sha256: str, checksums_sha256: str) -> list[str]:
    found: list[str] = []
    if set(report) != FIELDS:
        found.append(f"report fields differ from the closed contract: {sorted(report)}")
    expected = {
        "version": version,
        "tag": tag,
        "repository": repository,
        "commit": commit,
        "manifestSha256": manifest_sha256,
        "checksumsSha256": checksums_sha256,
    }
    for field, value in expected.items():
        if report.get(field) != value:
            found.append(f"report {field} differs from the verified release")
    if report.get("verdict") not in ALLOWED_VERDICTS:
        found.append("report verdict is not a passing GA-audit verdict")
    if not TAG.fullmatch(tag):
        found.append("expected tag is not an immutable release tag")
    if not COMMIT.fullmatch(commit):
        found.append("expected commit is not an exact commit")
    for label, value in (("manifest", manifest_sha256), ("checksums", checksums_sha256)):
        if not SHA256.fullmatch(value):
            found.append(f"expected {label} digest is not SHA-256")
    return found


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("report", type=Path)
    parser.add_argument("--expected-version", required=True)
    parser.add_argument("--expected-tag", required=True)
    parser.add_argument("--expected-repository", required=True)
    parser.add_argument("--expected-commit", required=True)
    parser.add_argument("--expected-manifest-sha256", required=True)
    parser.add_argument("--expected-checksums-sha256", required=True)
    args = parser.parse_args()
    try:
        report = load(args.report)
        found = problems(
            report,
            version=args.expected_version,
            tag=args.expected_tag,
            repository=args.expected_repository,
            commit=args.expected_commit,
            manifest_sha256=args.expected_manifest_sha256,
            checksums_sha256=args.expected_checksums_sha256,
        )
    except (OSError, ValueError) as error:
        found = [f"invalid GA audit report: {error}"]
    if found:
        print("ga-audit-report=FAIL")
        for problem in found:
            print(f"- {problem}")
        return 1
    print(f"ga-audit-report=PASS tag={args.expected_tag} commit={args.expected_commit}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
