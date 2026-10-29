#!/usr/bin/env python3
"""Verify the MVP evidence report against the traceability catalog (issue #37, DATA-TRACE-002).

`docs/implementation/ISSUE-37-MVP-EVIDENCE.md` is what the #80 gate relies on, so it is checked, not trusted:

- its M1 requirement table lists exactly the requirements that the M1 issues declare, and each row's coverage, test
  and command counts, and gap owners equal `config/traceability/requirements.json`;
- every open finding and every recorded gap names an owner (an issue, or the gate that must decide it);
- the CI evidence of record names a 40-hex pull request head and tested tree, a CI run, and the 64-hex SHA-256 of the
  server and extension jars;
- the report states one verdict.
"""

import json
import os
import re
from pathlib import Path

ROOT = Path(os.environ.get("AGENTWORKS_REPOSITORY_ROOT", Path(__file__).resolve().parents[2]))
REPORT = Path("docs/implementation/ISSUE-37-MVP-EVIDENCE.md")
# The delivery issues of milestone M1 — OSS MVP Walking Skeleton that declare requirements (#80 is the gate).
M1_ISSUES = (29, 30, 31, 32, 33, 34, 35, 36, 37, 74, 149, 155, 183, 210, 211, 213)
VERDICTS = ("PASS WITH DOCUMENTED GAPS", "PASS", "FAIL")


def section(text: str, heading: str) -> str:
    match = re.search(rf"^#+ {re.escape(heading)}\s*$(.*?)(?=^#+ |\Z)", text, re.MULTILINE | re.DOTALL)
    return match.group(1) if match else ""


def rows(text: str) -> list[list[str]]:
    """The body rows of every Markdown table in `text`; header rows (those followed by a separator) are skipped."""
    found = []
    lines = text.splitlines()
    separator = re.compile(r"^\|[-:| ]+\|$")
    for index, line in enumerate(lines):
        if not line.startswith("|") or separator.match(line):
            continue
        if index + 1 < len(lines) and separator.match(lines[index + 1]):
            continue
        found.append([cell.strip() for cell in line.strip().strip("|").split("|")])
    return found


def problems(root: Path = ROOT) -> list[str]:
    path = root / REPORT
    if not path.is_file():
        return [f"{REPORT} is missing"]
    text = path.read_text(encoding="utf-8")
    catalog = json.loads((root / "config/traceability/requirements.json").read_text(encoding="utf-8"))["requirements"]
    declared = set()
    requirement_issues: dict[str, list[int]] = {}
    for number in M1_ISSUES:
        issue_requirements = json.loads((root / f"config/traceability/issues/{number}.json").read_text(
            encoding="utf-8"))["requirements"]
        declared.update(issue_requirements)
        for requirement in issue_requirements:
            requirement_issues.setdefault(requirement, []).append(number)
    found = []

    table_rows = [row for row in rows(section(text, "M1 requirement traceability")) if len(row) == 7]
    duplicate_requirements = sorted(identifier for identifier in {row[0] for row in table_rows}
                                    if sum(row[0] == identifier for row in table_rows) > 1)
    if duplicate_requirements:
        found.append(f"the M1 requirement table repeats rows {duplicate_requirements}")
    table = {row[0]: row for row in table_rows}
    if set(table) != declared:
        found.append(f"the M1 requirement table lists {sorted(set(table) - declared)} that no M1 issue declares and "
                     f"misses {sorted(declared - set(table))}")
    for requirement in sorted(set(table) & declared):
        _, issues, coverage, tests, commands, journeys, owners = table[requirement]
        evidence = catalog[requirement]
        expected_issues = ", ".join(f"#{number}" for number in requirement_issues[requirement])
        if issues != expected_issues:
            found.append(f"the M1 table row {requirement} lists issues {issues}, expected {expected_issues}")
        expected = (evidence["coverage"], str(len(evidence.get("tests", []))), str(len(evidence.get("commands", []))),
                    ", ".join(f"#{issue}" for issue in evidence.get("gap_issues", [])) or "—")
        if (coverage, tests, commands, owners) != expected:
            found.append(f"the M1 table row {requirement} says {(coverage, tests, commands, owners)}, the catalog "
                         f"{expected}")
        has_journey_evidence = any(test.startswith("agentworks-journey/") for test in evidence.get("tests", [])) or any(
            "-Pjourney" in command for command in evidence.get("commands", []))
        if has_journey_evidence == (journeys == "—"):
            found.append(f"the M1 table row {requirement} journeys column does not match its journey evidence")

    findings = section(text, "Open findings and follow-ups")
    for row in rows(findings):
        if len(row) == 4 and not re.match(r"^(?:#\d+|PR #\d+)(?:\b|,)", row[2]):
            found.append(f"open finding {row[0]} names no owner")
        if len(row) == 2 and not re.match(r"^#\d+", row[1]):
            found.append(f"recorded gap {row[0][:60]!r} names no owner")

    record = {row[0]: row[1] for row in rows(section(text, "CI evidence of record")) if len(row) == 2}
    expected_journeys = len(catalog["MVP-E2E-001"].get("tests", []))
    checks = {
        "Pull request head": r"`[0-9a-f]{40}`",
        "Journey report head": r"`[0-9a-f]{40}`",
        "Tested tree": r"`[0-9a-f]{40}`",
        "CI run": r"actions/runs/\d+",
        "Server boot jar": r"SHA-256 `[0-9a-f]{64}`",
        "Harness extension jar": r"SHA-256 `[0-9a-f]{64}`",
        "Result": rf"with {expected_journeys} of {expected_journeys} journeys PASS",
    }
    for field, pattern in checks.items():
        if not re.search(pattern, record.get(field, "")):
            found.append(f"the CI evidence of record has no valid {field}")
    head = re.search(r"`([0-9a-f]{40})`", record.get("Pull request head", ""))
    report_head = re.search(r"`([0-9a-f]{40})`", record.get("Journey report head", ""))
    if head and report_head and head.group(1) != report_head.group(1):
        found.append("the CI evidence pull request head does not match the journey report head")
    if not any(verdict in record.get("Result", "") for verdict in VERDICTS):
        found.append("the CI evidence of record states no report verdict")

    verdict = section(text, "Verdict")
    stated = [verdict_name for verdict_name in VERDICTS if f"**{verdict_name}.**" in verdict]
    if len(stated) != 1:
        found.append(f"the report must state exactly one verdict, found {stated}")
    elif stated[0] not in record.get("Result", ""):
        found.append(f"the CI evidence result does not match the report verdict {stated[0]}")
    ac6 = next((row for row in rows(section(text, "Acceptance criteria")) if row and row[0] == "6"), [])
    if not ac6 or "REQ-NFR-008 and DATA-TRACE-002" not in " ".join(ac6):
        found.append("acceptance criterion 6 must name both command-only entries REQ-NFR-008 and DATA-TRACE-002")
    return found


def main() -> None:
    if found := problems(ROOT):
        raise SystemExit("mvp-evidence=FAIL: " + "; ".join(found))
    print("mvp-evidence=PASS")


if __name__ == "__main__":
    main()
