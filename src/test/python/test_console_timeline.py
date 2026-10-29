"""Runs the Web Console timeline script (issue #36) against a scripted EventSource under Node.js.

The script is the Console's only client code: it follows the run event stream, renders events as text, and handles
the stream's control events as the #35 contract documents. CI runners provide Node.js; elsewhere the test is skipped
with a reason, unless CI is set, where a missing Node.js fails it.
"""

import os
import shutil
import subprocess
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
SCRIPT = ROOT / "agentworks-server/src/main/resources/static/console/assets/console.js"
TEST = ROOT / "agentworks-server/src/test/js/console-timeline.test.mjs"


class ConsoleTimelineTest(unittest.TestCase):
    def test_timeline_renders_text_and_handles_every_control_event(self) -> None:
        node = shutil.which("node")
        if node is None:
            if os.environ.get("CI"):
                self.fail("Node.js is required in CI to run the Console timeline test")
            self.skipTest("Node.js is not installed")
        result = subprocess.run([node, str(TEST), str(SCRIPT)], cwd=ROOT, text=True, capture_output=True, timeout=60,
                                check=False)
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertIn("ALL PASSED", result.stdout)


if __name__ == "__main__":
    unittest.main()
