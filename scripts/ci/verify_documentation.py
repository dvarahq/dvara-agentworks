#!/usr/bin/env python3
"""Fail closed on OSS documentation links, public-surface drift, and release-guide omissions."""

import argparse
import os
import re
import subprocess
import sys
from pathlib import Path

ROOT = Path(os.environ.get("AGENTWORKS_REPOSITORY_ROOT", Path(__file__).resolve().parents[2])).resolve()
MARKDOWN = [path for path in (ROOT / "README.md", ROOT / "LICENSE_POLICY.md",
                              ROOT / "legal/oss/CONTRIBUTING.md", ROOT / "CONTRIBUTING.md")
            if path.is_file()] + list((ROOT / "docs").rglob("*.md"))
REQUIRED = {
    "README.md": ("Dvara AgentWorks", "docs/getting-started.md", "Apache License 2.0"),
    "docs/getting-started.md": ("15 minutes", "paid model", "BUILD SUCCESS", "cleanup"),
    "docs/concepts.md": ("root", "continuation", "DRAINING", "PostgreSQL time", "idempotency"),
    "docs/agentspec.md": ("dvarahq.com/v1alpha1", "credential reference", "canonical JSON Schema"),
    "docs/integrations.md": ("Models", "tools", "MCP", "A2A", "skills"),
    "docs/reference/api.md": ("OpenAPI 3.1", "RFC 9457", "Last-Event-ID"),
    "docs/reference/configuration.md": ("relaxed binding", "AGENTWORKS_TOKEN", "agentworks.planes"),
    "docs/operations/production.md": ("TLS", "RLS", "readiness", "CPU/memory", "PITR"),
    "docs/operations/rc5.md": ("v1.0.0-rc5", "24 continuous hours", "168 hours", "public issue #35"),
    "docs/operations/upgrade-and-rollback.md": ("flyway validate", "mixed-version", "helm rollback", "forward recovery"),
    "docs/release/rc5-go-no-go.md": ("PENDING", "24 hours", "168 hours", "public issue #35"),
    "docs/security/operations.md": ("private vulnerability reporting", "redaction", "delegated", "Sandbox"),
    "docs/troubleshooting.md": ("IDEMPOTENCY_KEY_REUSED", "support bundle", "Migration checksum"),
    "docs/limitations.md": ("JTA",),
    "legal/oss/CONTRIBUTING.md": ("Developer Certificate of Origin", "git commit -s", "Apache License 2.0"),
}
LEGAL_REQUIRED = {
    "legal/oss/LICENSE": ("Apache License", "Version 2.0"),
    "legal/oss/NOTICE": ("Dvara AgentWorks Core", "htmx", "Bootstrap"),
    "legal/oss/CONTRIBUTING.md": ("Developer Certificate of Origin", "Apache License 2.0"),
    "legal/oss/DCO": ("Developer Certificate of Origin",),
}
PUBLIC_NAMESPACES = {
    "agents", "artifacts", "capabilities", "children", "client", "compatibility", "console", "context",
    "configuration", "credentials", "development", "gateway", "idempotency", "jdbc", "lifecycle", "node-id", "observability",
    "operations", "planes", "recovery", "release-evidence", "runs", "runtime", "sandbox", "security",
    "spring-ai", "tenancy",
}
METRIC_ONLY_NAMESPACES = {"compiled", "operation", "resource", "run", "shared"}


def problem(message: str) -> None:
    problems.append(message)


def slug(value: str) -> str:
    value = re.sub(r"<[^>]+>", "", value).strip().lower()
    value = re.sub(r"[^a-z0-9 _-]", "", value)
    return re.sub(r"[ _]+", "-", value).strip("-")


def anchors(path: Path) -> set[str]:
    found, counts = set(), {}
    for heading in re.findall(r"^#{1,6}\s+(.+?)\s*$", path.read_text(encoding="utf-8"), re.MULTILINE):
        base = slug(heading)
        number = counts.get(base, 0); counts[base] = number + 1
        found.add(base if number == 0 else f"{base}-{number}")
    return found


def verify_links() -> None:
    link = re.compile(r"(?<!!)\[[^\]]+\]\(([^)]+)\)")
    image = re.compile(r"!\[([^\]]*)\]\(([^)]+)\)")
    for path in MARKDOWN:
        text = path.read_text(encoding="utf-8")
        for alt, _ in image.findall(text):
            if not alt.strip(): problem(f"{path.relative_to(ROOT)} has an image without alternative text")
        for target in link.findall(text):
            target = target.split(" ", 1)[0].strip("<>")
            if any(character in target for character in "{}[]"): continue
            if target.startswith("http://"):
                if not re.match(r"http://(?:127\.0\.0\.1|localhost)(?::\d+)?(?:/|$)", target):
                    problem(f"{path.relative_to(ROOT)} has insecure external link: {target}")
                continue
            if target.startswith(("https://", "mailto:")): continue
            file_name, _, fragment = target.partition("#")
            destination = (path.parent / file_name).resolve() if file_name else path
            try: destination.relative_to(ROOT)
            except ValueError:
                problem(f"{path.relative_to(ROOT)} link escapes repository: {target}"); continue
            if not destination.exists():
                problem(f"{path.relative_to(ROOT)} has broken link: {target}"); continue
            if fragment and destination.suffix.lower() == ".md" and fragment not in anchors(destination):
                problem(f"{path.relative_to(ROOT)} has missing anchor: {target}")


def verify_snippets() -> None:
    for path in MARKDOWN:
        text = path.read_text(encoding="utf-8")
        for number, match in enumerate(re.finditer(r"^```(?:bash|sh)\s*\n(.*?)^```\s*$", text,
                                                   re.MULTILINE | re.DOTALL), start=1):
            result = subprocess.run(["bash", "-n"], input=match.group(1), text=True, capture_output=True)
            if result.returncode:
                detail = result.stderr.strip().splitlines()[-1] if result.stderr.strip() else "invalid shell syntax"
                problem(f"{path.relative_to(ROOT)} shell snippet {number} does not parse: {detail}")


def cli_commands() -> set[str]:
    sys.path.insert(0, str(ROOT / "cli"))
    from agentworks.main import parser  # noqa: PLC0415
    import argparse as argparse_module
    found = set()
    def walk(node, prefix=()):
        for action in node._actions:
            if isinstance(action, argparse_module._SubParsersAction):
                for name, child in action.choices.items():
                    if any(isinstance(item, argparse_module._SubParsersAction) for item in child._actions):
                        walk(child, (*prefix, name))
                    else: found.add(" ".join((*prefix, name)))
    walk(parser())
    return found


def verify_cli() -> None:
    text = (ROOT / "docs/cli.md").read_text(encoding="utf-8")
    match = re.search(r"agentworks-cli-command-index:start.*?```text\n(.*?)\n```.*?agentworks-cli-command-index:end",
                      text, re.DOTALL)
    if not match: problem("docs/cli.md has no checked command index"); return
    documented = {line.strip() for line in match.group(1).splitlines() if line.strip()}
    actual = cli_commands()
    if documented != actual:
        problem(f"CLI documentation drift: missing={sorted(actual-documented)} stale={sorted(documented-actual)}")


def verify_openapi() -> None:
    path = ROOT / "agentworks-server/src/main/resources/api/agentworks-api-v1.openapi.yaml"
    text = path.read_text(encoding="utf-8")
    operations = len(re.findall(r"^    (?:get|post|put|patch|delete):$", text, re.MULTILINE))
    for field in ("operationId", "summary", "x-agentworks-authorization"):
        count = len(re.findall(rf"^      {re.escape(field)}:", text, re.MULTILINE))
        if count != operations: problem(f"OpenAPI has {operations} operations but {count} {field} declarations")
    if operations < 1: problem("OpenAPI has no operations")


def verify_configuration() -> None:
    reference = (ROOT / "docs/reference/configuration.md").read_text(encoding="utf-8")
    found = set()
    for base in (ROOT / "agentworks-server/src/main/java", ROOT / "agentworks-spring-boot-starter/src/main/java"):
        for path in base.rglob("*.java"):
            text = path.read_text(encoding="utf-8")
            for key in re.findall(r'"(agentworks\.([a-z0-9-]+)(?:\.[a-z0-9_.-]+)?)"', text):
                found.add(key[1])
    found -= METRIC_ONLY_NAMESPACES
    unknown = found - PUBLIC_NAMESPACES
    missing = {name for name in found if f"agentworks.{name}" not in reference}
    if unknown: problem(f"uncatalogued public configuration namespaces: {sorted(unknown)}")
    if missing: problem(f"configuration namespaces missing from reference: {sorted(missing)}")


def verify_required_content() -> None:
    for relative, needles in REQUIRED.items():
        path = ROOT / relative
        if not path.is_file() and relative.startswith("legal/oss/"):
            path = ROOT / Path(relative).name
        if not path.is_file(): problem(f"missing required document {relative}"); continue
        text = path.read_text(encoding="utf-8")
        for needle in needles:
            if needle.casefold() not in text.casefold(): problem(f"{relative} does not document {needle}")
    limitations = (ROOT / "docs/limitations.md").read_text(encoding="utf-8")
    for label, alternatives in {
        "visual builder": ("visual builder", "Builder Studio"),
        "active-run migration": ("active-run migration", "paused-run migration"),
    }.items():
        if not any(value.casefold() in limitations.casefold() for value in alternatives):
            problem(f"docs/limitations.md does not document {label}")
    for relative, needles in LEGAL_REQUIRED.items():
        path = ROOT / relative
        if not path.is_file():
            path = ROOT / Path(relative).name
        if not path.is_file(): problem(f"missing required legal or attribution file {relative}"); continue
        text = path.read_text(encoding="utf-8")
        for needle in needles:
            if needle.casefold() not in text.casefold(): problem(f"{relative} does not contain {needle}")
    documentation_map = (ROOT / "docs/README.md").read_text(encoding="utf-8")
    for relative in LEGAL_REQUIRED:
        target = "../" + relative
        root_target = "../" + Path(relative).name
        if target not in documentation_map and root_target not in documentation_map:
            problem(f"docs/README.md does not link required legal or attribution file {relative}")


def documentation_problems(root: Path | None = None) -> list[str]:
    global ROOT, MARKDOWN, problems
    if root is not None:
        ROOT = root.resolve()
    MARKDOWN = [path for path in (ROOT / "README.md", ROOT / "LICENSE_POLICY.md",
                                  ROOT / "legal/oss/CONTRIBUTING.md", ROOT / "CONTRIBUTING.md")
                if path.is_file()] + list((ROOT / "docs").rglob("*.md"))
    problems = []
    verify_required_content(); verify_links(); verify_snippets(); verify_cli(); verify_openapi(); verify_configuration()
    return problems


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--root", type=Path, help="repository root (used by negative fixtures)")
    args = parser.parse_args()
    if found := documentation_problems(args.root):
        raise SystemExit("documentation=FAIL: " + "; ".join(found))
    openapi = ROOT / "agentworks-server/src/main/resources/api/agentworks-api-v1.openapi.yaml"
    operations = len(re.findall(r"^    (?:get|post|put|patch|delete):$", openapi.read_text(), re.MULTILINE))
    print(f"documentation=PASS markdown={len(MARKDOWN)} cli_commands={len(cli_commands())} "
          f"openapi_operations={operations}")


if __name__ == "__main__":
    main()
