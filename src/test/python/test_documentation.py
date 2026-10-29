import os
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
GATE = ROOT / "scripts/ci/verify_documentation.py"


class DocumentationGateTest(unittest.TestCase):
    def run_gate(self, root=ROOT):
        environment = os.environ.copy()
        environment["PYTHONDONTWRITEBYTECODE"] = "1"
        return subprocess.run(["python3", str(GATE), "--root", str(root)], cwd=ROOT, env=environment,
                              text=True, capture_output=True)

    def fixture(self):
        temporary = tempfile.TemporaryDirectory()
        root = Path(temporary.name)
        legal = "legal/oss" if (ROOT / "legal/oss/NOTICE").is_file() else None
        for relative in ("README.md", "LICENSE_POLICY.md", legal, "docs", "cli",
                         "agentworks-server/src/main/resources/api", "agentworks-server/src/main/java",
                         "agentworks-spring-boot-starter/src/main/java"):
            if relative is None:
                continue
            source = ROOT / relative; target = root / relative
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copytree(source, target) if source.is_dir() else shutil.copy2(source, target)
        if legal is None:
            for name in ("LICENSE", "NOTICE", "DCO", "CONTRIBUTING.md"):
                shutil.copy2(ROOT / name, root / name)
        return temporary, root

    def test_repository_documentation_passes(self):
        result = self.run_gate()
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)

    def test_broken_local_link_is_rejected(self):
        temporary, root = self.fixture()
        try:
            with (root / "docs/getting-started.md").open("a", encoding="utf-8") as output:
                output.write("\n[missing](not-present.md)\n")
            result = self.run_gate(root)
            self.assertNotEqual(0, result.returncode)
            self.assertIn("broken link", result.stderr)
        finally: temporary.cleanup()

    def test_insecure_external_link_is_rejected(self):
        temporary, root = self.fixture()
        try:
            with (root / "docs/getting-started.md").open("a", encoding="utf-8") as output:
                output.write("\n[insecure](http://example.com/docs)\n")
            result = self.run_gate(root)
            self.assertNotEqual(0, result.returncode)
            self.assertIn("insecure external link", result.stderr)
        finally: temporary.cleanup()

    def test_invalid_shell_snippet_is_rejected(self):
        temporary, root = self.fixture()
        try:
            with (root / "docs/getting-started.md").open("a", encoding="utf-8") as output:
                output.write("\n```bash\nif broken; then\n```\n")
            result = self.run_gate(root)
            self.assertNotEqual(0, result.returncode)
            self.assertIn("shell snippet", result.stderr)
        finally: temporary.cleanup()

    def test_cli_drift_is_rejected(self):
        temporary, root = self.fixture()
        try:
            path = root / "docs/cli.md"
            path.write_text(path.read_text().replace("agent create\n", ""), encoding="utf-8")
            result = self.run_gate(root)
            self.assertNotEqual(0, result.returncode)
            self.assertIn("CLI documentation drift", result.stderr)
        finally: temporary.cleanup()

    def test_openapi_operation_without_summary_is_rejected(self):
        temporary, root = self.fixture()
        try:
            path = root / "agentworks-server/src/main/resources/api/agentworks-api-v1.openapi.yaml"
            path.write_text(path.read_text().replace("      summary:", "      omitted-summary:", 1), encoding="utf-8")
            result = self.run_gate(root)
            self.assertNotEqual(0, result.returncode)
            self.assertIn("summary declarations", result.stderr)
        finally: temporary.cleanup()

    def test_missing_notice_and_attribution_is_rejected(self):
        temporary, root = self.fixture()
        try:
            notice = root / "legal/oss/NOTICE"
            (notice if notice.is_file() else root / "NOTICE").unlink()
            result = self.run_gate(root)
            self.assertNotEqual(0, result.returncode)
            self.assertIn("missing required legal or attribution file", result.stderr)
        finally: temporary.cleanup()


if __name__ == "__main__":
    unittest.main()
