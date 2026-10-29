#!/usr/bin/env python3
"""Verify the Core dependency, licensing, CI, and secret-handling policy."""

import re
import subprocess
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "scripts" / "ci"))
from traceability_catalog import undefined_references  # noqa: E402
from verify_resumable_work import problems as resumable_work_problems  # noqa: E402
from verify_frontend_assets import problems as frontend_asset_problems  # noqa: E402
from verify_migration_immutability import problems as migration_immutability_problems  # noqa: E402
from verify_journey_isolation import problems as journey_isolation_problems  # noqa: E402
from verify_mvp_evidence import problems as mvp_evidence_problems  # noqa: E402
from verify_m1_gate import problems as m1_gate_problems  # noqa: E402
from verify_m2_gate import problems as m2_gate_problems  # noqa: E402
from verify_m3_gate import problems as m3_gate_problems  # noqa: E402
from verify_compatibility_policy import problems as compatibility_policy_problems  # noqa: E402
from verify_supply_chain import problems as supply_chain_problems  # noqa: E402
from verify_documentation import documentation_problems  # noqa: E402
sys.path.insert(0, str(ROOT / "scripts" / "release"))
from verify_rc1_readiness import policy_problems as rc1_policy_problems  # noqa: E402
from verify_ga_audit import policy_problems as ga_audit_policy_problems  # noqa: E402
from verify_ga_publication import policy_problems as ga_publication_policy_problems  # noqa: E402

NS = {"m": "http://maven.apache.org/POM/4.0.0"}
FORBIDDEN_GROUPS = (
    "org.apache.kafka", "org.springframework.kafka", "org.springframework.data",
    "redis.clients", "io.lettuce", "org.redisson", "org.hibernate", "software.amazon.awssdk",
    "com.azure", "com.google.cloud", "com.dvarahq.agentworks.enterprise",
)
FORBIDDEN_COORDINATES = {
    ("org.springframework.boot", "spring-boot-starter-data-redis"),
    ("org.springframework.boot", "spring-boot-starter-data-jpa"),
}
PUBLIC_TREE = (ROOT / "public-files.txt").is_file()


def fail(message: str) -> None:
    raise SystemExit(f"repository-policy=FAIL: {message}")


private_required = (
    ".github/workflows/core-ci.yml", "LICENSE_POLICY.md", "legal/oss/LICENSE", "legal/oss/NOTICE",
    "legal/oss/DCO", "legal/oss/CONTRIBUTING.md", "config/traceability/index.json",
    "config/traceability/requirements.json", "config/traceability/detailed-ids.json",
    "config/traceability/follow-up-issues.json", "scripts/ci/issue_spec.py",
    "config/branch-protection.json", "config/lifecycle/resumable-work-categories.json",
    "config/frontend-assets.json",
    "config/compatibility/policy.json", "config/compatibility/deprecations.json",
    "config/compatibility/golden-vectors.json",
    "config/supply-chain/policy.json", "config/supply-chain/vulnerability-exceptions.json",
    "config/release/rc1.json", "config/release/rc1-evidence-template.json",
    "config/release/ga-audit.json", "config/release/ga-audit-evidence-template.json",
    "config/release/ga-publication.json", "config/release/ga-publication-evidence-template.json",
    "legal/oss/SOURCE-OFFER.md",
)
public_required = (
    ".github/workflows/core-ci.yml", ".github/workflows/release.yml", "LICENSE", "NOTICE", "DCO",
    "CONTRIBUTING.md", "LICENSE_POLICY.md", "legal/oss/LICENSE", "legal/oss/NOTICE", "legal/oss/DCO",
    "legal/oss/CONTRIBUTING.md", "legal/oss/SOURCE-OFFER.md", "public-files.txt",
    "config/branch-protection.json", "config/lifecycle/resumable-work-categories.json",
    "config/frontend-assets.json", "config/compatibility/policy.json", "config/compatibility/deprecations.json",
    "config/compatibility/golden-vectors.json", "config/supply-chain/policy.json",
    "config/supply-chain/vulnerability-exceptions.json",
)
for relative in public_required if PUBLIC_TREE else private_required:
    if not (ROOT / relative).is_file():
        fail(f"missing required policy file {relative}")

root = ET.parse(ROOT / "pom.xml").getroot()
modules = [node.text for node in root.findall("m:modules/m:module", NS)]
expected = [
    "agentworks-bom", "agentworks-api", "agentworks-capabilities", "agentworks-context", "agentworks-memory", "agentworks-agent-test", "agentworks-agent-test-sample", "agentworks-evaluation", "agentworks-spec",
    "agentworks-sdk", "agentworks-provider-tck", "agentworks-spring-boot-starter",
    "agentworks-reference-embedded", "agentworks-reference-remote", "agentworks-gateway-api",
    "agentworks-gateway-openai-compatible", "agentworks-gateway-dvarahq",
    "agentworks-storage-jdbc",
    "agentworks-release", "agentworks-deployment", "agentworks-runtime", "agentworks-tools", "agentworks-mcp",
    "agentworks-agent-a2a", "agentworks-server",
]
if modules != expected:
    fail(f"reactor modules changed: {modules}")

bom = ET.parse(ROOT / "agentworks-bom/pom.xml").getroot()
managed = {
    node.findtext("m:artifactId", namespaces=NS)
    for node in bom.findall("m:dependencyManagement/m:dependencies/m:dependency", NS)
    if node.findtext("m:groupId", namespaces=NS) == "com.dvarahq.agentworks"
}
published = set(expected[1:]) - {"agentworks-agent-test-sample", "agentworks-reference-embedded", "agentworks-reference-remote"}
if managed != published:
    fail(f"BOM does not manage the complete Core reactor: {sorted(managed)}")

for pom in [ROOT / "pom.xml", *(ROOT / module / "pom.xml" for module in expected), ROOT / "agentworks-journey/pom.xml"]:
    document = ET.parse(pom).getroot()
    # Inspect dependencies active in the normal build. The deliberately banned
    # dependency under architecture-negative-fixture is verified separately.
    for dependency in document.findall("m:dependencies/m:dependency", NS):
        group = dependency.findtext("m:groupId", default="", namespaces=NS)
        artifact = dependency.findtext("m:artifactId", default="", namespaces=NS)
        if group == "org.springframework.data" and artifact != "spring-data-jdbc":
            fail(f"forbidden dependency {group}:{artifact} in {pom.relative_to(ROOT)}")
        if any(group == prefix or group.startswith(prefix + ".") for prefix in FORBIDDEN_GROUPS):
            fail(f"forbidden dependency {group}:{artifact} in {pom.relative_to(ROOT)}")
        if (group, artifact) in FORBIDDEN_COORDINATES:
            fail(f"forbidden dependency {group}:{artifact} in {pom.relative_to(ROOT)}")

for module in [*expected[1:], "agentworks-journey"]:
    for source in (ROOT / module).glob("src/main/java/**/*.java"):
        relative = source.relative_to(ROOT).as_posix()
        if "/enterprise/" in relative:
            continue
        if re.search(r"^import\s+com\.dvarahq\.agentworks\.enterprise\.", source.read_text(encoding="utf-8"), re.MULTILINE):
            fail(f"Core imports Enterprise code: {relative}")

workflow = (ROOT / ".github/workflows/core-ci.yml").read_text(encoding="utf-8")
for action in re.findall(r"uses:\s*([^\s#]+)", workflow):
    if not re.fullmatch(r"[^@]+@[0-9a-f]{40}", action):
        fail(f"GitHub Action is not pinned to a commit SHA: {action}")

if PUBLIC_TREE:
    tracked = (ROOT / "public-files.txt").read_text(encoding="utf-8").splitlines()
    missing = [relative for relative in tracked if not (ROOT / relative).is_file()]
    if missing:
        fail(f"public inventory names missing files: {missing[:3]}")
else:
    tracked = subprocess.check_output(["git", "ls-files"], cwd=ROOT, text=True).splitlines()
for relative in tracked:
    lowered = relative.lower()
    if lowered.endswith((".pem", ".key")) or "secret" in Path(lowered).name:
        fail(f"secret-like tracked filename: {relative}")

if PUBLIC_TREE:
    # The published tree deliberately omits private traceability, milestone, and qualification evidence. Its gate
    # verifies only contracts that are complete and independently executable from the public source.
    if problems := frontend_asset_problems(ROOT):
        fail("; ".join(problems))
    if problems := journey_isolation_problems(ROOT):
        fail("; ".join(problems))
    if problems := compatibility_policy_problems(ROOT):
        fail("; ".join(problems))
    if problems := supply_chain_problems():
        fail("; ".join(problems))
    if problems := documentation_problems(ROOT):
        fail("; ".join(problems))
    print(f"repository-policy=PASS mode=public modules={len(expected)} tracked_files={len(tracked)}")
    raise SystemExit(0)

# Detailed IDs in implementation documentation and traceability data must be catalog-defined.
if problems := undefined_references(ROOT):
    fail("; ".join(problems))

# Cohort retirement is safe only while every resumable-work table is catalogued and fenced (issue #155).
if problems := resumable_work_problems(ROOT):
    fail("; ".join(problems))

# The Web Console's vendored frontend assets keep their recorded provenance, license, and integrity (issue #36).
if problems := frontend_asset_problems(ROOT):
    fail("; ".join(problems))

# A migration a published release (a v* tag) shipped is never edited or removed; Flyway would refuse it (#201).
if problems := migration_immutability_problems(ROOT):
    fail("; ".join(problems))

# The walking-skeleton journey harness reaches the product through public extension points only (#37).
if problems := journey_isolation_problems(ROOT):
    fail("; ".join(problems))

# The MVP evidence report agrees with the traceability catalog and records findings, owners, and identities (#37).
if problems := mvp_evidence_problems(ROOT):
    fail("; ".join(problems))

# The independent M1 verdict remains bound to exact identities, results, findings, and progression (#80).
if problems := m1_gate_problems(ROOT):
    fail("; ".join(problems))

# The independent M2 verdict remains bound to exact identities, results, findings, and progression (#81).
if problems := m2_gate_problems(ROOT):
    fail("; ".join(problems))

# The independent M3 verdict remains bound to exact identities, scope, findings, and progression (#82).
if problems := m3_gate_problems(ROOT):
    fail("; ".join(problems))

# The release compatibility matrix must agree with code, CI boundaries, golden vectors, and deprecation ownership.
if problems := compatibility_policy_problems(ROOT):
    fail("; ".join(problems))

# OSS release artifacts are reproducible, license-audited, secret-free, vulnerability-gated, and protected (#65).
if problems := supply_chain_problems():
    fail("; ".join(problems))

# Public guides, links, commands, API operations, and configuration namespaces cannot drift (#66).
if problems := documentation_problems(ROOT):
    fail("; ".join(problems))

# RC1 keeps one frozen distribution identity and cannot manufacture GO without external qualification evidence (#67).
if problems := rc1_policy_problems(ROOT):
    fail("; ".join(problems))

# GA requires a closed, exact-RC, independently signed audit; policy scaffolding alone never claims PASS (#68).
if problems := ga_audit_policy_problems(ROOT):
    fail("; ".join(problems))

# Public GA promotion stays PENDING until the complete exact-commit family exists (#69).
if problems := ga_publication_policy_problems(ROOT):
    fail("; ".join(problems))

print(f"repository-policy=PASS modules={len(expected)} tracked_files={len(tracked)}")
