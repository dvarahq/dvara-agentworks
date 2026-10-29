#!/usr/bin/env python3
"""Compare runnable OCI platform manifests while isolating signed attestation metadata."""

from __future__ import annotations

import argparse
import json
import tarfile
from pathlib import Path


def subjects(path: Path) -> dict[str, str]:
    with tarfile.open(path, "r") as archive:
        index = json.load(archive.extractfile("index.json"))
        found = {}
        pending = list(index.get("manifests", []))
        visited = set()
        while pending:
            manifest = pending.pop()
            annotations = manifest.get("annotations", {})
            if annotations.get("vnd.docker.reference.type") == "attestation-manifest":
                continue
            digest = manifest.get("digest", "")
            if manifest.get("mediaType", "").endswith("image.index.v1+json"):
                if digest in visited:
                    continue
                visited.add(digest)
                nested = json.load(archive.extractfile(f"blobs/sha256/{digest.split(':', 1)[1]}"))
                pending.extend(nested.get("manifests", []))
                continue
            platform = manifest.get("platform", {})
            if platform.get("os") and platform.get("architecture"):
                found[f"{platform['os']}/{platform['architecture']}"] = digest
    return found


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("first", type=Path)
    parser.add_argument("second", type=Path)
    args = parser.parse_args()
    first, second = subjects(args.first), subjects(args.second)
    required = {"linux/amd64", "linux/arm64"}
    if set(first) != required or first != second:
        raise SystemExit(f"oci-reproducibility=FAIL: runnable manifests differ: first={first} second={second}")
    print("oci-reproducibility=PASS runnable_manifests=2 isolated_nondeterminism=attestations")


if __name__ == "__main__":
    main()
