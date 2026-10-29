#!/usr/bin/env python3
"""Fail closed when the OSS security RC policy or executable gate is weakened."""

import os
from pathlib import Path

ROOT = Path(os.environ.get("AGENTWORKS_REPOSITORY_ROOT", Path(__file__).resolve().parents[2]))


def require(path: str, markers: tuple[str, ...]) -> list[str]:
    source = ROOT / path
    if not source.is_file():
        return [f"missing {path}"]
    text = source.read_text(encoding="utf-8")
    return [f"{path} misses {marker!r}" for marker in markers if marker not in text]


def forbid(path: str, markers: tuple[str, ...]) -> list[str]:
    source = ROOT / path
    if not source.is_file():
        return [f"missing {path}"]
    text = source.read_text(encoding="utf-8")
    return [f"{path} still contains forbidden {marker!r}" for marker in markers if marker in text]


problems = []
problems += require("SECURITY.md", ("Report a vulnerability", "three business days", "seven business days",
                                    "docs/security/threat-model.md"))
problems += require("docs/implementation/OSS-SECURITY-GATE.md", ("## Assets and data flow", "## Authorization inventory",
        "## ADR-027 adversarial analysis", "## Isolation profile checks and residual risk",
        "## Release security gate", "RequestBodyLimitTest", "RunAdmissionFencePostgresTest",
        "Dependabot alerts are currently disabled"))
problems += require(".github/workflows/security.yml", ("scripts/ci/run_dependency_check.sh",))
problems += require("scripts/ci/run_dependency_check.sh", ("dependency-check-maven:12.2.2:aggregate",
        "DependencyCheck_Builder/nvd_cache/nvdcve-{0}.json.gz", "config/dependency-check-suppressions.xml",
        "-DfailBuildOnCVSS=7", "-DfailOnError=true"))
problems += require("pom.xml", ("<kotlin.version>2.4.20</kotlin.version>",))
problems += forbid("config/dependency-check-suppressions.xml", ("CVE-2026-53914",))
problems += require(".github/dependabot.yml", ("package-ecosystem: maven", "package-ecosystem: github-actions"))
problems += require("agentworks-server/src/main/java/com/dvarahq/agentworks/identity/web/RequestBodyLimitFilter.java",
        ("getContentLengthLong() > maximum", "read > maximum", "getServletPath()"))

if problems:
    raise SystemExit("security-gate=FAIL: " + "; ".join(problems))
print("security-gate=PASS threat-model=present response-policy=present body-limit=streamed sca=high")
