#!/usr/bin/env python3
"""Remove time/random fields from a CycloneDX dependency inventory."""

from __future__ import annotations

import argparse
import json
from pathlib import Path


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("input", type=Path)
    parser.add_argument("output", type=Path)
    parser.add_argument("--commit", required=True)
    args = parser.parse_args()
    data = json.loads(args.input.read_text(encoding="utf-8"))
    data.pop("serialNumber", None)
    metadata = data.setdefault("metadata", {})
    metadata.pop("timestamp", None)
    metadata["properties"] = sorted([
        item for item in metadata.get("properties", []) if item.get("name") != "agentworks:source-commit"
    ] + [{"name": "agentworks:source-commit", "value": args.commit}], key=lambda item: item["name"])
    data["components"] = sorted(data.get("components", []), key=lambda item: item.get("bom-ref", ""))
    data["dependencies"] = sorted(data.get("dependencies", []), key=lambda item: item.get("ref", ""))
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(data, indent=2, sort_keys=True) + "\n", encoding="utf-8")


if __name__ == "__main__":
    main()
