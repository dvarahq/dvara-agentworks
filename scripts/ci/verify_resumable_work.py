#!/usr/bin/env python3
"""Verify the resumable-work category catalog that cohort retirement depends on (issue #155).

Every migration table carrying a cohort_id column must be catalogued as a work ledger or as non-work evidence, so
new resumable work cannot bypass the retirement fence silently. Implemented categories must name a ledger table,
a fence function present in the migrations, and existing fence and retirement-race test methods; planned categories must name an owner.
"""

import json
import os
import re
from pathlib import Path

ROOT = Path(os.environ.get("AGENTWORKS_REPOSITORY_ROOT", Path(__file__).resolve().parents[2]))
CATALOG = Path("config/lifecycle/resumable-work-categories.json")
MIGRATIONS = Path("agentworks-storage-jdbc/src/main/resources/db")


def cohort_tables(sql: str) -> set[str]:
    tables = set()
    for name, body in re.findall(r"create\s+table\s+(?:if\s+not\s+exists\s+)?(\w+)\s*\((.*?)\n\);", sql, re.I | re.S):
        if re.search(r"(?:^|,|\()\s*cohort_id\s+\w+", body, re.I):
            tables.add(name.lower())
    for name in re.findall(r"alter\s+table\s+(?:if\s+exists\s+)?(\w+)\s+add\s+(?:column\s+)?(?:if\s+not\s+exists\s+)?cohort_id\b",
                           sql, re.I):
        tables.add(name.lower())
    return tables


def problems(root: Path) -> list[str]:
    catalog = json.loads((root / CATALOG).read_text(encoding="utf-8"))
    follow_up_file = root / "config/traceability/follow-up-issues.json"
    follow_ups = {int(number) for number in json.loads(follow_up_file.read_text(encoding="utf-8"))["issues"]}
    sql = "\n".join(path.read_text(encoding="utf-8") for path in sorted((root / MIGRATIONS).rglob("*.sql")))
    found, ledgers = [], set()
    for name, category in catalog["categories"].items():
        status = category.get("status")
        if status == "implemented":
            ledger = category.get("ledger_table", "")
            ledgers.add(ledger)
            if not re.search(rf"create\s+table\s+{re.escape(ledger)}\b", sql, re.I):
                found.append(f"{name}: ledger table {ledger!r} is not created by any migration")
            fence = category.get("fence", "")
            if not re.search(rf"create\s+(?:or\s+replace\s+)?function\s+{re.escape(fence)}\s*\(", sql, re.I):
                found.append(f"{name}: fence function {fence!r} is not defined by any migration")
            trigger = (rf"create\s+trigger\s+\w+\s+before\s+insert\s+or\s+update\s+or\s+delete\s+on\s+{re.escape(ledger)}"
                       rf"\s+for\s+each\s+row\s+execute\s+function\s+{re.escape(fence)}\s*\(")
            if not re.search(trigger, sql, re.I):
                found.append(f"{name}: fence {fence!r} is not a row trigger on every write to {ledger!r}")
            for kind in ("fence_tests", "retirement_race_tests"):
                if not category.get(kind):
                    found.append(f"{name}: an implemented category needs {kind}")
                for reference in category.get(kind, []):
                    path, _, method = reference.partition("#")
                    source = root / path
                    if not method or not source.is_file() or not re.search(rf"void\s+{re.escape(method)}\s*\(",
                                                                            source.read_text(encoding="utf-8")):
                        found.append(f"{name}: test {reference} does not exist")
        elif status == "planned":
            if category.get("owner_issue") not in follow_ups:
                found.append(f"{name}: planned category needs an owner_issue listed in follow-up-issues.json")
        else:
            found.append(f"{name}: status must be implemented or planned")
    allowed = ledgers | set(catalog.get("non_work_tables", {}))
    for table in sorted(cohort_tables(sql) - allowed):
        found.append(f"table {table} carries cohort_id but is not catalogued as resumable work or non-work evidence")
    return found


def main() -> None:
    if found := problems(ROOT):
        raise SystemExit("resumable-work=FAIL: " + "; ".join(found))
    print("resumable-work=PASS")


if __name__ == "__main__":
    main()
