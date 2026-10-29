#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$repo_root"

expected="$(mktemp)"
actual="$(mktemp)"
trap 'rm -f "$expected" "$actual"' EXIT

LC_ALL=C sort -u public-files.txt > "$expected"
if git rev-parse --is-inside-work-tree >/dev/null 2>&1; then
  git ls-files | LC_ALL=C sort > "$actual"
else
  find . -type f -not -path './.git/*' -print \
    | sed 's#^./##' \
    | LC_ALL=C sort > "$actual"
fi

if ! diff -u "$expected" "$actual"; then
  echo "public tree contains an unapproved or missing tracked file" >&2
  exit 1
fi

python3 - "$repo_root" <<'PY'
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

root = Path(sys.argv[1])
namespace = {"m": "http://maven.apache.org/POM/4.0.0"}
pom = ET.parse(root / "pom.xml").getroot()
modules = [node.text for node in pom.findall("m:modules/m:module", namespace)]
modules += [node.text for node in pom.findall("m:profiles/m:profile/m:modules/m:module", namespace)]
if len(modules) != 26 or len(modules) != len(set(modules)):
    raise SystemExit(f"public reactor must contain 26 unique modules, found {len(modules)}")
for module in modules:
    if not (root / module / "pom.xml").is_file():
        raise SystemExit(f"public reactor module is incomplete: {module}")

forbidden_paths = ("AGENTS.md", "docs/implementation/", "/src/")
for relative in (root / "public-files.txt").read_text(encoding="utf-8").splitlines():
    normalized = "/" + relative
    enterprise_source = (forbidden_paths[2] in normalized
                         and "/com/dvarahq/agentworks/enterprise/" in normalized)
    if relative == forbidden_paths[0] or forbidden_paths[1] in normalized or enterprise_source:
        raise SystemExit(f"forbidden public path: {relative}")

forbidden_content = (
    "dvara-agentworks" + "-ee",
    "dvara-agent-os" + "-enterprise",
    "DvaraAgentWorks" + "EnterpriseApplication",
    "Dvara AgentWorks " + "Enterprise Reactor",
)
enterprise_boundary_files = {
    "agentworks-server/src/test/java/com/dvarahq/agentworks/architecture/ArchitectureBoundaryTest.java",
    "agentworks-server/src/test/java/com/dvarahq/agentworks/architecture/DvaraArchitectureRules.java",
    "config/release/ga-audit.json",
    "config/supply-chain/policy.json",
    "pom.xml",
    "scripts/ci/test_supply_chain.py",
    "scripts/ci/traceability_catalog.py",
    "scripts/ci/verify_journey_isolation.py",
    "scripts/ci/verify_repository_policy.py",
    "scripts/release/verify_ga_publication.py",
    "scripts/verify-public-tree.sh",
}
binary = {".gif", ".gz", ".jar", ".jpeg", ".jpg", ".png", ".woff", ".woff2", ".zip"}
for path in root.rglob("*"):
    if not path.is_file() or ".git" in path.parts or path.suffix.lower() in binary:
        continue
    try:
        text = path.read_text(encoding="utf-8")
    except UnicodeDecodeError:
        continue
    relative = path.relative_to(root).as_posix()
    for marker in forbidden_content:
        if marker.casefold() in text.casefold():
            raise SystemExit(f"forbidden private marker in {path.relative_to(root)}: {marker}")
    public_text = text.casefold()
    if relative == "README.md":
        public_text = public_text.replace("production-grade enterprise agents", "")
        public_text = public_text.replace("enterprise ai agent runtime", "")
    if "enterprise" in public_text and relative not in enterprise_boundary_files:
        raise SystemExit(f"unapproved Enterprise reference in {relative}")
PY

echo "public-tree=PASS modules=26"
