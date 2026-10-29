#!/usr/bin/env python3
"""Verify the versioned AgentWorks configuration catalogue and its startup enforcement."""

import json
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
SCHEMA = Path("agentworks-server/src/main/resources/META-INF/agentworks/configuration-schema.json")
REQUIRED_FIELDS = {
    "name", "type", "default", "constraint", "sensitive", "owner", "reload", "since", "status", "description"
}
TYPES = {
    "boolean", "credential-ref", "data-size", "decimal", "duration", "enum", "integer", "long", "path",
    "secret", "string", "string-list", "string-map", "uri",
}
STATUSES = {"active", "deprecated", "removed"}
SENSITIVE_PARTS = ("private-key", "hmac-keys", "credential-ref", "token-file", "reissue")


def relaxed(value: str) -> str:
    return re.sub(r"[^a-z0-9]", "", value.lower())


def rule_pattern(name: str) -> re.Pattern[str]:
    pieces: list[str] = []
    position = 0
    for match in re.finditer(r"<[^>]+>", name):
        pieces.append(re.escape(relaxed(name[position:match.start()])))
        pieces.append(r"[a-z0-9]+")
        position = match.end()
    pieces.append(re.escape(relaxed(name[position:])))
    return re.compile("^" + "".join(pieces) + "$")


def problems(root: Path = ROOT) -> list[str]:
    found: list[str] = []
    schema_path = root / SCHEMA
    if not schema_path.is_file():
        return [f"missing configuration schema {SCHEMA}"]
    try:
        schema = json.loads(schema_path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as error:
        return [f"invalid configuration schema: {error}"]
    if schema.get("schemaVersion") != 1:
        found.append("configuration schemaVersion must be 1")
    if schema.get("format") != "agentworks-configuration-catalog/v1":
        found.append("configuration schema format must be agentworks-configuration-catalog/v1")
    compatibility = schema.get("compatibility", {})
    if compatibility.get("unknownKeys") != "error" or compatibility.get("removedKeys") != "error":
        found.append("configuration compatibility must fail closed for unknown and removed keys")
    if compatibility.get("precedence") != [
        "command-line", "system-property", "environment-variable", "configuration-file", "profile-default"
    ]:
        found.append("configuration precedence changed without a schema-version change")
    entries = schema.get("properties")
    if not isinstance(entries, list) or not entries:
        return found + ["configuration schema properties must be a non-empty list"]

    names: set[str] = set()
    relaxed_names: set[str] = set()
    patterns: list[tuple[str, re.Pattern[str]]] = []
    for index, entry in enumerate(entries):
        label = f"configuration entry {index}"
        if not isinstance(entry, dict):
            found.append(f"{label} is not an object")
            continue
        missing = REQUIRED_FIELDS - entry.keys()
        if missing:
            found.append(f"{label} is missing {sorted(missing)}")
            continue
        name = entry["name"]
        label = name
        if not isinstance(name, str) or not name.startswith("agentworks."):
            found.append(f"{label} is not an AgentWorks property name")
            continue
        if name in names:
            found.append(f"duplicate configuration rule {name}")
        names.add(name)
        identity = relaxed(name)
        if "<" not in name and identity in relaxed_names:
            found.append(f"relaxed-binding duplicate configuration rule {name}")
        if "<" not in name:
            relaxed_names.add(identity)
        patterns.append((name, rule_pattern(name)))
        if entry["type"] not in TYPES:
            found.append(f"{label} has unsupported type {entry['type']}")
        if entry["status"] not in STATUSES:
            found.append(f"{label} has unsupported status {entry['status']}")
        if entry["reload"] not in {"restart", "dynamic"}:
            found.append(f"{label} has unsupported reload policy {entry['reload']}")
        if entry["status"] in {"deprecated", "removed"} and not entry.get("replacement"):
            found.append(f"{label} needs a replacement")
        secret_named = name.endswith(".password") or any(part in name for part in SENSITIVE_PARTS)
        if secret_named and not entry["sensitive"]:
            found.append(f"{label} must be sensitive")
        if entry["type"] in {"secret", "credential-ref", "path"} and not entry["sensitive"]:
            found.append(f"{label} type must be redacted")

    # Direct public property lookups may not bypass the catalogue. Prefix-composed families are covered by focused
    # Java tests and their explicit catalogue entries.
    java_roots = (root / "agentworks-server/src/main/java", root / "agentworks-spring-boot-starter/src/main/java")
    direct: set[str] = set()
    lookup = re.compile(r'(?:getProperty|bind|duration)\(\s*"(agentworks\.[a-zA-Z0-9_.-]+)"')
    conditional = re.compile(
        r'@ConditionalOnProperty\(prefix\s*=\s*"(agentworks\.[^"]+)"\s*,\s*name\s*=\s*"([^"]+)"'
    )
    for base in java_roots:
        for path in base.rglob("*.java"):
            text = path.read_text(encoding="utf-8")
            direct.update(lookup.findall(text))
            direct.update(f"{prefix}.{name}" for prefix, name in conditional.findall(text))
    for key in sorted(direct):
        matches = [name for name, pattern in patterns if pattern.fullmatch(relaxed(key))]
        exact = [name for name in matches if "<" not in name]
        dynamic_container = any(name.startswith(key + ".<") for name, _ in patterns)
        if not matches and not dynamic_container:
            found.append(f"public configuration lookup is not catalogued: {key}")
        elif len(exact) > 1 or (not exact and len(matches) > 1):
            found.append(f"public configuration lookup has ambiguous rules: {key} -> {matches}")

    factories = root / "agentworks-server/src/main/resources/META-INF/spring.factories"
    if not factories.is_file() or "AgentWorksConfigurationEnvironmentPostProcessor" not in factories.read_text(encoding="utf-8"):
        found.append("configuration validator is not registered before application-context creation")
    reference = root / "docs/reference/configuration.md"
    if not reference.is_file() or "configuration-schema.json" not in reference.read_text(encoding="utf-8"):
        found.append("configuration reference does not link the machine-readable schema")
    return found


if __name__ == "__main__":
    if issues := problems():
        raise SystemExit("configuration-contract=FAIL: " + "; ".join(issues))
    print("configuration-contract=PASS")
