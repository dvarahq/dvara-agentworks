import json
import subprocess
import unittest
from pathlib import Path


class FakeExternalRunnerTest(unittest.TestCase):
    def test_handshake_and_failure_transcript(self):
        runner = Path(__file__).parents[3] / "scripts" / "fake_external_runner.py"
        messages = "\n".join([
            json.dumps({"type": "handshake", "protocolVersion": "agentworks-executor/v1",
                        "requiredCapabilities": [], "requestedAssurance": "INSTRUMENTED",
                        "accessMode": "DVARAOS_MEDIATED"}),
            json.dumps({"type": "start", "runId": "00000000-0000-0000-0000-000000000011",
                        "traceId": "trace-python", "input": "failure"}),
            ""
        ])
        completed = subprocess.run(["python3", "-I", str(runner)], input=messages,
                                   text=True, capture_output=True, check=True)
        transcript = [json.loads(line) for line in completed.stdout.splitlines()]
        self.assertEqual("agentworks-executor/v1", transcript[0]["protocolVersion"])
        self.assertEqual(["RUN_ACTIVATED", "RUN_FAILED"],
                         [item["eventType"] for item in transcript[1:]])
        self.assertTrue(all(item["traceId"] == "trace-python" for item in transcript[1:]))


if __name__ == "__main__":
    unittest.main()
