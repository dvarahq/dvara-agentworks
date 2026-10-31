#!/usr/bin/env python3
"""Verify the published dependency, licensing, CI, and secret-handling policy."""

import re
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "scripts" / "ci"))
from verify_resumable_work import problems as resumable_work_problems  # noqa: E402
from verify_frontend_assets import problems as frontend_asset_problems  # noqa: E402
from verify_journey_isolation import problems as journey_isolation_problems  # noqa: E402
from verify_compatibility_policy import problems as compatibility_policy_problems  # noqa: E402
from verify_supply_chain import problems as supply_chain_problems  # noqa: E402
from verify_documentation import documentation_problems  # noqa: E402
from verify_configuration_contract import problems as configuration_contract_problems  # noqa: E402

NS = {"m": "http://maven.apache.org/POM/4.0.0"}
FORBIDDEN_GROUPS = (
    "org.apache.kafka", "org.springframework.kafka", "org.springframework.data",
    "redis.clients", "io.lettuce", "org.redisson", "org.hibernate", "software.amazon.awssdk",
    "com.azure", "com.google.cloud",
)
FORBIDDEN_COORDINATES = {
    ("org.springframework.boot", "spring-boot-starter-data-redis"),
    ("org.springframework.boot", "spring-boot-starter-data-jpa"),
}


def fail(message: str) -> None:
    raise SystemExit(f"repository-policy=FAIL: {message}")


required = (
    ".github/workflows/core-ci.yml", ".github/workflows/release.yml", "LICENSE", "NOTICE", "DCO",
    "CONTRIBUTING.md", "LICENSE_POLICY.md", "legal/oss/LICENSE", "legal/oss/NOTICE", "legal/oss/DCO",
    "legal/oss/CONTRIBUTING.md", "legal/oss/SOURCE-OFFER.md", "public-files.txt",
    "config/branch-protection.json", "config/lifecycle/resumable-work-categories.json",
    "config/frontend-assets.json", "config/compatibility/policy.json", "config/compatibility/deprecations.json",
    "config/compatibility/golden-vectors.json", "config/supply-chain/policy.json",
    "config/supply-chain/vulnerability-exceptions.json", "config/traceability/requirements.json",
)
for relative in required:
    if not (ROOT / relative).is_file():
        fail(f"missing required policy file {relative}")

root = ET.parse(ROOT / "pom.xml").getroot()
modules = [node.text for node in root.findall("m:modules/m:module", NS)]
expected = [
    "agentworks-bom", "agentworks-api", "agentworks-capabilities", "agentworks-context", "agentworks-memory",
    "agentworks-agent-test", "agentworks-agent-test-sample", "agentworks-evaluation", "agentworks-spec",
    "agentworks-sdk", "agentworks-provider-tck", "agentworks-spring-boot-starter",
    "agentworks-reference-embedded", "agentworks-reference-remote", "agentworks-gateway-api",
    "agentworks-gateway-openai-compatible", "agentworks-gateway-dvarahq", "agentworks-storage-jdbc",
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
published = set(expected[1:]) - {
    "agentworks-agent-test-sample", "agentworks-reference-embedded", "agentworks-reference-remote"
}
if managed != published:
    fail(f"BOM does not manage the complete published reactor: {sorted(managed)}")

for pom in [ROOT / "pom.xml", *(ROOT / module / "pom.xml" for module in expected),
            ROOT / "agentworks-journey/pom.xml"]:
    document = ET.parse(pom).getroot()
    for dependency in document.findall("m:dependencies/m:dependency", NS):
        group = dependency.findtext("m:groupId", default="", namespaces=NS)
        artifact = dependency.findtext("m:artifactId", default="", namespaces=NS)
        if group == "org.springframework.data" and artifact != "spring-data-jdbc":
            fail(f"forbidden dependency {group}:{artifact} in {pom.relative_to(ROOT)}")
        if any(group == prefix or group.startswith(prefix + ".") for prefix in FORBIDDEN_GROUPS):
            fail(f"forbidden dependency {group}:{artifact} in {pom.relative_to(ROOT)}")
        if (group, artifact) in FORBIDDEN_COORDINATES:
            fail(f"forbidden dependency {group}:{artifact} in {pom.relative_to(ROOT)}")

for workflow in sorted((ROOT / ".github/workflows").glob("*.yml")):
    for action in re.findall(r"uses:\s*([^\s#]+)", workflow.read_text(encoding="utf-8")):
        if action.startswith("./"):
            continue
        if not re.fullmatch(r"[^@]+@[0-9a-f]{40}", action):
            fail(f"GitHub Action is not pinned to a commit SHA: {action}")

tracked = (ROOT / "public-files.txt").read_text(encoding="utf-8").splitlines()
if tracked != sorted(set(tracked)):
    fail("public inventory must be sorted and unique")
missing = [relative for relative in tracked if not (ROOT / relative).is_file()]
if missing:
    fail(f"public inventory names missing files: {missing[:3]}")
for relative in tracked:
    lowered = relative.lower()
    if lowered.endswith((".pem", ".key")) or "secret" in Path(lowered).name:
        fail(f"secret-like tracked filename: {relative}")

for verifier in (
    resumable_work_problems,
    frontend_asset_problems,
    journey_isolation_problems,
    compatibility_policy_problems,
    lambda _root: supply_chain_problems(),
    documentation_problems,
    configuration_contract_problems,
):
    if problems := verifier(ROOT):
        fail("; ".join(problems))

print(f"repository-policy=PASS mode=public modules={len(expected)} tracked_files={len(tracked)}")
