"""Resolve identifiers using only the catalogs and documentation published with AgentWorks."""

import json
import os
import re
from pathlib import Path

ROOT = Path(os.environ.get("AGENTWORKS_REPOSITORY_ROOT", Path(__file__).resolve().parents[2]))
REQUIREMENT_ID = re.compile(r"REQ-(?:F|E|NFR|C)-\d{3}")
USE_CASE_ID = re.compile(r"UC-\d{3}")
JOURNEY_ID = re.compile(r"TC-\d{3}")
BUILTIN_FAMILIES = ("REQ-F", "REQ-E", "REQ-NFR", "REQ-C", "UC", "TC", "ADR")


def load_json(path: Path):
    return json.loads(path.read_text(encoding="utf-8"))


class Catalog:
    def __init__(self, root: Path = ROOT):
        self.root = root
        data = load_json(root / "config/traceability/requirements.json")
        requirements = data.get("requirements", {})
        self.requirements = {value for value in requirements if REQUIREMENT_ID.fullmatch(value)}
        self.families = BUILTIN_FAMILIES
        alternatives = "|".join(sorted((re.escape(value) for value in self.families), key=len, reverse=True))
        self.candidate = re.compile(rf"(?<![A-Za-z0-9_./-])((?:{alternatives})-\d{{3}})(?![0-9A-Za-z-])")
        self.range = re.compile(rf"((?:{alternatives})-)(\d{{3}})(?:\.\.|–)(?:(?:{alternatives})-)?(\d{{3}})")

    def _document(self, directory: str, prefix: str) -> bool:
        return any((self.root / directory).glob(f"{prefix}-*.md"))

    def defined(self, identifier: str) -> bool:
        if REQUIREMENT_ID.fullmatch(identifier):
            return identifier in self.requirements
        if USE_CASE_ID.fullmatch(identifier):
            return self._document("docs/use_cases", identifier)
        if JOURNEY_ID.fullmatch(identifier):
            return self._document("docs/test_cases", identifier)
        if re.fullmatch(r"ADR-\d{3}", identifier):
            return self._document("docs/adr", identifier)
        return False

    def references(self, text: str) -> set[str]:
        result = set(self.candidate.findall(text))
        for match in self.range.finditer(text):
            prefix, low, high = match.group(1), int(match.group(2)), int(match.group(3))
            result.update(f"{prefix}{number:03d}" for number in range(low, high + 1))
        return result

    def declared_requirements(self, identifier: str) -> set[str]:
        directory = "docs/use_cases" if identifier.startswith("UC-") else "docs/test_cases"
        label = "Related Requirements" if identifier.startswith("UC-") else "Requirements"
        document = next((self.root / directory).glob(f"{identifier}-*.md"))
        match = re.search(rf"^\| {label} \| (.+?) \|$", document.read_text(encoding="utf-8"), re.MULTILINE)
        if not match:
            raise ValueError(f"{document.name} declares no {label} row")
        return {item for item in self.references(match.group(1)) if REQUIREMENT_ID.fullmatch(item)}


def reference_scan_files(root: Path = ROOT) -> list[Path]:
    files = [root / "README.md"]
    files += list((root / "docs").rglob("*.md"))
    files += list((root / "config/traceability").rglob("*.json"))
    return sorted({path for path in files if path.is_file()})


def undefined_references(root: Path = ROOT) -> list[str]:
    catalog = Catalog(root)
    problems = []
    for path in reference_scan_files(root):
        relative = path.relative_to(root).as_posix()
        for identifier in sorted(catalog.references(path.read_text(encoding="utf-8"))):
            if not catalog.defined(identifier):
                problems.append(f"{relative} cites undefined repository ID {identifier}")
    return problems
