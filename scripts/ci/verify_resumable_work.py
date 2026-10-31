#!/usr/bin/env python3
"""Verify that every resumable-work category is fenced and has a declared owner."""

import json
import os
import re
from pathlib import Path

ROOT = Path(os.environ.get("AGENTWORKS_REPOSITORY_ROOT", Path(__file__).resolve().parents[2]))
CATALOG = Path("config/lifecycle/resumable-work-categories.json")
MIGRATIONS = Path("agentworks-storage-jdbc/src/main/resources/db")


def cohort_tables(sql: str) -> set[str]:
    tables = set()
    for name, body in re.findall(r"create\s+table\s+(?:if\s+not\s+exists\s+)?(\w+)\s*\((.*?)\n\);", sql,
                                 re.I | re.S):
        if re.search(r"(?:^|,|\()\s*cohort_id\s+\w+", body, re.I):
            tables.add(name.lower())
    for name in re.findall(
            r"alter\s+table\s+(?:if\s+exists\s+)?(\w+)\s+add\s+(?:column\s+)?(?:if\s+not\s+exists\s+)?cohort_id\b",
            sql, re.I):
        tables.add(name.lower())
    return tables


def problems(root: Path) -> list[str]:
    catalog = json.loads((root / CATALOG).read_text(encoding="utf-8"))
    declared_owners = catalog.get("planned_owner_issues")
    if (not isinstance(declared_owners, list)
            or not all(isinstance(number, int) and number > 0 for number in declared_owners)
            or declared_owners != sorted(set(declared_owners))):
        return ["planned_owner_issues must be a sorted, unique list of positive issue numbers"]
    declared_owners = set(declared_owners)
    sql = "\n".join(path.read_text(encoding="utf-8") for path in sorted((root / MIGRATIONS).rglob("*.sql")))
    found, ledgers, used_owners = [], set(), set()
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
                    if not method or not source.is_file() or not re.search(
                            rf"void\s+{re.escape(method)}\s*\(", source.read_text(encoding="utf-8")):
                        found.append(f"{name}: test {reference} does not exist")
        elif status == "planned":
            owner = category.get("owner_issue")
            if owner not in declared_owners:
                found.append(f"{name}: planned category needs an owner_issue declared in planned_owner_issues")
            else:
                used_owners.add(owner)
        else:
            found.append(f"{name}: status must be implemented or planned")
    allowed = ledgers | set(catalog.get("non_work_tables", {}))
    for table in sorted(cohort_tables(sql) - allowed):
        found.append(f"table {table} carries cohort_id but is not catalogued as resumable work or non-work evidence")
    if unused := declared_owners - used_owners:
        found.append(f"planned_owner_issues contains unused owners {sorted(unused)}")
    return found


def main() -> None:
    if found := problems(ROOT):
        raise SystemExit("resumable-work=FAIL: " + "; ".join(found))
    print("resumable-work=PASS")


if __name__ == "__main__":
    main()
