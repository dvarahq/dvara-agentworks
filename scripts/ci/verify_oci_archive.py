#!/usr/bin/env python3
"""Verify that a buildx OCI archive contains both supported platforms and BuildKit attestations."""

from __future__ import annotations

import json
import pathlib
import sys
import tarfile

if len(sys.argv) != 2:
    raise SystemExit("usage: verify_oci_archive.py ARCHIVE")

archive = pathlib.Path(sys.argv[1])
with tarfile.open(archive) as bundle:
    index = json.load(bundle.extractfile("index.json"))
    manifests = index.get("manifests", [])
    if len(manifests) == 1 and manifests[0].get("mediaType", "").endswith("image.index.v1+json"):
        digest = manifests[0]["digest"].split(":", 1)[1]
        manifests = json.load(bundle.extractfile(f"blobs/sha256/{digest}"))["manifests"]
platforms = {((item.get("platform") or {}).get("os"),
              (item.get("platform") or {}).get("architecture")) for item in manifests}
required = {("linux", "amd64"), ("linux", "arm64")}
missing = required - platforms
if missing:
    raise SystemExit(f"oci-archive=FAIL: missing platforms {sorted(missing)}")

attestations = [item for item in manifests
                if item.get("annotations", {}).get("vnd.docker.reference.type") == "attestation-manifest"]
if len(attestations) < 2:
    raise SystemExit("oci-archive=FAIL: expected per-platform SBOM/provenance attestation manifests")

print(f"oci-archive=PASS platforms={len(required)} attestations={len(attestations)}")
