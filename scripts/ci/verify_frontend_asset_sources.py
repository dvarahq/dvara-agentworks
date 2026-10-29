#!/usr/bin/env python3
"""Verify that the Web Console's vendored frontend assets are byte-identical to their npm registry tarballs (issue #36).

verify_frontend_assets.py checks the vendored files against the digests recorded in config/frontend-assets.json,
offline. This gate closes the remaining gap: it downloads each package's tarball from the npm registry, checks it
against the recorded sourceIntegrity (the registry's published sha512), and compares every vendored file with the
tarball member it came from, so the recorded digests cannot drift from the published package. It needs network access
to registry.npmjs.org; CI runs it in the policy job. Set AGENTWORKS_ASSET_TARBALLS to a directory of already
downloaded <name>-<version>.tgz files to run it offline (the integrity check still applies).
"""

import base64
import hashlib
import io
import json
import os
import tarfile
import urllib.request
from pathlib import Path

ROOT = Path(os.environ.get("AGENTWORKS_REPOSITORY_ROOT", Path(__file__).resolve().parents[2]))
MANIFEST = Path("config/frontend-assets.json")


def tarball(package: dict) -> bytes:
    local = os.environ.get("AGENTWORKS_ASSET_TARBALLS")
    if local:
        return (Path(local) / f"{package['name']}-{package['version']}.tgz").read_bytes()
    with urllib.request.urlopen(package["source"], timeout=60) as response:  # noqa: S310 - a recorded https URL
        return response.read()


def problems(root: Path) -> list[str]:
    manifest = json.loads((root / MANIFEST).read_text(encoding="utf-8"))
    vendor = root / manifest["root"]
    found = []
    for package in manifest["packages"]:
        name = package["name"]
        if not package.get("source", "").startswith("https://registry.npmjs.org/"):
            found.append(f"frontend asset {name} must come from the npm registry")
            continue
        data = tarball(package)
        algorithm, _, expected = package["sourceIntegrity"].partition("-")
        if base64.b64encode(hashlib.new(algorithm, data).digest()).decode() != expected:
            found.append(f"the {name} tarball does not match its recorded sourceIntegrity")
            continue
        with tarfile.open(fileobj=io.BytesIO(data)) as archive:
            for entry in package["files"]:
                try:
                    member = archive.extractfile(entry["from"])
                except KeyError:
                    found.append(f"{entry['from']} is not in the {name} {package['version']} tarball "
                                 f"(recorded for {package['directory']}/{entry['path']})")
                    continue
                vendored = vendor / package["directory"] / entry["path"]
                if member is None or not vendored.is_file() or member.read() != vendored.read_bytes():
                    found.append(f"vendored file {package['directory']}/{entry['path']} differs from {entry['from']} "
                                 f"in the {name} {package['version']} tarball")
    return found


if __name__ == "__main__":
    if issues := problems(ROOT):
        raise SystemExit("frontend-asset-sources=FAIL: " + "; ".join(issues))
    print(f"frontend-asset-sources=PASS manifest={MANIFEST}")
