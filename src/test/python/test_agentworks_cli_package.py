import json
import os
import subprocess
import tempfile
import threading
import unittest
import zipfile
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]


class PackageServer(BaseHTTPRequestHandler):
    authorization = None

    def log_message(self, *_):
        pass

    def do_GET(self):
        PackageServer.authorization = self.headers.get("Authorization")
        payload = json.dumps({"items": [{"name": "packaged-agent"}], "nextCursor": None}).encode()
        self.send_response(200); self.send_header("Content-Type", "application/json"); self.end_headers()
        self.wfile.write(payload)


class PackagedCliJourney(unittest.TestCase):
    def test_clean_package_runs_without_repository_imports(self):
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory) / "package"
            subprocess.run([str(ROOT / "scripts" / "build-cli.sh"), str(output)], cwd="/tmp", check=True,
                           stdout=subprocess.PIPE, text=True)
            binary = output / "agentworks"
            server = ThreadingHTTPServer(("127.0.0.1", 0), PackageServer)
            thread = threading.Thread(target=server.serve_forever, daemon=True); thread.start()
            try:
                environment = {"PATH": os.environ.get("PATH", ""), "XDG_CONFIG_HOME": str(Path(directory) / "config"),
                               "AGENTWORKS_TOKEN": "awt_package_test",
                               "AGENTWORKS_URL": f"http://127.0.0.1:{server.server_port}"}
                result = subprocess.run([str(binary), "--output", "json", "agent", "list"], cwd="/tmp",
                                        env=environment, check=True, stdout=subprocess.PIPE, text=True)
                value = json.loads(result.stdout)
                self.assertEqual("agentworks.cli/v1", value["schemaVersion"])
                self.assertEqual("packaged-agent", value["data"]["result"]["items"][0]["name"])
                self.assertEqual("Bearer awt_package_test", PackageServer.authorization)
                self.assertNotIn("awt_package_test", result.stdout)
            finally:
                server.shutdown(); server.server_close(); thread.join()
            with zipfile.ZipFile(binary) as archive:
                self.assertFalse(any("__pycache__" in name or name.endswith(".pyc") for name in archive.namelist()))

    def test_package_is_reproducible(self):
        with tempfile.TemporaryDirectory() as directory:
            first, second = Path(directory) / "first", Path(directory) / "second"
            subprocess.run([str(ROOT / "scripts" / "build-cli.sh"), str(first)], check=True,
                           stdout=subprocess.PIPE, text=True)
            subprocess.run([str(ROOT / "scripts" / "build-cli.sh"), str(second)], check=True,
                           stdout=subprocess.PIPE, text=True)
            self.assertEqual((first / "agentworks").read_bytes(), (second / "agentworks").read_bytes())


if __name__ == "__main__":
    unittest.main()
