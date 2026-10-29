#!/usr/bin/env python3
"""Verify the provenance of the Web Console's vendored frontend assets (issue #36).

config/frontend-assets.json records, for every third-party package vendored under the Console's static assets, its
version, SPDX license, the npm registry tarball it came from with that tarball's published integrity, and the SHA-256
of every vendored file. This gate requires that every vendored file is recorded and unchanged, that every package
ships its license text, and that every Console template loading a vendored script or stylesheet pins the recorded
subresource integrity, so an asset cannot change, appear, or lose its license without review.
"""

import base64
import hashlib
import json
import os
import re
from pathlib import Path

ROOT = Path(os.environ.get("AGENTWORKS_REPOSITORY_ROOT", Path(__file__).resolve().parents[2]))
MANIFEST = Path("config/frontend-assets.json")
TEMPLATES = Path("agentworks-server/src/main/resources/templates/console")
ALLOWED_LICENSES = {"MIT", "0BSD", "BSD-2-Clause", "BSD-3-Clause", "Apache-2.0", "ISC"}


def problems(root: Path) -> list[str]:
    manifest = json.loads((root / MANIFEST).read_text(encoding="utf-8"))
    vendor = root / manifest["root"]
    found, recorded, integrities = [], set(), {}
    for package in manifest["packages"]:
        name = package["name"]
        if package.get("license") not in ALLOWED_LICENSES:
            found.append(f"frontend asset {name} has license {package.get('license')!r}, which the policy does not allow")
        for field in ("version", "source", "sourceIntegrity", "directory"):
            if not package.get(field):
                found.append(f"frontend asset {name} does not record its {field}")
        if not package.get("source", "").startswith("https://registry.npmjs.org/"):
            found.append(f"frontend asset {name} must come from the npm registry")
        paths = {entry["path"] for entry in package.get("files", [])}
        if "LICENSE" not in paths:
            found.append(f"frontend asset {name} does not ship its license text")
        for entry in package.get("files", []):
            relative = f"{package['directory']}/{entry['path']}"
            recorded.add(relative)
            path = vendor / relative
            if not path.is_file():
                found.append(f"vendored file {relative} is recorded but missing")
                continue
            content = path.read_bytes()
            if hashlib.sha256(content).hexdigest() != entry["sha256"]:
                found.append(f"vendored file {relative} does not match its recorded sha256")
            if entry.get("integrity"):
                algorithm, _, value = entry["integrity"].partition("-")
                actual = base64.b64encode(hashlib.new(algorithm, content).digest()).decode() if algorithm in (
                    "sha256", "sha384", "sha512") else None
                if actual != value:
                    found.append(f"vendored file {relative} does not match its recorded integrity")
                integrities[relative] = entry["integrity"]
    if vendor.is_dir():
        for path in sorted(vendor.rglob("*")):
            relative = path.relative_to(vendor).as_posix()
            if path.is_file() and relative not in recorded:
                found.append(f"vendored file {relative} is not recorded in {MANIFEST}")
    references = 0
    for template in sorted((root / TEMPLATES).glob("*.html")):
        text = template.read_text(encoding="utf-8")
        # Every tag, and Thymeleaf's th:src and th:href as well as plain src and href.
        for tag in re.findall(r"<[a-zA-Z][^>]*>", text, re.S):
            if re.search(r"\b(?:th:)?(?:src|href)=\"[^\"]*/console/assets/vendor/", tag) and re.search(r"\bth:(?:src|href)=", tag):
                found.append(f"{template.name} builds a vendored asset URL with an expression; use a literal path")
            if re.match(r"<(?:script|link|img|iframe|source|video|audio|embed|object)\b", tag) and re.search(
                    r"\b(?:th:)?(?:src|href|data)=\"(?:@\{|[^\"]*:)?//", tag):
                found.append(f"{template.name} loads an asset from another origin")
            source = re.search(r"\b(?:src|href)=\"/console/assets/vendor/([^\"]+)\"", tag)
            if not source:
                continue
            references += 1
            integrity = re.search(r"integrity=\"([^\"]+)\"", tag)
            expected = integrities.get(source.group(1))
            if expected is None:
                found.append(f"{template.name} loads unrecorded vendored asset {source.group(1)}")
            elif not integrity or integrity.group(1) != expected:
                found.append(f"{template.name} does not pin the recorded integrity of {source.group(1)}")
    if references == 0:
        found.append("no Console template loads a vendored asset; the gate would pass vacuously")
    return found


if __name__ == "__main__":
    if issues := problems(ROOT):
        raise SystemExit("frontend-assets=FAIL: " + "; ".join(issues))
    print(f"frontend-assets=PASS manifest={MANIFEST}")
