#!/usr/bin/env python3
"""Verify the published release compatibility policy against code, CI, and golden evidence."""

from __future__ import annotations

import json
import os
import re
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(os.environ.get("AGENTWORKS_REPOSITORY_ROOT", Path(__file__).resolve().parents[2]))
POLICY = Path("config/compatibility/policy.json")
DEPRECATIONS = Path("config/compatibility/deprecations.json")
VECTORS = Path("config/compatibility/golden-vectors.json")
NS = {"m": "http://maven.apache.org/POM/4.0.0"}


def load(relative: Path) -> dict:
    return json.loads((ROOT / relative).read_text(encoding="utf-8"))


def text(relative: str) -> str:
    return (ROOT / relative).read_text(encoding="utf-8")


def property_value(root: ET.Element, name: str) -> str | None:
    properties = root.find("m:properties", NS)
    node = None if properties is None else properties.find(f"m:{name}", NS)
    return None if node is None else node.text


def discovered_test_cases() -> set[str]:
    cases: set[str] = set()
    method = re.compile(r"@(?:Test|ParameterizedTest|RepeatedTest)\b[\s\S]{0,600}?\b(\w+)\s*\(")
    for module in ROOT.glob("agentworks-*"):
        for path in module.glob("src/test/java/**/*Test.java"):
            relative = path.relative_to(ROOT).as_posix()
            methods = set(method.findall(path.read_text(encoding="utf-8")))
            cases.update(f"{relative}#{name}" for name in methods)
            if not methods:
                cases.add(relative + "#*")
    return cases


def problems(root: Path = ROOT) -> list[str]:
    global ROOT
    previous = ROOT
    ROOT = root
    try:
        found: list[str] = []
        for relative in (POLICY, DEPRECATIONS, VECTORS, Path("docs/operations/compatibility.md")):
            if not (ROOT / relative).is_file():
                found.append(f"missing {relative}")
        if found:
            return found

        policy = load(POLICY)
        deprecations = load(DEPRECATIONS)
        vectors = load(VECTORS)
        if policy.get("schemaVersion") != 1:
            found.append("policy schemaVersion must be 1")
        if set(policy.get("statusDefinitions", {})) != {"tested", "supported", "experimental", "unsupported"}:
            found.append("policy must define exactly tested/supported/experimental/unsupported")
        if policy.get("eolPolicy", {}).get("noticeDays", 0) < 30:
            found.append("EOL notice must be at least 30 days")

        pom = ET.parse(ROOT / "pom.xml").getroot()
        java = property_value(pom, "java.version")
        boot = pom.findtext("m:parent/m:version", namespaces=NS)
        spring_ai = property_value(pom, "spring-ai.version")
        if str(policy.get("java", {}).get("major")) != java:
            found.append(f"Java policy {policy.get('java', {}).get('major')} differs from pom {java}")
        if policy.get("framework", {}).get("springBoot", {}).get("version") != boot:
            found.append("Spring Boot policy differs from the parent pom")
        if policy.get("framework", {}).get("springAi", {}).get("version") != spring_ai:
            found.append("Spring AI policy differs from the pom")

        postgres = policy.get("postgresql", {})
        minimum, maximum = postgres.get("minimumMajor"), postgres.get("maximumMajor")
        if postgres.get("supportedMajors") != list(range(minimum, maximum + 1)):
            found.append("PostgreSQL supportedMajors must be the contiguous min/max range")
        if len(postgres.get("testedImages", [])) != 2 or not all(
                re.fullmatch(r"postgres:\d+\.\d+-alpine", image) for image in postgres.get("testedImages", [])):
            found.append("PostgreSQL min/max testedImages must be exact minor alpine tags")
        tested_majors = [int(image.split(":", 1)[1].split(".", 1)[0]) for image in postgres.get("testedImages", [])]
        if tested_majors != [minimum, maximum]:
            found.append("PostgreSQL testedImages must name the minimum and maximum supported majors")

        manifest = policy.get("contracts", {}).get("releaseManifest", {})
        if manifest.get("read") != list(range(1, manifest.get("write", 0) + 1)):
            found.append("manifest read versions must be contiguous through the writer")
        source = text("agentworks-gateway-api/src/main/java/com/dvarahq/agentworks/gateway/PinnedModelRoles.java")
        if f"MODEL_RESILIENCE_MANIFEST_VERSION = {manifest.get('write')}" not in source:
            found.append("manifest writer differs from PinnedModelRoles")
        executor = text("agentworks-runtime/src/main/java/com/dvarahq/agentworks/agent/remote/ExternalRunnerClient.java")
        if f'PROTOCOL_VERSION = "{policy["contracts"]["externalExecutorSubprocess"]["write"]}"' not in executor:
            found.append("subprocess protocol policy differs from ExternalRunnerClient")
        remote = text("agentworks-agent-a2a/src/main/java/com/dvarahq/agentworks/a2a/RemoteExecutorHttpClient.java")
        http = policy["contracts"]["externalExecutorHttp"]
        for constant, value in (("PROTOCOL_VERSION", http["protocol"]), ("SCHEMA_VERSION", http["schema"])):
            if f'{constant} = "{value}"' not in remote:
                found.append(f"HTTP executor {constant} differs from policy")

        chart = text("deploy/helm/agentworks/Chart.yaml")
        kubernetes = policy["containers"]["kubernetes"]
        if (f'>={kubernetes["minimumMinor"]}.0-0 <1.{int(kubernetes["maximumMinor"].split(".")[1]) + 1}.0-0'
                not in chart):
            found.append("Kubernetes policy differs from the Helm kubeVersion range")

        qualification = ROOT / ".github/workflows/qualification.yml"
        workflow = qualification.read_text(encoding="utf-8") if qualification.is_file() else text(".github/workflows/core-ci.yml")
        if not qualification.is_file():
            public_gate = text("scripts/ci/pr-verification.sh")
            if ("scripts/ci/pr-verification.sh" not in workflow
                    or "scripts/ci/verify_repository_policy.py" not in public_gate):
                found.append("public CI does not run the repository compatibility gate")
        else:
            required_workflow_fragments = (
                "Compatibility (Java distribution)", "Compatibility (PostgreSQL)",
                "Compatibility (Kubernetes)", "Compatibility (browser)",
                "-Dmaven.compiler.showDeprecation=true", "-Dmaven.compiler.failOnWarning=true",
                "python3 scripts/ci/verify_compatibility_policy.py",
            )
            for fragment in required_workflow_fragments:
                if fragment not in workflow:
                    found.append(f"CI compatibility matrix is missing {fragment}")
            for image in postgres.get("testedImages", []):
                if image not in workflow:
                    found.append(f"CI does not exercise {image}")
            for version in kubernetes.get("testedMinMax", []):
                if version not in workflow:
                    found.append(f"CI does not exercise Kubernetes {version}")
            for distribution in ("temurin", "corretto"):
                if distribution not in workflow:
                    found.append(f"CI does not exercise Java distribution {distribution}")
            for browser in policy["clients"]["browser"]["testedEngines"]:
                if browser not in workflow:
                    found.append(f"CI does not exercise browser engine {browser}")

        if deprecations.get("schemaVersion") != 1 or not isinstance(deprecations.get("entries"), list):
            found.append("deprecation inventory is malformed")
        required_deprecation = set(deprecations.get("entryRequiredFields", []))
        expected_fields = {"symbol", "owner", "trackingIssue", "removalRelease", "replacement", "reason"}
        if required_deprecation != expected_fields:
            found.append("deprecation inventory required fields are incomplete")
        symbols = set()
        for entry in deprecations.get("entries", []):
            if set(entry) != expected_fields or not all(entry.values()):
                found.append("every deprecation entry must contain non-empty required fields")
            symbols.add(entry.get("symbol"))
        deliberate = []
        for module in ROOT.glob("agentworks-*"):
            for path in module.glob("src/*/java/**/*.java"):
                content = path.read_text(encoding="utf-8")
                if "@SuppressWarnings(\"deprecation\")" in content or "@SuppressWarnings(\"removal\")" in content:
                    deliberate.append(path.relative_to(ROOT).as_posix())
        if deliberate and not symbols:
            found.append(f"unrecorded deprecated API suppressions: {deliberate}")

        tests = discovered_test_cases()
        entries = vectors.get("vectors", [])
        ids = [entry.get("id") for entry in entries]
        if vectors.get("schemaVersion") != 1 or len(ids) != len(set(ids)):
            found.append("golden vector catalog is malformed or has duplicate IDs")
        required_contracts = {"agentSpec", "releaseManifest", "runEvent", "externalExecutorSubprocess",
                              "externalExecutorHttp", "mcp", "a2aExposure"}
        if required_contracts - {entry.get("contract") for entry in entries}:
            found.append("golden vector catalog does not cover every required contract")
        for entry in entries:
            reference = entry.get("test", "")
            path, marker, method = reference.partition("#")
            if not (ROOT / path).is_file() or not marker:
                found.append(f"golden vector {entry.get('id')} has no executable test")
            elif method != "*" and reference not in tests:
                found.append(f"golden vector {entry.get('id')} references missing test {reference}")
            fixture = entry.get("fixture")
            if fixture and not (ROOT / fixture).is_file():
                found.append(f"golden vector {entry.get('id')} fixture is missing")
        for contract in ("agentSpec", "releaseManifest", "runEvent", "externalExecutorSubprocess",
                         "externalExecutorHttp", "mcp", "a2aExposure"):
            expectations = {entry.get("expect") for entry in entries if entry.get("contract") == contract}
            if not any(value == "accept" or str(value).startswith("read-") for value in expectations):
                found.append(f"golden vectors have no accepted {contract} example")
            if "reject" not in expectations:
                found.append(f"golden vectors have no rejected {contract} example")
        return found
    finally:
        ROOT = previous


if __name__ == "__main__":
    if issues := problems():
        raise SystemExit("compatibility-policy=FAIL: " + "; ".join(issues))
    print("compatibility-policy=PASS java=25 postgres=15-18 kubernetes=1.35-1.37 browsers=3 vectors=15")
