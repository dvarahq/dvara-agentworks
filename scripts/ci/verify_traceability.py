#!/usr/bin/env python3
"""Verify append-only traceability v4 and test-case-level executable evidence."""

import json
import os
import re
import subprocess
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from issue_spec import validate as validate_snapshot  # noqa: E402
from traceability_catalog import JOURNEY_ID, REQUIREMENT_ID, ROOT, USE_CASE_ID, Catalog, load_json  # noqa: E402

DIRECTORY = ROOT / "config/traceability"
COVERAGE = {"full", "partial"}
ISSUE_KEYS = {"issue", "title", "requirements", "use_cases", "journeys", "evidence"}
EVIDENCE_KEYS = {"coverage", "gap", "gap_issues", "note", "tests", "commands"}


def fail(message: str) -> None:
    raise SystemExit(f"traceability=FAIL: {message}")


def discovered_tests() -> tuple[set[str], set[str]]:
    files, cases = set(), set()
    java_method = re.compile(r"@(?:Test|ParameterizedTest|RepeatedTest)\b[\s\S]{0,600}?\b(?:public\s+|protected\s+|private\s+)?(?:static\s+)?(?:final\s+)?[\w<>?, \[\].]+\s+(\w+)\s*\(")
    for module in ROOT.glob("agentworks-*"):
        for pattern in ("src/test/java/**/*Test.java", "src/test/java/**/*Tests.java"):
            for path in module.glob(pattern):
                relative = path.relative_to(ROOT).as_posix(); files.add(relative)
                methods = set(java_method.findall(path.read_text(encoding="utf-8")))
                cases.update(f"{relative}#{method}" for method in methods)
                if not methods: cases.add(f"{relative}#*")
    for path in (ROOT / "src/test/python").glob("test_*.py"):
        relative = path.relative_to(ROOT).as_posix(); files.add(relative)
        methods = set(re.findall(r"^\s*def\s+(test_\w+)\s*\(", path.read_text(encoding="utf-8"), re.MULTILINE))
        cases.update(f"{relative}#{method}" for method in methods)
    return files, cases


TEST_FILES, TEST_CASES = discovered_tests()


def verify_test(owner: str, reference: str) -> None:
    path, marker, method = reference.partition("#")
    if path not in TEST_FILES:
        fail(f"{owner} references {path}, which is not an existing executable test")
    if not marker:
        fail(f"{owner} must cite a test method or explicit #* whole-class wildcard: {reference}")
    if method != "*" and reference not in TEST_CASES:
        fail(f"{owner} references missing test case {reference}")


def verify_command(owner: str, command: str) -> None:
    for token in command.split():
        if token.startswith("<"): continue
        if "/" in token or token.endswith((".py", ".sh")):
            if not (ROOT / token.removeprefix("./")).exists(): fail(f"{owner} command references missing path {token}")


def verify_evidence(owner: str, evidence: dict, required_coverage: bool, followups: set[int]) -> None:
    if not isinstance(evidence, dict) or set(evidence) - EVIDENCE_KEYS: fail(f"{owner} has unsupported evidence fields")
    if not evidence.get("tests") and not evidence.get("commands"): fail(f"{owner} has no executable evidence")
    coverage = evidence.get("coverage", None if required_coverage else "full")
    if coverage not in COVERAGE: fail(f"{owner} must declare coverage as full or partial")
    partial = coverage == "partial"
    if partial != bool(evidence.get("gap")): fail(f"{owner} must state a gap exactly when coverage is partial")
    if partial != bool(evidence.get("gap_issues")): fail(f"{owner} must assign gap_issues exactly when coverage is partial")
    unknown = set(evidence.get("gap_issues", [])) - followups
    if unknown: fail(f"{owner} assigns unknown follow-up issues {sorted(unknown)}")
    for reference in evidence.get("tests", []): verify_test(owner, reference)
    for command in evidence.get("commands", []): verify_command(owner, command)


def covers(requirement: dict, reference: str, kind: str) -> bool:
    listed = requirement.get(kind, [])
    if reference in listed: return True
    if kind == "tests": return reference.partition("#")[0] + "#*" in listed
    return False


def verify_relevance(owner: str, evidence: dict, relevant: set[str], requirements: dict) -> None:
    for kind in ("tests", "commands"):
        for reference in evidence.get(kind, []):
            if not any(covers(requirements[identifier], reference, kind) for identifier in relevant):
                fail(f"{owner} cites {reference}, which no requirement in the issue/journey intersection maps")


catalog = Catalog(ROOT)
index = load_json(DIRECTORY / "index.json")
if index.get("version") != 4: fail("index.json must declare version 4")
indexed = index.get("issues", [])
if not indexed or indexed != sorted(set(indexed)) or not all(isinstance(n, int) for n in indexed): fail("index issues must be unique and sorted")
base = os.environ.get("TRACEABILITY_BASE_SHA")
if base:
    result = subprocess.run(["git", "show", f"{base}:config/traceability/index.json"], cwd=ROOT, text=True, capture_output=True)
    if result.returncode: fail(f"cannot read traceability index from base {base}")
    previous = set(json.loads(result.stdout).get("issues", []))
    if removed := previous - set(indexed): fail(f"indexed issue history is append-only; removed {sorted(removed)}")

issue_files = {int(p.stem) for p in (DIRECTORY / "issues").glob("*.json") if p.stem.isdigit()}
snapshots = {int(p.stem) for p in (DIRECTORY / "issue-specs").glob("*.json") if p.stem.isdigit()}
records = {int(m.group(1)) for p in (ROOT / "docs/implementation").glob("ISSUE-*.md") if (m := re.match(r"ISSUE-(\d+)-", p.name))}
if set(indexed) != issue_files or set(indexed) != snapshots or set(indexed) != records:
    fail("index, issue files, issue snapshots, and implementation records must contain the same issue numbers")

followup_data = load_json(DIRECTORY / "follow-up-issues.json")
followups = {int(value) for value in followup_data.get("issues", {})}
catalog_data = load_json(DIRECTORY / "requirements.json")
if catalog_data.get("version") != 4: fail("requirements.json must declare version 4")
requirements = catalog_data.get("requirements", {})
mapped_cases = set()
for identifier, evidence in requirements.items():
    allowed = REQUIREMENT_ID.fullmatch(identifier) or identifier in catalog.detailed or identifier.startswith("P0-")
    if not allowed or not catalog.defined(identifier): fail(f"invalid or undefined requirement ID {identifier}")
    verify_evidence(identifier, evidence, True, followups)
    for ref in evidence.get("tests", []):
        path, _, method = ref.partition("#")
        mapped_cases.update(case for case in TEST_CASES if case == ref or (method == "*" and case.startswith(path + "#")))

for number in indexed:
    issue = load_json(DIRECTORY / "issues" / f"{number}.json")
    snapshot = load_json(DIRECTORY / "issue-specs" / f"{number}.json")
    if problems := validate_snapshot(snapshot, catalog): fail(f"issue #{number} snapshot: {'; '.join(problems)}")
    expected = snapshot["declarations"]
    actual = {"requirements": sorted(issue.get("requirements", [])), "use_cases": sorted(issue.get("use_cases", {})), "journeys": sorted(issue.get("journeys", {}))}
    if expected != actual: fail(f"issue #{number} evidence declarations differ from its issue snapshot")
    if set(issue) - ISSUE_KEYS or issue.get("issue") != number or not issue.get("title"): fail(f"issue #{number} has invalid fields")
    declared = set(issue["requirements"])
    if not declared or any(identifier not in requirements for identifier in declared): fail(f"issue #{number} declares a requirement without catalog evidence")
    for section, pattern in (("use_cases", USE_CASE_ID), ("journeys", JOURNEY_ID)):
        for identifier, evidence in issue.get(section, {}).items():
            if not pattern.fullmatch(identifier) or not catalog.defined(identifier): fail(f"issue #{number} has invalid {section} ID {identifier}")
            verify_evidence(f"issue #{number} {identifier}", evidence, False, followups)
            intersection = declared & catalog.declared_requirements(identifier)
            if not intersection: fail(f"issue #{number} {identifier} has no requirement intersection with the issue declaration")
            verify_relevance(f"issue #{number} {identifier}", evidence, intersection, requirements)
    if "evidence" in issue:
        verify_evidence(f"issue #{number} evidence", issue["evidence"], False, followups)
        verify_relevance(f"issue #{number} evidence", issue["evidence"], declared, requirements)

if orphaned := TEST_CASES - mapped_cases: fail(f"orphan executable test cases: {sorted(orphaned)}")
partial = sum(evidence["coverage"] == "partial" for evidence in requirements.values())
print(f"traceability=PASS version=4 issues={len(indexed)} requirements={len(requirements)} test_cases={len(TEST_CASES)} partial_requirements={partial}")
