#!/usr/bin/env python3
"""Reject detailed requirement IDs that no versioned repository catalog defines."""

import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from traceability_catalog import reference_scan_files, undefined_references  # noqa: E402

if problems := undefined_references():
    raise SystemExit("detailed-references=FAIL: " + "; ".join(problems))
print(f"detailed-references=PASS files={len(reference_scan_files())}")
