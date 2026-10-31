import importlib.util
import json
import sys
import tempfile
import unittest
from datetime import datetime, timezone
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
MODULE_PATH = ROOT / "scripts/release/verify_ga_audit.py"
SPEC = importlib.util.spec_from_file_location("verify_ga_audit", MODULE_PATH)
MODULE = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = MODULE
SPEC.loader.exec_module(MODULE)
OBSERVED_NOW = datetime(2026, 11, 3, tzinfo=timezone.utc)


def valid_evidence(base: Path):
    policy = MODULE.load(ROOT / MODULE.POLICY)
    commit = "a" * 40
    rc = base / "rc3-evidence.json"
    rc.write_text(json.dumps({"issue": 31, "release": {"commit": commit}, "verdict": "GO", "artifacts": {
        "manifestSha256": "b" * 64, "checksumsSha256": "c" * 64}}) + "\n", encoding="utf-8")
    report = base / "ga-report.json"
    report.write_text(json.dumps({"commit": commit, "verdict": "PASS", "manifestSha256": "b" * 64,
                                  "checksumsSha256": "c" * 64}) + "\n", encoding="utf-8")
    bundle = base / "ga-report.sigstore.json"
    bundle.write_text('{"verificationMaterial":{}}\n', encoding="utf-8")
    def evidence_file(name):
        path = base / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text('{"status":"PASS"}\n', encoding="utf-8")
        return {"path": str(path), "sha256": MODULE.digest(path)}
    return {
        "schemaVersion": 1,
        "release": {"version": "1.0.0-rc3", "tag": "v1.0.0-rc3",
                    "repository": "https://github.com/dvarahq/dvara-agentworks",
                    "commit": commit, "dirty": False},
        "artifacts": {"rcEvidencePath": str(rc), "manifestSha256": "b" * 64,
                      "checksumsSha256": "c" * 64},
        "prerequisites": [
            {"issue": 31, "status": "CLOSED", "commit": commit, "evidence": str(rc),
             "evidenceSha256": MODULE.digest(rc)},
        ],
        "invariants": {
            entry["id"]: {"status": "PASS", "commit": commit,
                          "positiveEvidence": evidence_file(f"results/{entry['id']}-positive.json"),
                          "negativeEvidence": evidence_file(f"results/{entry['id']}-negative.json")}
            for entry in policy["invariants"]
        },
        "audits": {
            name: {"status": "PASS", "commit": commit,
                   "evidence": evidence_file(f"results/{name}.json")}
            for name in policy["requiredAudits"]
        },
        "findings": [{"severity": "LOW", "status": "ACCEPTED", "owner": "release-owner",
                      "impact": "Editorial follow-up only.", "disposition": "Tracked for 1.0.1."}],
        "report": {"path": str(report), "sha256": MODULE.digest(report),
                   "sigstoreBundlePath": str(bundle), "sigstoreBundleSha256": MODULE.digest(bundle),
                   "certificateIdentity": "https://github.com/dvarahq/dvara-agentworks/.github/workflows/release.yml@refs/tags/v1.0.0-rc3",
                   "certificateOidcIssuer": "https://token.actions.githubusercontent.com"},
        "verdict": "PASS",
        "verdictAt": "2026-11-02T12:00:00Z",
    }


class GaAuditTest(unittest.TestCase):
    def test_repository_policy_is_internally_consistent(self):
        self.assertEqual([], MODULE.policy_problems(ROOT))

    def test_complete_exact_commit_evidence_can_pass(self):
        with tempfile.TemporaryDirectory() as directory:
            base = Path(directory)
            evidence = valid_evidence(base)
            self.assertEqual([], MODULE.evidence_problems(
                evidence, ROOT, evidence_base=base, verify_external=False, now=OBSERVED_NOW))

    def test_pending_template_cannot_pass(self):
        template = MODULE.load(ROOT / MODULE.TEMPLATE)
        problems = MODULE.evidence_problems(template, ROOT, verify_external=False, now=OBSERVED_NOW)
        self.assertTrue(any("not exact and clean" in problem for problem in problems))
        self.assertTrue(any("no passing explicit verdict" in problem for problem in problems))

    def test_every_invariant_needs_positive_and_non_vacuous_negative_evidence(self):
        with tempfile.TemporaryDirectory() as directory:
            evidence = valid_evidence(Path(directory))
            evidence["invariants"]["ARCH-003"]["negativeEvidence"] = {}
            self.assertTrue(any("ARCH-003 negativeEvidence path is missing" in problem for problem in
                                MODULE.evidence_problems(evidence, ROOT, verify_external=False,
                                                         now=OBSERVED_NOW)))

    def test_blocker_or_high_finding_refuses_ga(self):
        with tempfile.TemporaryDirectory() as directory:
            evidence = valid_evidence(Path(directory))
            evidence["findings"].append({"severity": "HIGH", "status": "OPEN", "owner": "security",
                                         "impact": "Authentication bypass.", "disposition": "Must fix."})
            self.assertTrue(any("unresolved HIGH" in problem for problem in
                                MODULE.evidence_problems(evidence, ROOT, verify_external=False,
                                                         now=OBSERVED_NOW)))

    def test_report_digest_and_future_verdict_are_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            evidence = valid_evidence(Path(directory))
            evidence["report"]["sha256"] = "0" * 64
            evidence["verdictAt"] = "2099-01-01T00:00:00Z"
            problems = MODULE.evidence_problems(evidence, ROOT, verify_external=False, now=OBSERVED_NOW)
            self.assertTrue(any("report SHA-256 does not match" in problem for problem in problems))
            self.assertTrue(any("verdictAt is in the future" in problem for problem in problems))

    def test_public_issue_31_body_must_name_the_same_passing_rc_and_artifacts(self):
        with tempfile.TemporaryDirectory() as directory:
            base = Path(directory)
            evidence = valid_evidence(base)
            readiness = Path(evidence["prerequisites"][0]["evidence"])
            readiness.write_text(json.dumps({"issue": 31, "release": {"commit": "d" * 40}, "verdict": "FAIL",
                                             "artifacts": {"manifestSha256": "e" * 64,
                                                           "checksumsSha256": "f" * 64}}) + "\n",
                                 encoding="utf-8")
            evidence["prerequisites"][0]["evidenceSha256"] = MODULE.digest(readiness)
            problems = MODULE.evidence_problems(evidence, ROOT, evidence_base=base,
                                                 verify_external=False, now=OBSERVED_NOW)
            self.assertTrue(any("does not authorize GO for the exact RC commit" in problem for problem in problems))
            self.assertTrue(any("names different RC artifacts" in problem for problem in problems))

    def test_sigstore_bundle_is_digest_bound(self):
        with tempfile.TemporaryDirectory() as directory:
            base = Path(directory)
            evidence = valid_evidence(base)
            Path(evidence["report"]["sigstoreBundlePath"]).write_text("{}\n", encoding="utf-8")
            self.assertTrue(any("Sigstore bundle SHA-256 does not match" in problem for problem in
                                MODULE.evidence_problems(evidence, ROOT, evidence_base=base,
                                                         verify_external=False, now=OBSERVED_NOW)))

    def test_forbidden_core_dependency_is_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "agentworks-fixture").mkdir()
            pom = """<project xmlns=\"http://maven.apache.org/POM/4.0.0\"><dependencies><dependency>
            <groupId>org.apache.kafka</groupId><artifactId>kafka-clients</artifactId>
            </dependency></dependencies></project>"""
            (root / "pom.xml").write_text("<project xmlns=\"http://maven.apache.org/POM/4.0.0\"/>", encoding="utf-8")
            (root / "agentworks-fixture/pom.xml").write_text(pom, encoding="utf-8")
            problems = MODULE.local_independence_problems(root, MODULE.load(ROOT / MODULE.POLICY))
            self.assertTrue(any("forbidden Core dependency" in problem for problem in problems))

    def test_deferred_production_package_is_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            path = root / "agentworks-fixture/src/main/java/com/dvarahq/agentworks/nonpublic/Fixture.java"
            path.parent.mkdir(parents=True)
            path.write_text("package com.dvarahq.agentworks.nonpublic; class Fixture {}\n", encoding="utf-8")
            (root / "pom.xml").write_text("<project xmlns=\"http://maven.apache.org/POM/4.0.0\"/>", encoding="utf-8")
            (root / "agentworks-fixture/pom.xml").write_text("<project xmlns=\"http://maven.apache.org/POM/4.0.0\"/>", encoding="utf-8")
            problems = MODULE.local_independence_problems(root, MODULE.load(ROOT / MODULE.POLICY))
            self.assertTrue(any("deferred production package exists" in problem for problem in problems))

    def test_non_standard_json_constants_are_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "evidence.json"
            path.write_text('{"value": NaN}', encoding="utf-8")
            with self.assertRaisesRegex(ValueError, "non-standard JSON constant"):
                MODULE.load(path)


if __name__ == "__main__":
    unittest.main()
