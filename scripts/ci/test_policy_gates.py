#!/usr/bin/env python3
"""Exercise deliberate failures so policy gates cannot pass vacuously."""

import base64
import hashlib
import io
import json
import tarfile
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
SPEC_TESTS = Path("agentworks-spec/src/test/java/com/dvarahq/agentworks/spec")
FRONTEND_ASSETS = Path("config/frontend-assets.json")
COMPATIBILITY_POLICY = Path("config/compatibility/policy.json")
COMPATIBILITY_VECTORS = Path("config/compatibility/golden-vectors.json")
VENDOR = Path("agentworks-server/src/main/resources/static/console/assets/vendor")
CONSOLE_LAYOUT = Path("agentworks-server/src/main/resources/templates/console/layout.html")

# The maintenance settings the fixtures turn off, as git calls on the real repository see them before any fixture
# runs: the fixtures set them in their own repositories only, so these must be unchanged at the end (#207). A setting
# made for the whole process (GIT_CONFIG_* in os.environ) or in the real repository's own configuration would change
# what git reports here, with "command line" or the repository's config file as its origin.
FIXTURE_SETTINGS = ("maintenance.auto", "gc.auto")


def root_git_settings() -> list[str]:
    found = subprocess.run(["git", "config", "--show-origin", "--get-regexp", r"^(maintenance\.auto|gc\.auto)$"],
                           cwd=ROOT, text=True, capture_output=True, check=False)
    return sorted(found.stdout.splitlines())


ROOT_GIT_SETTINGS = root_git_settings()


def git_environment() -> dict[str, str]:
    """Git process controls inherited by every child; none may leak out of a fixture."""
    return {name: value for name, value in os.environ.items() if name.startswith("GIT_")}


ROOT_GIT_ENVIRONMENT = git_environment()


def changed_git_environment() -> list[str]:
    current = git_environment()
    return sorted(name for name in ROOT_GIT_ENVIRONMENT.keys() | current.keys()
                  if ROOT_GIT_ENVIRONMENT.get(name) != current.get(name))


def run(command: list[str], cwd: Path, env: dict[str, str] | None = None) -> subprocess.CompletedProcess[str]:
    return subprocess.run(command, cwd=cwd, env=env, text=True, capture_output=True, check=False)


def must(command: list[str], cwd: Path) -> subprocess.CompletedProcess[str]:
    result = run(command, cwd)
    if result.returncode != 0:
        raise SystemExit(f"policy-fixtures=FAIL: {' '.join(command)}: {result.stderr}")
    return result


def fixture_repository(repository: Path) -> None:
    """Initialize a throwaway fixture repository with a fixture identity and no automatic maintenance.

    A fixture's `git commit` may start automatic maintenance detached in the background (the default of recent Git),
    which still writes into the fixture's .git while its temporary directory is removed ("Directory not empty"). The
    settings are the fixture repository's own configuration, so every git process run in it, including a gate's, reads
    them, and no git process run in the real repository does (#207).
    """
    must(["git", "init", "-q"], repository)
    for key, value in (("user.name", "Gate Fixture"), ("user.email", "fixture@example.invalid"),
                       ("maintenance.auto", "false"), ("gc.auto", "0")):
        must(["git", "config", key, value], repository)


def repository_copy(destination: Path) -> Path:
    """Copy tracked and new unignored files so fixtures mutate an isolated tree, never the checkout."""
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
    """A frontend asset package by name, so fixtures do not depend on the manifest's order."""
    return next(entry for entry in data["packages"] if entry["name"] == name)


def asset(data: dict, name: str, path: str) -> dict:
    return next(entry for entry in package(data, name)["files"] if entry["path"] == path)


def local_tarballs(root: Path) -> dict[str, str]:
    """Builds each package's tarball from its vendored files and records its integrity, so the tarball comparison
    runs offline; the comparison, not the download, is what the fixtures exercise."""
    directory = root / "asset-tarballs"
    directory.mkdir()
    manifest = json.loads((root / FRONTEND_ASSETS).read_text(encoding="utf-8"))
    for entry in manifest["packages"]:
        buffer = io.BytesIO()
        with tarfile.open(fileobj=buffer, mode="w:gz") as archive:
            for file in entry["files"]:
                content = (root / manifest["root"] / entry["directory"] / file["path"]).read_bytes()
                info = tarfile.TarInfo(file["from"])
                info.size = len(content)
                archive.addfile(info, io.BytesIO(content))
        data = buffer.getvalue()
        (directory / f"{entry['name']}-{entry['version']}.tgz").write_bytes(data)
        entry["sourceIntegrity"] = "sha512-" + base64.b64encode(hashlib.sha512(data).digest()).decode()
    (root / FRONTEND_ASSETS).write_text(json.dumps(manifest), encoding="utf-8")
    return {"AGENTWORKS_ASSET_TARBALLS": str(directory)}


def forge_vendored_file(root: Path) -> dict[str, str]:
    """Changes a vendored file and records its new digest, so that only the tarball comparison can notice."""
    environment = local_tarballs(root)
    path = root / VENDOR / "htmx-2.0.11/htmx.min.js"
    path.write_bytes(path.read_bytes() + b"\n// forged\n")
    digest = hashlib.sha256(path.read_bytes()).hexdigest()
    edit_json(root, FRONTEND_ASSETS, lambda data: asset(data, "htmx.org", "htmx.min.js").update({"sha256": digest}))
    return environment


def record_missing_tarball_member(root: Path) -> dict[str, str]:
    """Records a vendored file as coming from a tarball member that does not exist."""
    environment = local_tarballs(root)
    edit_json(root, FRONTEND_ASSETS,
              lambda data: asset(data, "htmx.org", "htmx.min.js").update({"from": "package/dist/missing.js"}))
    return environment


def record_wrong_tarball_integrity(root: Path) -> dict[str, str]:
    """Records an integrity that the package's tarball does not have."""
    environment = local_tarballs(root)
    edit_json(root, FRONTEND_ASSETS, lambda data: package(data, "htmx.org").update(
        {"sourceIntegrity": "sha512-" + base64.b64encode(hashlib.sha512(b"another tarball").digest()).decode()}))
    return environment


def expect_rejection(name: str, script: str, expected: str, mutate: Callable[[Path], dict[str, str] | None]) -> None:
    with tempfile.TemporaryDirectory() as directory:
        root = repository_copy(Path(directory))
        extra = mutate(root)
        environment = os.environ.copy()
        environment["AGENTWORKS_REPOSITORY_ROOT"] = str(root)
        if isinstance(extra, dict):
            environment.update(extra)
        result = run(["python3", str(ROOT / "scripts/ci" / script)], ROOT, environment)
        output = result.stdout + result.stderr
        if result.returncode == 0 or expected not in output:
            raise SystemExit(f"policy-fixtures=FAIL: {name} was not rejected as expected ({expected!r}): {output}")
    print(f"{name}=REJECTED")


JOURNEY_POM = Path("agentworks-journey/pom.xml")
JOURNEY_SCRIPT = Path("agentworks-journey/src/main/java/com/dvarahq/agentworks/journey/extension/JourneyScript.java")
MVP_EVIDENCE = Path("docs/implementation/ISSUE-37-MVP-EVIDENCE.md")
M1_GATE = Path("docs/implementation/ISSUE-80-MVP-GATE.md")
M2_GATE = Path("docs/implementation/ISSUE-81-BETA-GATE.md")
M3_GATE = Path("docs/implementation/ISSUE-82-FEATURE-COMPLETE-GATE.md")
THREAT_MODEL = Path("docs/implementation/OSS-SECURITY-GATE.md")
DEPENDENCY_CHECK_SCRIPT = Path("scripts/ci/run_dependency_check.sh")
DEPENDENCY_CHECK_SUPPRESSIONS = Path("config/dependency-check-suppressions.xml")
ROOT_POM = Path("pom.xml")


def replace_in(root: Path, relative: Path, old: str, new: str) -> None:
    """Replaces text in a fixture file, failing loudly when the anchor is gone so the fixture cannot pass vacuously."""
    path = root / relative
    text = path.read_text(encoding="utf-8")
    if old not in text:
        raise SystemExit(f"policy-fixtures=FAIL: fixture anchor {old!r} not found in {relative}")
    path.write_text(text.replace(old, new, 1), encoding="utf-8")


def drop_line(root: Path, relative: Path, prefix: str) -> None:
    path = root / relative
    lines = path.read_text(encoding="utf-8").splitlines(keepends=True)
    kept = [line for line in lines if not line.startswith(prefix)]
    if len(kept) == len(lines):
        raise SystemExit(f"policy-fixtures=FAIL: fixture line {prefix!r} not found in {relative}")
    path.write_text("".join(kept), encoding="utf-8")


def add_test(requirement: str, reference: str) -> Callable[[Path], None]:
    return lambda root: edit_json(root, TRACEABILITY / "requirements.json",
                                  lambda data: data["requirements"][requirement]["tests"].append(reference))


with tempfile.TemporaryDirectory() as scratch:
    baseline = repository_copy(Path(scratch))
    environment = os.environ.copy()
    environment["AGENTWORKS_REPOSITORY_ROOT"] = str(baseline)
    for script in ("verify_traceability.py", "verify_detailed_references.py", "verify_resumable_work.py",
                   "verify_security_gate.py", "verify_compatibility_policy.py"):
        result = run(["python3", str(ROOT / "scripts/ci" / script)], ROOT, environment)
        if result.returncode != 0:
            raise SystemExit(f"policy-fixtures=FAIL: unmodified copy fails {script}: {result.stdout}{result.stderr}")

fixtures = [
    ("traceability_missing_indexed_issue", "verify_traceability.py", "must contain the same issue numbers",
     lambda root: edit_json(root, TRACEABILITY / "index.json", lambda data: data["issues"].remove(30))),
    ("traceability_missing_issue_file", "verify_traceability.py", "must contain the same issue numbers",
     lambda root: (root / TRACEABILITY / "issues/30.json").unlink()),
    ("traceability_missing_requirement", "verify_traceability.py", "without catalog evidence",
     lambda root: edit_json(root, TRACEABILITY / "requirements.json",
                            lambda data: data["requirements"].pop("REQ-F-019"))),
    ("traceability_undefined_requirement_id", "verify_traceability.py", "invalid or undefined requirement ID",
     lambda root: edit_json(root, TRACEABILITY / "requirements.json",
                            lambda data: data["requirements"].update({"REQ-F-999": {"coverage": "full",
                                                                                     "commands": ["./mvnw"]}}))),
    ("traceability_false_use_case_mapping", "verify_traceability.py", "issue/journey intersection maps",
     lambda root: edit_json(root, TRACEABILITY / "issues/30.json", lambda data: data["use_cases"]["UC-001"]
                            ["tests"].append("agentworks-runtime/src/test/java/com/dvarahq/agentworks/agent/remote/ExternalRunnerClientTest.java#*"))),
    ("traceability_missing_use_case_mapping", "verify_traceability.py", "UC-001 has no executable evidence",
     lambda root: edit_json(root, TRACEABILITY / "issues/30.json",
                            lambda data: data["use_cases"].update({"UC-001": {}}))),
    ("traceability_missing_journey_evidence", "verify_traceability.py", "TC-002 has no executable evidence",
     lambda root: edit_json(root, TRACEABILITY / "issues/28.json",
                            lambda data: data["journeys"].update({"TC-002": {"tests": []}}))),
    ("traceability_partial_without_gap", "verify_traceability.py", "must state a gap",
     lambda root: edit_json(root, TRACEABILITY / "requirements.json",
                            lambda data: data["requirements"]["REQ-NFR-004"].pop("gap"))),
    ("traceability_partial_without_owner", "verify_traceability.py", "must assign gap_issues",
     lambda root: edit_json(root, TRACEABILITY / "requirements.json",
                            lambda data: data["requirements"]["REQ-NFR-004"].pop("gap_issues"))),
    ("traceability_unknown_gap_owner", "verify_traceability.py", "unknown follow-up issues",
     lambda root: edit_json(root, TRACEABILITY / "requirements.json",
                            lambda data: data["requirements"]["REQ-NFR-004"]["gap_issues"].append(9999))),
    ("traceability_nonexistent_test", "verify_traceability.py", "not an existing executable test",
     add_test("REQ-F-001", str(SPEC_TESTS / "DoesNotExistTest.java") + "#*")),
    ("traceability_implicit_whole_class", "verify_traceability.py", "explicit #* whole-class wildcard",
     add_test("REQ-F-034", str(SPEC_TESTS / "AgentSpecFuzzTest.java"))),
    ("traceability_nonexistent_test_method", "verify_traceability.py", "missing test case",
     add_test("REQ-F-001", str(SPEC_TESTS / "AgentSpecFuzzTest.java") + "#doesNotExist")),
    ("traceability_nonexistent_command", "verify_traceability.py", "missing path",
     lambda root: edit_json(root, TRACEABILITY / "requirements.json", lambda data: data["requirements"]
                            ["DATA-TRACE-001"]["commands"].append("python3 scripts/ci/missing.py"))),
    ("traceability_orphan_test", "verify_traceability.py", "orphan executable test cases",
     lambda root: (root / SPEC_TESTS / "OrphanFixtureTest.java").write_text(
         "package com.dvarahq.agentworks.spec;\nclass OrphanFixtureTest {}\n", encoding="utf-8")),
    ("traceability_snapshot_digest", "verify_traceability.py", "snapshot digest mismatch",
     lambda root: edit_json(root, TRACEABILITY / "issue-specs/30.json",
                            lambda data: data.update({"traceability_sha256": "0" * 64}))),
    ("traceability_snapshot_declaration_drift", "verify_traceability.py", "differ from its issue snapshot",
     lambda root: edit_json(root, TRACEABILITY / "issues/30.json",
                            lambda data: data["requirements"].remove("REQ-F-034"))),
    ("resumable_work_uncatalogued_cohort_table", "verify_resumable_work.py",
     "table cohort_claim carries cohort_id but is not catalogued",
     lambda root: (root / COMMON_MIGRATIONS / "V999__fixture.sql").write_text(
         "create table cohort_claim (\n    workspace_id uuid not null, cohort_id uuid not null\n);\n", encoding="utf-8")),
    ("resumable_work_uncatalogued_added_column", "verify_resumable_work.py",
     "table agent_inbox carries cohort_id but is not catalogued",
     lambda root: (root / COMMON_MIGRATIONS / "V999__fixture.sql").write_text(
         "create table agent_inbox (\n    id uuid not null\n);\nalter table agent_inbox add cohort_id varchar(36);\n",
         encoding="utf-8")),
    ("resumable_work_missing_fence_test", "verify_resumable_work.py", "does not exist",
     lambda root: edit_json(root, RESUMABLE_WORK, lambda data: data["categories"]["RUN"]["fence_tests"]
                            .append(data["categories"]["RUN"]["fence_tests"][0] + "Missing"))),
    ("resumable_work_without_retirement_race", "verify_resumable_work.py", "needs retirement_race_tests",
     lambda root: edit_json(root, RESUMABLE_WORK,
                            lambda data: data["categories"]["RUN"].update({"retirement_race_tests": []}))),
    ("resumable_work_missing_fence", "verify_resumable_work.py", "is not defined by any migration",
     lambda root: edit_json(root, RESUMABLE_WORK,
                            lambda data: data["categories"]["RUN"].update({"fence": "agentworks_fence_missing"}))),
    ("resumable_work_unfenced_ledger", "verify_resumable_work.py", "is not a row trigger on every write",
     lambda root: edit_json(root, RESUMABLE_WORK,
                            lambda data: data["categories"]["RUN"].update({"ledger_table": "runtime_cohort"}))),
    ("resumable_work_unowned_planned_category", "verify_resumable_work.py", "needs an owner_issue",
     lambda root: edit_json(root, RESUMABLE_WORK,
                            lambda data: data["categories"]["SCHEDULE"].update({"owner_issue": 9999}))),
    ("frontend_asset_tampered", "verify_frontend_assets.py", "does not match its recorded sha256",
     lambda root: (root / VENDOR / "htmx-2.0.11/htmx.min.js").open("a", encoding="utf-8").write("\n// tampered\n")),
    ("frontend_asset_unrecorded", "verify_frontend_assets.py", "is not recorded",
     lambda root: (root / VENDOR / "htmx-2.0.11/extra.js").write_text("// unrecorded\n", encoding="utf-8")),
    ("frontend_asset_without_license", "verify_frontend_assets.py", "does not ship its license text",
     lambda root: edit_json(root, FRONTEND_ASSETS, lambda data: package(data, "htmx.org").update(
         {"files": [entry for entry in package(data, "htmx.org")["files"] if entry["path"] != "LICENSE"]}))),
    ("frontend_asset_disallowed_license", "verify_frontend_assets.py", "which the policy does not allow",
     lambda root: edit_json(root, FRONTEND_ASSETS,
                            lambda data: package(data, "bootstrap").update({"license": "GPL-3.0"}))),
    ("frontend_asset_unpinned_integrity", "verify_frontend_assets.py", "does not pin the recorded integrity",
     lambda root: (root / CONSOLE_LAYOUT).write_text((root / CONSOLE_LAYOUT).read_text(encoding="utf-8")
                                                     .replace("sha384-2Oat", "sha384-XXXX"), encoding="utf-8")),
    ("frontend_asset_differs_from_its_tarball", "verify_frontend_asset_sources.py", "differs from",
     forge_vendored_file),
    ("frontend_asset_missing_from_its_tarball", "verify_frontend_asset_sources.py", "is not in the htmx.org",
     record_missing_tarball_member),
    ("frontend_asset_tarball_integrity_mismatch", "verify_frontend_asset_sources.py",
     "does not match its recorded sourceIntegrity", record_wrong_tarball_integrity),
    ("frontend_asset_from_cdn_by_expression", "verify_frontend_assets.py", "loads an asset from another origin",
     lambda root: (root / CONSOLE_LAYOUT).write_text((root / CONSOLE_LAYOUT).read_text(encoding="utf-8").replace(
         "</head>", '<link rel="stylesheet" th:href="@{//cdn.example/x.css}"></head>', 1), encoding="utf-8")),
    ("frontend_asset_from_cdn", "verify_frontend_assets.py", "loads an asset from another origin",
     lambda root: (root / CONSOLE_LAYOUT).write_text((root / CONSOLE_LAYOUT).read_text(encoding="utf-8").replace(
         "</head>", '<script src="https://cdn.example/htmx.js"></script></head>', 1), encoding="utf-8")),
    ("undefined_detailed_reference", "verify_detailed_references.py", "undefined repository ID TEST-CI-999",
     lambda root: (root / "docs/implementation/ISSUE-30-AGENT-SPEC.md").open("a", encoding="utf-8")
     .write("\nSee TEST-CI-999.\n")),
    ("undefined_reference_in_traceability_data", "verify_detailed_references.py", "undefined repository ID DATA-TRACE-999",
     lambda root: edit_json(root, TRACEABILITY / "issues/30.json",
                            lambda data: data.update({"title": data["title"] + " (DATA-TRACE-999)"}))),
    # #64: the compatibility policy must stay aligned with source, all boundary jobs, and rejection vectors.
    ("compatibility_java_drift", "verify_compatibility_policy.py", "Java policy 26 differs from pom 25",
     lambda root: edit_json(root, COMPATIBILITY_POLICY, lambda data: data["java"].update({"major": 26}))),
    ("compatibility_missing_ci_boundary", "verify_compatibility_policy.py",
     "CI compatibility matrix is missing Compatibility (browser)",
     lambda root: replace_in(root, Path(".github/workflows/qualification.yml"),
                             "Compatibility (browser)", "Browser smoke")),
    ("compatibility_missing_rejection_vector", "verify_compatibility_policy.py",
     "golden vectors have no rejected runEvent example",
     lambda root: edit_json(root, COMPATIBILITY_VECTORS,
                            lambda data: data.update({"vectors": [entry for entry in data["vectors"]
                                                                  if entry["id"] != "event-unknown"]}))),
    # #60: the security policy and high-severity SCA threshold are executable release gates.
    ("security_gate_missing_threat_boundary", "verify_security_gate.py", "misses '## Authorization inventory'",
     lambda root: replace_in(root, THREAT_MODEL, "## Authorization inventory", "## Surface inventory")),
    ("security_gate_weakened_sca_threshold", "verify_security_gate.py", "misses '-DfailBuildOnCVSS=7'",
     lambda root: replace_in(root, DEPENDENCY_CHECK_SCRIPT, "-DfailBuildOnCVSS=7", "-DfailBuildOnCVSS=9")),
    ("security_gate_vulnerable_kotlin", "verify_security_gate.py", "misses '<kotlin.version>2.4.20</kotlin.version>'",
     lambda root: replace_in(root, ROOT_POM, "<kotlin.version>2.4.20</kotlin.version>",
                             "<kotlin.version>2.3.21</kotlin.version>")),
    ("security_gate_kotlin_suppression", "verify_security_gate.py", "still contains forbidden 'CVE-2026-53914'",
     lambda root: replace_in(root, DEPENDENCY_CHECK_SUPPRESSIONS, "</suppressions>",
                             "  <suppress><cve>CVE-2026-53914</cve></suppress>\n</suppressions>")),
    # #37: the journey harness may reach the product only through allowlisted public extension points.
    ("journey_depends_on_internal_module", "verify_journey_isolation.py", "allowlisted extension points",
     lambda root: replace_in(root, JOURNEY_POM, "<dependencies>", "<dependencies><dependency><groupId>"
                             "com.dvarahq.agentworks</groupId><artifactId>agentworks-deployment</artifactId>"
                             "<scope>provided</scope></dependency>")),
    ("journey_imports_internal_code", "verify_journey_isolation.py", "not a public extension point",
     lambda root: replace_in(root, JOURNEY_SCRIPT, "import java.nio.file.Files;",
                             "import com.dvarahq.agentworks.runplane.execution.DurableRunEngine;\n"
                             "import java.nio.file.Files;")),
    ("journey_wildcard_imports_internal_code", "verify_journey_isolation.py", "not a public extension point",
     lambda root: replace_in(root, JOURNEY_SCRIPT, "import java.nio.file.Files;",
                             "import com.dvarahq.agentworks.runplane.execution.*;\n"
                             "import java.nio.file.Files;")),
    ("journey_extension_resource", "verify_journey_isolation.py", "not an allowlisted extension resource",
     lambda root: (root / "agentworks-journey/src/main/resources/application.properties").write_text(
         "spring.main.banner-mode=off\n", encoding="utf-8")),
    ("journey_runtime_scoped_dependency", "verify_journey_isolation.py", "must not have a compile dependency",
     lambda root: replace_in(root, JOURNEY_POM, "<artifactId>spring-ai-model</artifactId><scope>provided</scope>",
                             "<artifactId>spring-ai-model</artifactId>")),
    # #37: the MVP evidence report is checked against the catalog, not trusted.
    ("mvp_evidence_missing_requirement", "verify_mvp_evidence.py", "misses ['REQ-F-001']",
     lambda root: drop_line(root, MVP_EVIDENCE, "| REQ-F-001 |")),
    ("mvp_evidence_wrong_count", "verify_mvp_evidence.py", "the M1 table row REQ-F-034",
     lambda root: replace_in(root, MVP_EVIDENCE, "| REQ-F-034 | #30 | full | 7 |", "| REQ-F-034 | #30 | full | 8 |")),
    ("mvp_evidence_wrong_issue_list", "verify_mvp_evidence.py", "lists issues",
     lambda root: replace_in(root, MVP_EVIDENCE,
                             "| DATA-TRACE-002 | #37, #211 |", "| DATA-TRACE-002 | #37 |")),
    ("mvp_evidence_unowned_finding", "verify_mvp_evidence.py", "names no owner",
     lambda root: replace_in(root, MVP_EVIDENCE, "| #80 | Gate |", "| #80 | Gate | — | — |\n| #999 | Gate |")),
    ("mvp_evidence_missing_identity", "verify_mvp_evidence.py", "no valid Server boot jar",
     lambda root: replace_in(root, MVP_EVIDENCE, "| Server boot jar |", "| Server boot jar (unrecorded) |")),
    ("mvp_evidence_duplicate_row", "verify_mvp_evidence.py", "repeats rows",
     lambda root: replace_in(root, MVP_EVIDENCE, "| REQ-F-001 |", "| REQ-F-001 | #duplicate | full | 1 | 0 | — | — |\n| REQ-F-001 |")),
    ("mvp_evidence_wrong_journey_count", "verify_mvp_evidence.py", "no valid Result",
     lambda root: replace_in(root, MVP_EVIDENCE, "with 12 of 12 journeys PASS", "with 11 of 11 journeys PASS")),
    ("mvp_evidence_head_disagrees", "verify_mvp_evidence.py", "does not match the journey report head",
     lambda root: replace_in(root, MVP_EVIDENCE,
                             "| Journey report head | `ceada6a8888d8c9a973643e093bece4533839754`",
                             "| Journey report head | `0000000000000000000000000000000000000000`")),
    ("mvp_evidence_journey_column_omitted", "verify_mvp_evidence.py", "journeys column",
     lambda root: replace_in(root, MVP_EVIDENCE,
                             "| MVP-E2E-001 | #37, #211, #213 | full | 12 | 0 | all twelve journeys |",
                             "| MVP-E2E-001 | #37, #211, #213 | full | 12 | 0 | — |")),
    ("mvp_evidence_verdict_disagrees", "verify_mvp_evidence.py", "does not match the report verdict",
     lambda root: replace_in(root, MVP_EVIDENCE, "`report.json` verdict `PASS WITH DOCUMENTED GAPS`",
                             "`report.json` verdict `FAIL`")),
    ("mvp_evidence_ac6_omits_command_only", "verify_mvp_evidence.py", "must name both command-only entries",
     lambda root: replace_in(root, MVP_EVIDENCE, "REQ-NFR-008 and DATA-TRACE-002", "REQ-NFR-008")),
    # #80: the independent verdict must stay bound to identities, severities, owners, and progression.
    ("m1_gate_verdict_changed", "verify_m1_gate.py", "exactly one PASS verdict",
     lambda root: replace_in(root, M1_GATE, "**Result: PASS.**", "**Result: FAIL.**")),
    ("m1_gate_artifact_identity_missing", "verify_m1_gate.py", "server boot jar identity",
     lambda root: replace_in(root, M1_GATE,
                             "c59e45167278cf05bf60c9d0ae931eaaf29c2c2b487eb6e5ba359b16d3c148ac",
                             "unrecorded")),
    ("m1_gate_unowned_finding", "verify_m1_gate.py", "finding names no non-blocking severity and issue owner",
     lambda root: replace_in(root, M1_GATE, "| #213: execute", "| execute")),
    ("m1_gate_progression_ambiguous", "verify_m1_gate.py", "explicitly authorize M2 progression",
     lambda root: replace_in(root, M1_GATE, "| M2 progression | **Authorized** |",
                             "| M2 progression | Pending |")),
    # #81: the Beta verdict must stay bound to identities, required evidence, findings, and progression.
    ("m2_gate_verdict_changed", "verify_m2_gate.py", "exactly one PASS verdict",
     lambda root: replace_in(root, M2_GATE, "**Result: PASS.**", "**Result: FAIL.**")),
    ("m2_gate_artifact_identity_missing", "verify_m2_gate.py", "journey extension jar identity",
     lambda root: replace_in(root, M2_GATE, "| Journey extension jar |",
                             "| Journey extension jar (unrecorded) |")),
    ("m2_gate_unowned_finding", "verify_m2_gate.py", "finding lacks severity, owner, or status",
     lambda root: replace_in(root, M2_GATE, "| #81 | RESOLVED |", "| — | RESOLVED |")),
    ("m2_gate_progression_ambiguous", "verify_m2_gate.py", "explicitly authorize M3 progression",
     lambda root: replace_in(root, M2_GATE, "| M3 progression | **Authorized** |",
                             "| M3 progression | Pending |")),
    ("m2_gate_evidence_missing", "verify_m2_gate.py", "evidence matrix misses",
     lambda root: drop_line(root, M2_GATE, "| VERIFY-M2-010 |")),
    # #82: feature freeze must remain bound to identities, complete evidence, findings, and progression.
    ("m3_gate_verdict_changed", "verify_m3_gate.py", "exactly one PASS verdict",
     lambda root: replace_in(root, M3_GATE, "**Result: PASS.**", "**Result: FAIL.**")),
    ("m3_gate_artifact_identity_missing", "verify_m3_gate.py", "server boot jar identity",
     lambda root: replace_in(root, M3_GATE, "4309d8b42f81e99d6c92f675edf4065f99dd431f8e1964ae38312181af884904",
                             "unrecorded")),
    ("m3_gate_unowned_finding", "verify_m3_gate.py", "finding lacks severity, owner, or status",
     lambda root: replace_in(root, M3_GATE, "| #82 | RESOLVED |", "| — | RESOLVED |")),
    ("m3_gate_progression_ambiguous", "verify_m3_gate.py", "explicitly authorize feature freeze and M4 progression",
     lambda root: replace_in(root, M3_GATE, "| M4 progression | **Authorized** |",
                             "| M4 progression | Pending |")),
    ("m3_gate_evidence_missing", "verify_m3_gate.py", "evidence matrix misses",
     lambda root: drop_line(root, M3_GATE, "| VERIFY-M3-011 |")),
]
for fixture in fixtures:
    expect_rejection(*fixture)

with tempfile.TemporaryDirectory() as directory:
    repository = repository_copy(Path(directory))
    fixture_repository(repository)
    must(["git", "add", "."], repository)
    must(["git", "commit", "-q", "-m", "traceability baseline"], repository)
    base = must(["git", "rev-parse", "HEAD"], repository).stdout.strip()
    edit_json(repository, TRACEABILITY / "index.json", lambda data: data["issues"].remove(28))
    (repository / TRACEABILITY / "issues/28.json").unlink()
    (repository / TRACEABILITY / "issue-specs/28.json").unlink()
    next((repository / "docs/implementation").glob("ISSUE-28-*.md")).unlink()
    environment = os.environ.copy()
    environment["AGENTWORKS_REPOSITORY_ROOT"] = str(repository)
    environment["TRACEABILITY_BASE_SHA"] = base
    result = run(["python3", str(ROOT / "scripts/ci/verify_traceability.py")], repository, environment)
    if result.returncode == 0 or "append-only" not in result.stdout + result.stderr:
        raise SystemExit("policy-fixtures=FAIL: append-only history gate accepted deletion")
print("traceability_append_only_history=REJECTED")

with tempfile.TemporaryDirectory() as directory:
    repository = repository_copy(Path(directory))
    fixture_repository(repository)
    must(["git", "add", "."], repository)
    must(["git", "commit", "-q", "-m", "release baseline"], repository)
    environment = os.environ.copy()
    environment["AGENTWORKS_REPOSITORY_ROOT"] = str(repository)
    gate = ["python3", str(ROOT / "scripts/ci/verify_migration_immutability.py")]
    # Without a release tag there is nothing to protect; a tagged, unchanged tree passes too.
    for label in ("untagged", "tagged"):
        if label == "tagged":
            must(["git", "tag", "v0.0.1-fixture"], repository)
        result = run(gate, repository, environment)
        if result.returncode != 0:
            raise SystemExit(f"policy-fixtures=FAIL: migration gate refused an unchanged {label} tree: {result.stdout}")
    released = repository / COMMON_MIGRATIONS / "V1__structural_tenancy_and_lifecycle.sql"
    released.write_text(released.read_text(encoding="utf-8") + "\n-- edited after release\n", encoding="utf-8")
    result = run(gate, repository, environment)
    if result.returncode == 0 or "is modified" not in result.stdout + result.stderr:
        raise SystemExit("policy-fixtures=FAIL: migration gate accepted an edited released migration")
    print("released_migration_edited=REJECTED")
    released.unlink()
    result = run(gate, repository, environment)
    if result.returncode == 0 or "is removed" not in result.stdout + result.stderr:
        raise SystemExit("policy-fixtures=FAIL: migration gate accepted a removed released migration")
    print("released_migration_removed=REJECTED")
    # #206: a v* tag that names no commit (here a blob) fails the gate with git's message, not a traceback.
    blob = must(["git", "hash-object", "-w", "--", str(COMMON_MIGRATIONS / "V2__agent_definition_version_lineage.sql")],
                repository).stdout.strip()
    must(["git", "tag", "v0.0.2-fixture-blob", blob], repository)
    result = run(gate, repository, environment)
    output = result.stdout + result.stderr
    if (result.returncode == 0 or "Traceback" in output
            or "cannot read the migrations of release tag v0.0.2-fixture-blob" not in output
            or "is removed" not in output):
        raise SystemExit(f"policy-fixtures=FAIL: migration gate crashed on an unreadable release tag: {output}")
    print("unreadable_release_tag=REJECTED")

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
# #207: the fixtures' git settings stayed in their own repositories; the real repository's are unchanged.
if root_git_settings() != ROOT_GIT_SETTINGS:
    raise SystemExit("policy-fixtures=FAIL: the fixtures changed the real repository's git settings "
                     f"{FIXTURE_SETTINGS}: {ROOT_GIT_SETTINGS} became {root_git_settings()}")
# #210: every inherited Git control is guarded, not only the two configuration values above. Prove the comparison
# sees a representative repository/index redirect, then restore it before checking the fixtures' real outcome.
probe_name = "GIT_INDEX_FILE"
probe_before = os.environ.get(probe_name)
os.environ[probe_name] = "/invalid/policy-fixture-index"
if probe_name not in changed_git_environment():
    raise SystemExit("policy-fixtures=FAIL: the Git environment guard did not detect GIT_INDEX_FILE")
if probe_before is None:
    os.environ.pop(probe_name)
else:
    os.environ[probe_name] = probe_before
changed_environment = changed_git_environment()
if changed_environment:
    raise SystemExit("policy-fixtures=FAIL: the fixtures changed inherited Git process controls: "
                     + ", ".join(changed_environment))
supply_chain = run(["python3", str(ROOT / "scripts/ci/test_supply_chain.py")], ROOT)
if supply_chain.returncode:
    raise SystemExit("policy-fixtures=FAIL: supply-chain fixtures failed: "
                     + supply_chain.stdout + supply_chain.stderr)
print(supply_chain.stdout, end="")
print(f"policy-fixtures=PASS fixtures={len(fixtures) + 18}")
