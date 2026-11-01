import importlib.util
import json
import subprocess
import sys
import tempfile
import unittest
from datetime import datetime, timezone
from pathlib import Path
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[3]
MODULE_PATH = ROOT / "scripts/release/verify_rc5_readiness.py"
SPEC = importlib.util.spec_from_file_location("verify_rc5_readiness", MODULE_PATH)
MODULE = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = MODULE
SPEC.loader.exec_module(MODULE)
OBSERVED_NOW = datetime(2026, 11, 2, tzinfo=timezone.utc)


def valid_evidence():
    policy = MODULE.load(ROOT / MODULE.POLICY)
    commit = "a" * 40
    return {
        "schemaVersion": 1,
        "release": {
            "version": policy["release"]["version"],
            "tag": policy["release"]["tag"],
            "repository": policy["release"]["canonicalSource"],
            "commit": commit,
            "dirty": False,
            "frozenAt": "2026-10-25T12:00:00Z",
        },
        "artifacts": {
            "releaseQualified": True,
            "bundlePath": "signed-release-candidate",
            "manifestSha256": "b" * 64,
            "checksumsSha256": "c" * 64,
            "kinds": policy["requiredArtifactKinds"],
            "independentlyVerified": True,
        },
        "prerequisites": [
            {"issue": issue, "status": "CLOSED", "disposition": f"https://github.com/example/issues/{issue}"}
            for issue in policy["requiredPriorReleaseBlockers"]
        ],
        "passes": {
            "protected-ci": {
                "commit": commit,
                "environment": "github-hosted-linux",
                "evidence": "release-run-123",
                "status": "PASS",
            },
            "independent-clean": {
                "commit": commit,
                "environment": "independent-linux-amd64",
                "evidence": "independent-report-sha256:123",
                "status": "PASS",
            },
        },
        "gates": {
            gate: {"commit": commit, "status": "PASS", "evidence": f"evidence/{gate}.json"}
            for gate in policy["requiredGates"]
        },
        "soak": {
            "commit": commit,
            "durationHours": 24,
            "verdict": "PASS",
            "evidence": "performance-evidence/report.json",
        },
        "findings": [{
            "severity": "LOW",
            "status": "OPEN",
            "owner": "release-owner",
            "impact": "Cosmetic diagnostic wording only.",
            "disposition": "Accepted for 1.0.1 under issue #999.",
        }],
        "stabilization": {
            "startedAt": "2026-10-25T12:00:00Z",
            "endedAt": "2026-11-01T12:00:00Z",
        },
        "verdict": "GO",
        "verdictAt": "2026-11-01T12:01:00Z",
    }


def local_problems(evidence):
    return MODULE.evidence_problems(evidence, ROOT, verify_external=False, now=OBSERVED_NOW)


class Rc5ReadinessTest(unittest.TestCase):
    def test_repository_policy_is_internally_consistent(self):
        self.assertEqual([], MODULE.policy_problems(ROOT))
        policy = MODULE.load(ROOT / MODULE.POLICY)
        self.assertEqual([14], policy["requiredPriorReleaseBlockers"])
        for relative in policy["evidence"].values():
            self.assertTrue((ROOT / relative).is_file(), relative)
            self.assertTrue(relative.startswith(("config/release/", "docs/operations/", "docs/release/")), relative)

    def test_complete_independent_evidence_can_authorize_go(self):
        self.assertEqual([], local_problems(valid_evidence()))

    def test_pending_template_cannot_authorize_go(self):
        template = MODULE.load(ROOT / MODULE.TEMPLATE)
        problems = local_problems(template)
        self.assertTrue(any("not exact and clean" in problem for problem in problems))
        self.assertTrue(any("not GO" in problem for problem in problems))

    def test_qualification_artifacts_cannot_be_mistaken_for_release_artifacts(self):
        evidence = valid_evidence()
        evidence["artifacts"]["releaseQualified"] = False
        self.assertTrue(any("not release-qualified" in problem
                            for problem in local_problems(evidence)))

    def test_two_passes_must_be_independent(self):
        evidence = valid_evidence()
        evidence["passes"]["independent-clean"]["environment"] = "github-hosted-linux"
        self.assertTrue(any("not from distinct environments" in problem
                            for problem in local_problems(evidence)))

    def test_every_gate_is_bound_to_the_exact_release_commit(self):
        evidence = valid_evidence()
        evidence["gates"]["security"]["commit"] = "d" * 40
        self.assertTrue(any("gate security did not pass" in problem
                            for problem in local_problems(evidence)))

    def test_open_blocking_finding_refuses_go(self):
        evidence = valid_evidence()
        evidence["findings"].append({
            "severity": "HIGH",
            "status": "OPEN",
            "owner": "security-owner",
            "impact": "Release compromise.",
            "disposition": "Must fix before RC.",
        })
        self.assertTrue(any("unresolved HIGH" in problem
                            for problem in local_problems(evidence)))

    def test_short_stabilization_window_refuses_go(self):
        evidence = valid_evidence()
        evidence["stabilization"]["endedAt"] = "2026-10-31T12:00:00Z"
        self.assertTrue(any("shorter than policy" in problem
                            for problem in local_problems(evidence)))

    def test_malformed_evidence_shapes_fail_closed(self):
        evidence = valid_evidence()
        evidence["passes"] = []
        evidence["gates"] = {"security": []}
        evidence["findings"] = [None]
        problems = local_problems(evidence)
        self.assertTrue(any("passes is not an object" in problem for problem in problems))
        self.assertTrue(any("gate security is not an object" in problem for problem in problems))
        self.assertTrue(any("finding 0 is not an object" in problem for problem in problems))

    def test_future_dated_stabilization_cannot_authorize_go(self):
        evidence = valid_evidence()
        evidence["release"]["frozenAt"] = "2099-01-01T00:00:00Z"
        evidence["stabilization"] = {
            "startedAt": "2099-01-01T00:00:00Z",
            "endedAt": "2099-01-08T00:00:00Z",
        }
        evidence["verdictAt"] = "2099-01-08T00:01:00Z"
        problems = local_problems(evidence)
        self.assertTrue(any("is in the future" in problem for problem in problems))

    def test_non_finite_soak_duration_cannot_authorize_go(self):
        for duration in (float("nan"), float("inf"), float("-inf"), 10 ** 1000):
            with self.subTest(duration=duration):
                evidence = valid_evidence()
                evidence["soak"]["durationHours"] = duration
                self.assertTrue(any("minimum duration" in problem for problem in local_problems(evidence)))

    def test_non_standard_json_constants_are_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            evidence = Path(directory) / "evidence.json"
            evidence.write_text('{"durationHours": NaN}', encoding="utf-8")
            with self.assertRaisesRegex(ValueError, "non-standard JSON constant"):
                MODULE.load(evidence)

    def test_signed_release_bundle_is_cryptographically_verified_and_digest_bound(self):
        evidence = valid_evidence()
        with tempfile.TemporaryDirectory() as directory:
            base = Path(directory)
            bundle = base / "signed-release-candidate"
            bundle.mkdir()
            (bundle / "MANIFEST.json").write_text("{}\n", encoding="utf-8")
            (bundle / "SHA256SUMS").write_text("manifest checksums\n", encoding="utf-8")
            (bundle / "SOURCE.json").write_text(json.dumps({
                "repository": "https://github.com/dvarahq/dvara-agentworks",
                "tag": "v1.0.0-rc5",
                "commit": evidence["release"]["commit"],
                "releaseQualified": True,
            }), encoding="utf-8")
            evidence["artifacts"]["manifestSha256"] = MODULE.sha256(bundle / "MANIFEST.json")
            evidence["artifacts"]["checksumsSha256"] = MODULE.sha256(bundle / "SHA256SUMS")
            completed = subprocess.CompletedProcess([], 0, "release-candidate=PASS", "")
            with patch.object(MODULE.subprocess, "run", return_value=completed) as verify:
                problems = MODULE.evidence_problems(
                    evidence, ROOT, evidence_base=base, now=OBSERVED_NOW)
            self.assertEqual([], problems)
            command = verify.call_args.args[0]
            self.assertIn("--phase", command)
            self.assertIn("release", command)
            self.assertIn(evidence["release"]["commit"], command)

            evidence["artifacts"]["manifestSha256"] = "0" * 64
            with patch.object(MODULE.subprocess, "run", return_value=completed):
                problems = MODULE.evidence_problems(
                    evidence, ROOT, evidence_base=base, now=OBSERVED_NOW)
            self.assertTrue(any("manifestSha256 does not match" in problem for problem in problems))


if __name__ == "__main__":
    unittest.main()
