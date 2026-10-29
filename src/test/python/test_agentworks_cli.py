import contextlib
import io
import json
import os
import stat
import sys
import tempfile
import threading
import unittest
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(ROOT / "cli"))
from agentworks import main as cli


class Server(BaseHTTPRequestHandler):
    requests = []
    failures = 0
    stream_requests = 0
    response_payload = None
    response_status = 200

    def log_message(self, *_):
        pass

    def _request(self):
        length = int(self.headers.get("Content-Length", "0"))
        body = self.rfile.read(length) if length else b""
        Server.requests.append((self.command, self.path, dict(self.headers), body))
        if self.path == "/api/v1/openapi.yaml":
            self.send_response(200); self.send_header("Content-Type", "application/yaml"); self.end_headers()
            self.wfile.write(b"openapi: 3.1.0\ninfo:\n  title: AgentWorks API\n  description: Bearer awt_public_example\n")
            return
        if self.path.startswith("/api/v1/runs/run-1/stream"):
            Server.stream_requests += 1
            self.send_response(200); self.send_header("Content-Type", "text/event-stream"); self.end_headers()
            if Server.stream_requests == 1:
                self.wfile.write(b"id: run-1:1\nevent: RUN_CREATED\ndata: {\"sequence\":1}\n\n")
                self.wfile.write(b"event: reconnect\ndata: {\"code\":\"SERVER_RESTART\"}\n\n")
            else:
                self.wfile.write(b"id: run-1:2\nevent: RUN_COMPLETED\ndata: {\"sequence\":2}\n\n")
                self.wfile.write(b"event: stream-end\ndata: {\"status\":\"COMPLETED\"}\n\n")
            return
        if Server.failures:
            Server.failures -= 1
            self.send_response(503); self.send_header("Content-Type", "application/problem+json"); self.end_headers()
            self.wfile.write(b'{"code":"UNAVAILABLE","detail":"retry"}')
            return
        if self.headers.get("Authorization") != "Bearer awt_test_token":
            self.send_response(401); self.send_header("Content-Type", "application/problem+json"); self.end_headers()
            self.wfile.write(b'{"code":"UNAUTHORIZED","detail":"no"}')
            return
        self.send_response(Server.response_status); self.send_header("Content-Type", "application/json"); self.end_headers()
        payload = Server.response_payload if Server.response_payload is not None else {
            "path": self.path, "authorization": "must-be-redacted",
            "idempotency": self.headers.get("Idempotency-Key")}
        self.wfile.write(json.dumps(payload).encode())

    do_GET = do_POST = do_DELETE = _request


class CliTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.server = ThreadingHTTPServer(("127.0.0.1", 0), Server)
        cls.thread = threading.Thread(target=cls.server.serve_forever, daemon=True); cls.thread.start()
        cls.url = f"http://127.0.0.1:{cls.server.server_port}"

    @classmethod
    def tearDownClass(cls):
        cls.server.shutdown(); cls.server.server_close()

    def setUp(self):
        Server.requests, Server.failures, Server.stream_requests = [], 0, 0
        Server.response_payload, Server.response_status = None, 200
        self.temp = tempfile.TemporaryDirectory()
        self.env = patch.dict(os.environ, {"XDG_CONFIG_HOME": self.temp.name,
                                           "AGENTWORKS_TOKEN": "awt_test_token",
                                           "AGENTWORKS_URL": self.url}, clear=True)
        self.env.start()

    def tearDown(self):
        self.env.stop(); self.temp.cleanup()

    def invoke(self, *arguments):
        stdout, stderr = io.StringIO(), io.StringIO()
        with contextlib.redirect_stdout(stdout), contextlib.redirect_stderr(stderr):
            status = cli.main(["--output", "json", *arguments])
        return status, stdout.getvalue(), stderr.getvalue()

    def test_json_envelope_and_secret_redaction_are_stable(self):
        status, output, _ = self.invoke("agent", "list")
        value = json.loads(output)
        self.assertEqual(0, status); self.assertEqual("agentworks.cli/v1", value["schemaVersion"])
        self.assertEqual("[REDACTED]", value["data"]["result"]["authorization"])
        self.assertNotIn("awt_test_token", output)

    def test_auth_status_verifies_the_server_principal(self):
        status, output, error = self.invoke("auth", "status")
        self.assertEqual(0, status, error)
        self.assertEqual("/api/v1/auth/session", json.loads(output)["data"]["principal"]["path"])

    def test_public_openapi_omits_auth_and_preserves_yaml_media(self):
        expected = ("openapi: 3.1.0\ninfo:\n  title: AgentWorks API\n"
                    "  description: Bearer awt_public_example\n")
        with patch.dict(os.environ, {"AGENTWORKS_TOKEN": ""}):
            status, output, error = self.invoke("diagnostic", "openapi")
        self.assertEqual(0, status, error)
        self.assertEqual(expected, json.loads(output)["data"]["result"])
        headers = {key.lower(): value for key, value in Server.requests[-1][2].items()}
        self.assertNotIn("authorization", headers)
        self.assertEqual("application/yaml", headers["accept"])

    def test_profile_stores_only_a_reference_and_uses_mode_0600(self):
        status, output, _ = self.invoke("profile", "set", "ci", "--server", self.url,
                                        "--token-env", "CI_AGENTWORKS_TOKEN", "--workspace", "workspace-1")
        self.assertEqual(0, status)
        path = Path(self.temp.name) / "agentworks" / "config.json"
        self.assertEqual(0o600, stat.S_IMODE(path.stat().st_mode))
        raw = path.read_text()
        self.assertIn("CI_AGENTWORKS_TOKEN", raw); self.assertNotIn("awt_test_token", raw)
        self.assertEqual("environment", json.loads(output)["data"]["authSource"])

    def test_unsafe_profile_and_token_file_are_rejected(self):
        path = Path(self.temp.name) / "agentworks" / "config.json"; path.parent.mkdir()
        path.write_text('{"version":1,"profiles":{},"currentProfile":"default"}'); path.chmod(0o644)
        status, _, error = self.invoke("profile", "list")
        self.assertEqual(cli.AUTH, status); self.assertEqual("UNSAFE_CONFIG_PERMISSIONS", json.loads(error)["error"]["code"])

    def test_remote_plain_http_and_literal_profile_tokens_are_rejected(self):
        with patch.dict(os.environ, {"AGENTWORKS_URL": "http://agentworks.example"}):
            status, _, error = self.invoke("agent", "list")
        self.assertEqual(cli.USAGE, status); self.assertEqual("INSECURE_URL", json.loads(error)["error"]["code"])
        status, _, error = self.invoke("profile", "set", "bad", "--server", self.url, "--token", "awt_secret")
        self.assertEqual(cli.USAGE, status); self.assertNotIn("awt_secret", error)

    def test_idempotent_mutation_retries_and_reuses_exact_key(self):
        request = Path(self.temp.name) / "run.json"; request.write_text('{"input":{"text":"hello"}}')
        Server.failures = 1
        status, _, _ = self.invoke("run", "start", "deployment-1", "--file", str(request),
                                   "--idempotency-key", "run-key-1")
        self.assertEqual(0, status); self.assertEqual(2, len(Server.requests))
        self.assertEqual(["run-key-1", "run-key-1"], [r[2]["Idempotency-Key"] for r in Server.requests])
        self.assertEqual(Server.requests[0][3], Server.requests[1][3])

    def test_unkeyed_mutation_is_never_retried(self):
        request = Path(self.temp.name) / "verify.json"; request.write_text('{}')
        Server.failures = 1
        status, _, error = self.invoke("release", "verify", "--file", str(request))
        self.assertEqual(cli.RELEASE_FAILED, status); self.assertEqual(1, len(Server.requests))
        self.assertEqual("UNAVAILABLE", json.loads(error)["error"]["code"])

    def test_naturally_idempotent_mutation_retries_without_inventing_a_key(self):
        request = Path(self.temp.name) / "dataset.json"; request.write_text('{"name":"fixture"}')
        Server.failures = 1
        status, _, error = self.invoke("eval", "create-dataset", "--file", str(request))
        self.assertEqual(0, status, error); self.assertEqual(2, len(Server.requests))
        self.assertNotIn("Idempotency-Key", Server.requests[0][2])
        self.assertEqual(Server.requests[0][3], Server.requests[1][3])

    def test_optional_and_required_request_bodies_follow_openapi(self):
        for action in ("cancel", "pause"):
            with self.subTest(action=action, body="absent"):
                Server.requests = []
                status, _, error = self.invoke("run", action, "run-1")
                self.assertEqual(0, status, error)
                self.assertEqual(b"", Server.requests[-1][3])
            with self.subTest(action=action, body="present"):
                request = Path(self.temp.name) / f"{action}.json"
                request.write_text('{"reason":"operator request"}')
                Server.requests = []
                status, _, error = self.invoke("run", action, "run-1", "--file", str(request))
                self.assertEqual(0, status, error)
                self.assertEqual({"reason": "operator request"}, json.loads(Server.requests[-1][3]))
        status, _, error = self.invoke("run", "start", "deployment-1", "--idempotency-key", "run-1")
        self.assertEqual(cli.USAGE, status)
        self.assertEqual("INVALID_ARGUMENT", json.loads(error)["error"]["code"])

    def test_http_domain_and_auth_exit_codes_are_distinct(self):
        with patch.dict(os.environ, {"AGENTWORKS_TOKEN": "bad"}):
            status, _, error = self.invoke("agent", "list")
        self.assertEqual(cli.AUTH, status); self.assertEqual("TOKEN_UNAVAILABLE", json.loads(error)["error"]["code"])
        Server.failures = 1
        request = Path(self.temp.name) / "verify.json"; request.write_text('{}')
        status, _, _ = self.invoke("--retries", "0", "release", "verify", "--file", str(request))
        self.assertEqual(cli.RELEASE_FAILED, status)

    def test_stream_reconnects_from_last_sequence_without_echoing_token(self):
        status, output, error = self.invoke("stream", "run-1", "--max-reconnects", "2")
        self.assertEqual(0, status, error); self.assertEqual(2, Server.stream_requests)
        headers = {key.lower(): value for key, value in Server.requests[1][2].items()}
        self.assertEqual("run-1:1", headers["last-event-id"])
        self.assertNotIn("awt_test_token", output)
        events = [json.loads(line)["data"]["event"] for line in output.splitlines()]
        self.assertEqual(["RUN_CREATED", "reconnect", "RUN_COMPLETED", "stream-end"], events)

    def test_context_is_secret_free_and_local_failure_has_stable_code(self):
        status, output, _ = self.invoke("diagnostic", "context")
        self.assertEqual(0, status); self.assertNotIn("awt_test_token", output)
        status, _, error = self.invoke("test", "run", "--", sys.executable, "-c", "raise SystemExit(7)")
        self.assertEqual(cli.TEST_FAILED, status); self.assertEqual("TEST_FAILED", json.loads(error)["error"]["code"])

    def test_local_command_output_cannot_break_json_or_echo_tokens(self):
        status, output, error = self.invoke("test", "run", "--", sys.executable, "-c",
                                            "print('awt_should_never_escape')")
        self.assertEqual(0, status, error); self.assertEqual(1, len(output.splitlines()))
        self.assertNotIn("awt_should_never_escape", output + error)

    def test_local_command_receives_no_agentworks_credentials(self):
        self.invoke("profile", "set", "ci", "--server", self.url, "--token-env", "CI_AGENTWORKS_TOKEN")
        self.invoke("profile", "use", "ci")
        leaked = Path(self.temp.name) / "child-environment.json"
        script = ("import json,os,pathlib; pathlib.Path(os.environ['OUTPUT_FILE']).write_text(json.dumps({"
                  "'direct':os.environ.get('AGENTWORKS_TOKEN'),"
                  "'profile':os.environ.get('CI_AGENTWORKS_TOKEN'),"
                  "'credential':os.environ.get('AGENTWORKS_CLIENT_SECRET'),"
                  "'ordinary':os.environ.get('ORDINARY_VALUE')}))")
        with patch.dict(os.environ, {"CI_AGENTWORKS_TOKEN": "awt_profile_secret",
                                     "AGENTWORKS_CLIENT_SECRET": "awt_other_secret",
                                     "ORDINARY_VALUE": "preserved", "OUTPUT_FILE": str(leaked)}):
            status, _, error = self.invoke("test", "run", "--", sys.executable, "-c", script)
        self.assertEqual(0, status, error)
        self.assertEqual({"direct": None, "profile": None, "credential": None, "ordinary": "preserved"},
                         json.loads(leaked.read_text()))

    def test_typed_semantic_failures_keep_exact_server_evidence(self):
        request = Path(self.temp.name) / "request.json"; request.write_text('{}')
        cases = [
            (("release", "verify", "--file", str(request)), 200, cli.RELEASE_FAILED, "SIGNATURE_INVALID",
             "Release bundle verification was refused",
             {"trusted": False, "code": "SIGNATURE_INVALID", "detail": "signature mismatch"}),
            (("promote", "apply", "environment-1", "--file", str(request), "--idempotency-key", "promotion-1"),
             201, cli.PROMOTION_FAILED, "PROMOTION_DENIED", "Promotion policy denied the requested change",
             {"outcome": "DENIED", "gates": [{"result": "FAILED"}]}),
            (("eval", "status", "evaluation-1"), 200, cli.EVALUATION_FAILED, "EVALUATION_BUDGET_EXCEEDED",
             "Evaluation ended with status BUDGET_EXCEEDED",
             {"status": "BUDGET_EXCEEDED", "failureCode": "COST_BUDGET"}),
            (("eval", "status", "evaluation-2"), 200, cli.EVALUATION_FAILED, "EVALUATION_FAILED",
             "Evaluation ended with status FAILED", {"status": "FAILED", "failureCode": "EVALUATOR_ERROR"}),
            (("eval", "status", "evaluation-3"), 200, cli.EVALUATION_FAILED, "EVALUATION_CANCELLED",
             "Evaluation ended with status CANCELLED", {"status": "CANCELLED", "failureCode": None}),
            (("eval", "report", "evaluation-1"), 200, cli.EVALUATION_FAILED, "EVALUATION_RESULT_FAILED",
             "One or more evaluation results failed",
             {"run": {"status": "COMPLETED"}, "results": [{"passed": False, "evidence": {"actual": 0}}]}),
            (("eval", "compare", "baseline-1", "--candidate-run-id", "evaluation-1"), 200, cli.EVALUATION_FAILED,
             "EVALUATION_REGRESSION", "Evaluation comparison reports a regression",
             {"baselineId": "baseline-1", "candidateRunId": "evaluation-1", "comparable": True,
              "baselinePassRate": 1.0, "candidatePassRate": 0.5, "passRateDelta": -0.5,
              "regression": True, "reasons": ["pass rate declined"]}),
        ]
        for arguments, http_status, expected_exit, expected_code, expected_message, payload in cases:
            with self.subTest(command=arguments[:2]):
                Server.response_payload, Server.response_status = payload, http_status
                status, output, error = self.invoke(*arguments)
                self.assertEqual(expected_exit, status); self.assertEqual("", output)
                self.assertEqual({
                    "schemaVersion": "agentworks.cli/v1", "ok": False,
                    "command": arguments[0] + " " + arguments[1], "exitCode": expected_exit,
                    "httpStatus": http_status,
                    "error": {"code": expected_code,
                              "message": expected_message,
                              "detail": {"result": payload, "response": {}}}}, json.loads(error))

    def test_nonterminal_typed_values_do_not_create_false_failures(self):
        Server.response_payload = {"status": "RUNNING", "completedResults": 1, "passedResults": 1}
        status, output, error = self.invoke("eval", "status", "evaluation-1")
        self.assertEqual(0, status, error); self.assertEqual("RUNNING", json.loads(output)["data"]["result"]["status"])
        Server.response_payload = {"items": [{"status": "FAILED"}], "nextCursor": None}
        status, output, error = self.invoke("eval", "datasets")
        self.assertEqual(0, status, error); self.assertEqual("FAILED", json.loads(output)["data"]["result"]["items"][0]["status"])
        Server.response_payload = {
            "baselineId": "baseline-1", "candidateRunId": "evaluation-1", "comparable": True,
            "baselinePassRate": 1.0, "candidatePassRate": 1.0, "passRateDelta": 0.0,
            "regression": False, "reasons": []}
        status, output, error = self.invoke(
            "eval", "compare", "baseline-1", "--candidate-run-id", "evaluation-1")
        self.assertEqual(0, status, error)
        self.assertFalse(json.loads(output)["data"]["result"]["regression"])

    def test_noncomparable_evaluation_is_a_redacted_typed_failure(self):
        payload = {
            "baselineId": "baseline-1", "candidateRunId": "evaluation-1", "comparable": False,
            "baselinePassRate": 1.0, "candidatePassRate": 0.0, "passRateDelta": -1.0,
            "regression": False, "reasons": ["credential awt_candidate_secret uses a different dataset"]}
        Server.response_payload = payload
        status, output, error = self.invoke(
            "eval", "compare", "baseline-1", "--candidate-run-id", "evaluation-1")
        self.assertEqual(cli.EVALUATION_FAILED, status); self.assertEqual("", output)
        value = json.loads(error)
        self.assertEqual("EVALUATION_NOT_COMPARABLE", value["error"]["code"])
        self.assertEqual("Evaluation comparison is not comparable", value["error"]["message"])
        expected = dict(payload)
        expected["reasons"] = ["credential [REDACTED] uses a different dataset"]
        self.assertEqual({"result": expected, "response": {}}, value["error"]["detail"])
        self.assertNotIn("awt_candidate_secret", error)

    def test_drain_authority_is_passed_through_and_retained_cost_fails_closed(self):
        cohort = {"state": "DRAINING", "drainStartedAt": "2026-10-15T00:00:00Z",
                  "drainDeadline": "2026-10-16T00:00:00Z"}
        Server.response_payload = cohort
        status, output, error = self.invoke("deploy", "cohort", "cohort-1")
        self.assertEqual(0, status, error); self.assertEqual(cohort, json.loads(output)["data"]["result"])
        report = {"quiescent": False, "sourceCutoff": "2026-10-15T12:00:00Z",
                  "drainDeadline": "2026-10-16T00:00:00Z", "drainDeadlineExceeded": False,
                  "blockers": [{"category": "RUN", "state": "PAUSED", "count": 1,
                                "oldestSince": "2026-10-15T01:00:00Z"}]}
        Server.response_payload = report
        status, output, error = self.invoke("deploy", "quiescence", "cohort-1")
        self.assertEqual(0, status, error); self.assertEqual(report, json.loads(output)["data"]["result"])
        request_count = len(Server.requests)
        status, _, error = self.invoke("deploy", "retained-cost", "cohort-1")
        self.assertEqual(cli.LIFECYCLE_CONFLICT, status)
        self.assertEqual("CAPABILITY_UNAVAILABLE", json.loads(error)["error"]["code"])
        self.assertEqual(request_count, len(Server.requests))

    def test_hand_built_path_segments_encode_slash_and_dot_navigation(self):
        with patch.object(cli.ApiClient, "request", return_value=(200, {}, {})) as request:
            status, _, error = self.invoke("deploy", "continue-waiting", "../cohort/child")
            self.assertEqual(0, status, error)
            self.assertEqual("/api/v1/cohorts/..%2Fcohort%2Fchild/quiescence", request.call_args.args[1])
        destination = Path(self.temp.name) / "artifact.bin"
        with patch.object(cli.ApiClient, "request", return_value=(200, b"artifact", {})) as request:
            status, _, error = self.invoke("artifact", "download", "../artifact/child", str(destination))
            self.assertEqual(0, status, error)
            self.assertEqual("/api/v1/artifacts/..%2Fartifact%2Fchild/content", request.call_args.args[1])


if __name__ == "__main__":
    unittest.main()
