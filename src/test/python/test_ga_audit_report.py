import importlib.util
import json
import sys
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
MODULE_PATH = ROOT / "scripts/release/verify_ga_audit_report.py"
SPEC = importlib.util.spec_from_file_location("verify_ga_audit_report", MODULE_PATH)
MODULE = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = MODULE
SPEC.loader.exec_module(MODULE)

VERSION = "1.0.0-rc5"
TAG = "v1.0.0-rc5"
REPOSITORY = "https://github.com/dvarahq/dvara-agentworks"
COMMIT = "a" * 40
MANIFEST = "b" * 64
CHECKSUMS = "c" * 64


def report():
    return {
        "version": VERSION,
        "tag": TAG,
        "repository": REPOSITORY,
        "commit": COMMIT,
        "verdict": "PASS",
        "manifestSha256": MANIFEST,
        "checksumsSha256": CHECKSUMS,
    }


def verify(value):
    return MODULE.problems(
        value,
        version=VERSION,
        tag=TAG,
        repository=REPOSITORY,
        commit=COMMIT,
        manifest_sha256=MANIFEST,
        checksums_sha256=CHECKSUMS,
    )


class GaAuditReportTest(unittest.TestCase):
    def test_exact_passing_report_is_accepted(self):
        self.assertEqual([], verify(report()))

    def test_non_blocking_follow_up_verdict_is_accepted(self):
        value = report()
        value["verdict"] = "PASS_WITH_NON_BLOCKING_FOLLOW_UPS"
        self.assertEqual([], verify(value))

    def test_wrong_identity_or_digest_is_rejected(self):
        for field, changed in (
            ("version", "1.0.0"), ("tag", "v1.0.0-rc4"),
            ("repository", "https://example.invalid/repo"), ("commit", "d" * 40),
            ("manifestSha256", "e" * 64), ("checksumsSha256", "f" * 64),
        ):
            with self.subTest(field=field):
                value = report()
                value[field] = changed
                self.assertTrue(verify(value))

    def test_non_passing_verdict_is_rejected(self):
        for verdict in ("PENDING", "FAIL", "GO", None):
            with self.subTest(verdict=verdict):
                value = report()
                value["verdict"] = verdict
                self.assertTrue(any("verdict" in problem for problem in verify(value)))

    def test_extra_or_missing_fields_are_rejected(self):
        extra = report()
        extra["notes"] = "not signed"
        self.assertTrue(any("fields" in problem for problem in verify(extra)))
        missing = report()
        del missing["tag"]
        self.assertTrue(any("fields" in problem for problem in verify(missing)))

    def test_non_standard_json_is_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "report.json"
            path.write_text('{"verdict": NaN}', encoding="utf-8")
            with self.assertRaisesRegex(ValueError, "non-standard JSON"):
                MODULE.load(path)


if __name__ == "__main__":
    unittest.main()
