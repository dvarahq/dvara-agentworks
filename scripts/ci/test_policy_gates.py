#!/usr/bin/env python3
"""Exercise deliberate public-tree failures so policy gates cannot pass vacuously."""

import json
import os
import shutil
import subprocess
import tempfile
from pathlib import Path
from typing import Callable

ROOT = Path(__file__).resolve().parents[2]
TRACEABILITY = Path("config/traceability")
RESUMABLE_WORK = Path("config/lifecycle/resumable-work-categories.json")
COMMON_MIGRATIONS = Path("agentworks-storage-jdbc/src/main/resources/db/migration/common")
FRONTEND_ASSETS = Path("config/frontend-assets.json")
COMPATIBILITY_POLICY = Path("config/compatibility/policy.json")
COMPATIBILITY_VECTORS = Path("config/compatibility/golden-vectors.json")
VENDOR = Path("agentworks-server/src/main/resources/static/console/assets/vendor")
JOURNEY_POM = Path("agentworks-journey/pom.xml")
JOURNEY_SCRIPT = Path("agentworks-journey/src/main/java/com/dvarahq/agentworks/journey/extension/JourneyScript.java")
DEPENDENCY_CHECK_SCRIPT = Path("scripts/ci/run_dependency_check.sh")


def run(command: list[str], cwd: Path, env: dict[str, str] | None = None) -> subprocess.CompletedProcess[str]:
    return subprocess.run(command, cwd=cwd, env=env, text=True, capture_output=True, check=False)


def must(command: list[str], cwd: Path) -> subprocess.CompletedProcess[str]:
    result = run(command, cwd)
    if result.returncode != 0:
        raise SystemExit(f"policy-fixtures=FAIL: {' '.join(command)}: {result.stderr}")
    return result


def fixture_repository(repository: Path) -> None:
    must(["git", "init", "-q"], repository)
    for key, value in (("user.name", "Gate Fixture"), ("user.email", "fixture@example.invalid"),
                       ("maintenance.auto", "false"), ("gc.auto", "0")):
        must(["git", "config", key, value], repository)


def repository_copy(destination: Path) -> Path:
    files = must(["git", "ls-files", "--cached", "--others", "--exclude-standard"], ROOT).stdout.splitlines()
    for relative in files:
        source = ROOT / relative
        if source.is_file():
            target = destination / relative
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(source, target)
    return destination


def edit_json(root: Path, relative: Path, change: Callable[[dict], None]) -> None:
    path = root / relative
    data = json.loads(path.read_text(encoding="utf-8"))
    change(data)
    path.write_text(json.dumps(data), encoding="utf-8")


def package(data: dict, name: str) -> dict:
    return next(entry for entry in data["packages"] if entry["name"] == name)


def replace_in(root: Path, relative: Path, old: str, new: str) -> None:
    path = root / relative
    text = path.read_text(encoding="utf-8")
    if old not in text:
        raise SystemExit(f"policy-fixtures=FAIL: fixture anchor {old!r} not found in {relative}")
    path.write_text(text.replace(old, new, 1), encoding="utf-8")


def expect_rejection(name: str, script: str, expected: str, mutate: Callable[[Path], None]) -> None:
    with tempfile.TemporaryDirectory() as directory:
        root = repository_copy(Path(directory))
        mutate(root)
        environment = os.environ.copy()
        environment["AGENTWORKS_REPOSITORY_ROOT"] = str(root)
        result = run(["python3", str(ROOT / "scripts/ci" / script)], ROOT, environment)
        output = result.stdout + result.stderr
        if result.returncode == 0 or expected not in output:
            raise SystemExit(f"policy-fixtures=FAIL: {name} was not rejected as expected ({expected!r}): {output}")
    print(f"{name}=REJECTED")


def verify_unsigned_commit_is_rejected() -> None:
    with tempfile.TemporaryDirectory() as directory:
        repository = Path(directory)
        fixture_repository(repository)
        fixture = repository / "fixture.txt"
        fixture.write_text("base\n", encoding="utf-8")
        must(["git", "add", "fixture.txt"], repository)
        must(["git", "commit", "-q", "-s", "-m", "signed base"], repository)
        base = must(["git", "rev-parse", "HEAD"], repository).stdout.strip()
        fixture.write_text("unsigned\n", encoding="utf-8")
        must(["git", "commit", "-q", "-am", "unsigned fixture"], repository)
        unsigned = must(["git", "rev-parse", "HEAD"], repository).stdout.strip()
        result = run([str(ROOT / "scripts/ci/verify_dco.sh"), base, unsigned], repository)
        if result.returncode == 0 or "dco=FAIL" not in result.stderr + result.stdout:
            raise SystemExit("policy-fixtures=FAIL: DCO gate accepted an unsigned commit")
    print("unsigned_commit=REJECTED")


with tempfile.TemporaryDirectory() as scratch:
    baseline = repository_copy(Path(scratch))
    environment = os.environ.copy()
    environment["AGENTWORKS_REPOSITORY_ROOT"] = str(baseline)
    for script in ("verify_traceability.py", "verify_detailed_references.py", "verify_resumable_work.py",
                   "verify_security_gate.py", "verify_compatibility_policy.py", "verify_journey_isolation.py"):
        result = run(["python3", str(ROOT / "scripts/ci" / script)], ROOT, environment)
        if result.returncode != 0:
            raise SystemExit(f"policy-fixtures=FAIL: unmodified public copy fails {script}: "
                             f"{result.stdout}{result.stderr}")

fixtures = [
    ("public_traceability_wrong_scope", "verify_traceability.py", "public-release-evidence scope",
     lambda root: edit_json(root, TRACEABILITY / "requirements.json",
                            lambda data: data.update({"scope": "wrong"}))),
    ("public_traceability_missing_evidence", "verify_traceability.py", "has no public evidence",
     lambda root: edit_json(root, TRACEABILITY / "requirements.json",
                            lambda data: data["requirements"]["REQ-C-001"].update({"evidence": []}))),
    ("resumable_work_uncatalogued_cohort_table", "verify_resumable_work.py",
     "table cohort_claim carries cohort_id but is not catalogued",
     lambda root: (root / COMMON_MIGRATIONS / "V999__fixture.sql").write_text(
         "create table cohort_claim (\n    workspace_id uuid not null, cohort_id uuid not null\n);\n",
         encoding="utf-8")),
    ("resumable_work_unowned_planned_category", "verify_resumable_work.py", "needs an owner_issue",
     lambda root: edit_json(root, RESUMABLE_WORK,
                            lambda data: data["categories"]["SCHEDULE"].update({"owner_issue": 9999}))),
    ("resumable_work_unused_owner", "verify_resumable_work.py", "contains unused owners",
     lambda root: edit_json(root, RESUMABLE_WORK,
                            lambda data: data["planned_owner_issues"].append(9999))),
    ("frontend_asset_tampered", "verify_frontend_assets.py", "does not match its recorded sha256",
     lambda root: (root / VENDOR / "htmx-2.0.11/htmx.min.js").open("a", encoding="utf-8")
     .write("\n// tampered\n")),
    ("frontend_asset_unrecorded", "verify_frontend_assets.py", "is not recorded",
     lambda root: (root / VENDOR / "htmx-2.0.11/extra.js").write_text("// unrecorded\n", encoding="utf-8")),
    ("frontend_asset_disallowed_license", "verify_frontend_assets.py", "which the policy does not allow",
     lambda root: edit_json(root, FRONTEND_ASSETS,
                            lambda data: package(data, "bootstrap").update({"license": "GPL-3.0"}))),
    ("compatibility_java_drift", "verify_compatibility_policy.py", "Java policy 26 differs from pom 25",
     lambda root: edit_json(root, COMPATIBILITY_POLICY, lambda data: data["java"].update({"major": 26}))),
    ("compatibility_missing_rejection_vector", "verify_compatibility_policy.py",
     "golden vectors have no rejected runEvent example",
     lambda root: edit_json(root, COMPATIBILITY_VECTORS,
                            lambda data: data.update({"vectors": [entry for entry in data["vectors"]
                                                                  if entry["id"] != "event-unknown"]}))),
    ("security_gate_missing_threat_boundary", "verify_security_gate.py", "misses '## Main risks and controls'",
     lambda root: replace_in(root, Path("docs/security/threat-model.md"),
                             "## Main risks and controls", "## Risks")),
    ("security_gate_weakened_sca_threshold", "verify_security_gate.py", "misses '-DfailBuildOnCVSS=7'",
     lambda root: replace_in(root, DEPENDENCY_CHECK_SCRIPT, "-DfailBuildOnCVSS=7", "-DfailBuildOnCVSS=9")),
    ("journey_depends_on_internal_module", "verify_journey_isolation.py", "allowlisted extension points",
     lambda root: replace_in(root, JOURNEY_POM, "<dependencies>", "<dependencies><dependency><groupId>"
                             "com.dvarahq.agentworks</groupId><artifactId>agentworks-deployment</artifactId>"
                             "<scope>provided</scope></dependency>")),
    ("journey_imports_internal_code", "verify_journey_isolation.py", "not a public extension point",
     lambda root: replace_in(root, JOURNEY_SCRIPT, "import java.nio.file.Files;",
                             "import com.dvarahq.agentworks.runplane.execution.DurableRunEngine;\n"
                             "import java.nio.file.Files;")),
    ("journey_extension_resource", "verify_journey_isolation.py", "not an allowlisted extension resource",
     lambda root: (root / "agentworks-journey/src/main/resources/application.properties").write_text(
         "spring.main.banner-mode=off\n", encoding="utf-8")),
]
for fixture in fixtures:
    expect_rejection(*fixture)
verify_unsigned_commit_is_rejected()
supply_chain = run(["python3", str(ROOT / "scripts/ci/test_supply_chain.py")], ROOT)
if supply_chain.returncode:
    raise SystemExit("policy-fixtures=FAIL: public supply-chain fixtures failed: "
                     + supply_chain.stdout + supply_chain.stderr)
print(supply_chain.stdout, end="")
print(f"policy-fixtures=PASS mode=public fixtures={len(fixtures) + 17}")
