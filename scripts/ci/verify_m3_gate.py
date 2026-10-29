#!/usr/bin/env python3
"""Keep the independent #82 M3 verdict bound to its recorded evidence."""

import os
import re
from pathlib import Path

ROOT = Path(os.environ.get("AGENTWORKS_REPOSITORY_ROOT", Path(__file__).resolve().parents[2]))
REPORT = ROOT / "docs/implementation/ISSUE-82-FEATURE-COMPLETE-GATE.md"
REQUIRED_IDS = {
    *(f"VERIFY-M3-{number:03d}" for number in range(1, 12)),
    "DATA-TRACE-M3-001",
    *(f"SCOPE-FREEZE-{number:03d}" for number in range(1, 4)),
}


def problems(root: Path = ROOT) -> list[str]:
    path = root / REPORT.relative_to(ROOT)
    if not path.is_file():
        return ["missing M3 gate report"]
    text = path.read_text(encoding="utf-8")
    found: list[str] = []
    verdicts = re.findall(r"\*\*Result: (PASS|PASS WITH REQUIRED FIXES|FAIL)\.\*\*", text)
    if verdicts != ["PASS"]:
        found.append(f"the M3 gate must record exactly one PASS verdict, found {verdicts}")
    for label in ("Candidate commit", "Candidate tree"):
        if not re.search(rf"\| {label} \| `[0-9a-f]{{40}}` \|", text):
            found.append(f"no valid {label.lower()} is recorded")
    for label in ("Server boot jar", "Journey extension jar"):
        if not re.search(rf"\| {label} \| `[^`]+` — SHA-256 `[0-9a-f]{{64}}` \|", text):
            found.append(f"no valid {label.lower()} identity is recorded")
    if "| BLOCKER | 0 |" not in text or "| HIGH | 0 |" not in text:
        found.append("a passing gate must record zero unresolved BLOCKER and zero unresolved HIGH findings")
    if "| M4 progression | **Authorized** |" not in text or "| Feature freeze | **Authorized** |" not in text:
        found.append("a passing gate must explicitly authorize feature freeze and M4 progression")
    missing = sorted(identifier for identifier in REQUIRED_IDS if f"| {identifier} |" not in text)
    if missing:
        found.append(f"the gate evidence matrix misses {missing}")
    finding_rows = [line for line in text.splitlines() if re.match(r"\| M3-[A-Z]-\d+ \|", line)]
    for line in finding_rows:
        columns = [column.strip() for column in line.strip("|").split("|")]
        if len(columns) < 7 or columns[1] not in {"BLOCKER", "HIGH", "MEDIUM", "LOW"} \
                or columns[5] not in {"RESOLVED", "OPEN"} or not re.search(r"#\d+", columns[4]):
            found.append(f"finding lacks severity, owner, or status: {line}")
        if len(columns) >= 6 and columns[1] in {"BLOCKER", "HIGH"} and columns[5] == "OPEN":
            found.append(f"passing gate retains an unresolved blocking finding: {line}")
    return found


if __name__ == "__main__":
    if failures := problems():
        raise SystemExit("m3-gate=FAIL: " + "; ".join(failures))
    print("m3-gate=PASS verdict=PASS blocker=0 high=0 feature_freeze=AUTHORIZED m4=AUTHORIZED")
