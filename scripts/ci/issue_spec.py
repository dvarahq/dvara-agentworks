#!/usr/bin/env python3
"""Generate and validate offline snapshots of GitHub issue traceability sections."""

import argparse
import hashlib
import json
import re
from pathlib import Path

from traceability_catalog import Catalog, JOURNEY_ID, REQUIREMENT_ID, ROOT, USE_CASE_ID

SNAPSHOT_DIR = ROOT / "config/traceability/issue-specs"


def traceability_section(body: str) -> str:
    match = re.search(r"^## Traceability\s*$\n(.*?)(?=^## |\Z)", body, re.MULTILINE | re.DOTALL)
    if not match:
        raise ValueError("issue body has no ## Traceability section")
    return "## Traceability\n\n" + match.group(1).strip() + "\n"


def parse(section: str, catalog: Catalog) -> dict:
    refs = catalog.references(section)
    return {
        "requirements": sorted(value for value in refs if REQUIREMENT_ID.fullmatch(value) or value in catalog.detailed or value.startswith("P0-")),
        "use_cases": sorted(value for value in refs if USE_CASE_ID.fullmatch(value)),
        "journeys": sorted(value for value in refs if JOURNEY_ID.fullmatch(value)),
    }


def make_snapshot(issue: int, url: str, updated_at: str, body: str, catalog: Catalog) -> dict:
    section = traceability_section(body)
    return {
        "version": 1,
        "issue": issue,
        "url": url,
        "updated_at": updated_at,
        "traceability_sha256": hashlib.sha256(section.encode()).hexdigest(),
        "traceability_source": section,
        "declarations": parse(section, catalog),
    }


def validate(snapshot: dict, catalog: Catalog) -> list[str]:
    problems = []
    source = snapshot.get("traceability_source", "")
    digest = hashlib.sha256(source.encode()).hexdigest()
    if snapshot.get("version") != 1 or not isinstance(snapshot.get("issue"), int):
        problems.append("invalid snapshot identity")
    if digest != snapshot.get("traceability_sha256"):
        problems.append("traceability snapshot digest mismatch")
    try:
        parsed = parse(source, catalog)
    except Exception as error:
        problems.append(str(error))
    else:
        if parsed != snapshot.get("declarations"):
            problems.append("snapshot declarations do not match its source")
        for identifier in sum(parsed.values(), []):
            if not catalog.defined(identifier):
                problems.append(f"snapshot cites undefined ID {identifier}")
    return problems


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--issue", type=int)
    parser.add_argument("--url")
    parser.add_argument("--updated-at")
    parser.add_argument("--body", type=Path)
    parser.add_argument("--output", type=Path)
    parser.add_argument("--validate", action="store_true")
    args = parser.parse_args()
    catalog = Catalog(ROOT)
    if args.validate:
        problems = []
        for path in sorted(SNAPSHOT_DIR.glob("*.json")):
            problems += [f"{path.name}: {problem}" for problem in validate(json.loads(path.read_text()), catalog)]
        if problems:
            raise SystemExit("issue-spec=FAIL: " + "; ".join(problems))
        print(f"issue-spec=PASS snapshots={len(list(SNAPSHOT_DIR.glob('*.json')))}")
        return
    if not all((args.issue, args.url, args.updated_at, args.body, args.output)):
        parser.error("generation requires --issue, --url, --updated-at, --body, and --output")
    snapshot = make_snapshot(args.issue, args.url, args.updated_at, args.body.read_text(), catalog)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(snapshot, indent=2) + "\n")


if __name__ == "__main__":
    main()
