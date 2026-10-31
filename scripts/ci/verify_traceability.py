#!/usr/bin/env python3
"""Verify the self-contained traceability catalog shipped in the public tree."""

import os
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from traceability_catalog import REQUIREMENT_ID, load_json  # noqa: E402

ROOT = Path(os.environ.get("AGENTWORKS_REPOSITORY_ROOT", Path(__file__).resolve().parents[2]))
CATALOG = ROOT / "config/traceability/requirements.json"


def fail(message: str) -> None:
    raise SystemExit(f"traceability=FAIL: {message}")


data = load_json(CATALOG)
if data.get("version") != 1 or data.get("scope") != "public-release-evidence":
    fail("public requirements catalog must declare version 1 and public-release-evidence scope")
requirements = data.get("requirements")
if not isinstance(requirements, dict) or not requirements:
    fail("public requirements catalog must contain requirements")
for identifier, entry in requirements.items():
    if not REQUIREMENT_ID.fullmatch(identifier):
        fail(f"invalid public requirement ID {identifier}")
    if not isinstance(entry, dict) or set(entry) != {"statement", "evidence"}:
        fail(f"{identifier} must contain exactly statement and evidence")
    if not isinstance(entry["statement"], str) or not entry["statement"].strip():
        fail(f"{identifier} has no statement")
    evidence = entry["evidence"]
    if not isinstance(evidence, list) or not evidence or not all(isinstance(value, str) for value in evidence):
        fail(f"{identifier} has no public evidence")
    for reference in evidence:
        token = reference.split()[0].removeprefix("./")
        if "/" in token and not (ROOT / token).exists():
            fail(f"{identifier} references missing public evidence {token}")
print(f"traceability=PASS scope=public requirements={len(requirements)}")
