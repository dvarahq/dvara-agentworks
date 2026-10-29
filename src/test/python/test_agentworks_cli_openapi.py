import re
import sys
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(ROOT / "cli"))
from agentworks.main import CUSTOM_ENDPOINTS, ENDPOINTS

OPENAPI = ROOT / "agentworks-server" / "src" / "main" / "resources" / "api" / "agentworks-api-v1.openapi.yaml"
METHOD = re.compile(r"^    (get|post|put|patch|delete):$")
PATH = re.compile(r"^  (/api/v1/[^:]+):$")


def normalized(path):
    return re.sub(r"\{[^}]+}", "{}", path)


def wire_name(name):
    first, *rest = name.split("_")
    return first + "".join(item.capitalize() for item in rest)


class OpenApiIndex:
    def __init__(self, source):
        self.lines = source.splitlines()
        self.operations = {}
        current_path = None
        for index, line in enumerate(self.lines):
            path_match = PATH.match(line)
            if path_match:
                current_path = path_match.group(1)
                continue
            method_match = METHOD.match(line)
            if current_path and method_match:
                end = index + 1
                while end < len(self.lines) and not PATH.match(self.lines[end]) and not METHOD.match(self.lines[end]):
                    end += 1
                self.operations[(current_path, method_match.group(1).upper())] = "\n".join(self.lines[index:end])

    def lookup(self, key, endpoint):
        candidates = [(path, block) for (path, method), block in self.operations.items()
                      if method == endpoint.method and normalized(path) == normalized(endpoint.path)]
        action_paths = {
            ("deploy", "suspend"): "/api/v1/cohorts/{id}/{action}",
            ("deploy", "resume"): "/api/v1/cohorts/{id}/{action}",
            ("deploy", "revoke"): "/api/v1/cohorts/{id}/{action}",
            ("release", "suspend"): "/api/v1/releases/{id}/{action}",
            ("release", "resume"): "/api/v1/releases/{id}/{action}",
            ("release", "revoke"): "/api/v1/releases/{id}/{action}",
            ("release", "retire"): "/api/v1/releases/{id}/{action}",
        }
        if not candidates and key in action_paths:
            candidates = [(path, block) for (path, method), block in self.operations.items()
                          if method == endpoint.method and path == action_paths[key]]
            self_test = candidates[0][1] if candidates else ""
            if key[1] not in re.findall(r"enum: \[([^]]+)]", self_test)[0].replace(" ", "").split(","):
                candidates = []
        if len(candidates) != 1:
            raise AssertionError(f"{key} resolves to {len(candidates)} OpenAPI operations: {endpoint}")
        return candidates[0]


def query_parameters(block):
    values = set()
    parameter_match = re.search(r"^      parameters:(.*?)(?=^      [a-zA-Z][a-zA-Z-]*:|\Z)",
                                block, re.MULTILINE | re.DOTALL)
    if not parameter_match:
        return values
    parameters = parameter_match.group(1)
    for reference, name in (("Cursor", "cursor"), ("Limit", "limit")):
        if f"#/components/parameters/{reference}" in parameters:
            values.add(name)
    inline = re.findall(r"name:\s*([A-Za-z0-9-]+),\s*in:\s*query", parameters)
    values.update(inline)
    items = re.split(r"(?=^        - )", parameters, flags=re.MULTILINE)
    for item in items:
        if re.search(r"\bin:\s*query\b", item):
            match = re.search(r"\bname:\s*([A-Za-z0-9-]+)", item)
            if match:
                values.add(match.group(1))
    return values


def request_body_cardinality(block):
    match = re.search(r"^      requestBody:(.*?)(?=^      responses:|\Z)",
                      block, re.MULTILINE | re.DOTALL)
    if not match:
        return "absent"
    return "required" if re.search(r"\brequired:\s*true\b", match.group(0)) else "optional"


class CliOpenApiContractTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.index = OpenApiIndex(OPENAPI.read_text(encoding="utf-8"))

    def test_every_remote_cli_endpoint_matches_the_published_contract(self):
        for key, endpoint in {**ENDPOINTS, **CUSTOM_ENDPOINTS}.items():
            with self.subTest(command=key):
                path, block = self.index.lookup(key, endpoint)
                self.assertEqual(endpoint.method, METHOD.match(block.splitlines()[0]).group(1).upper())
                if key not in {("deploy", "suspend"), ("deploy", "resume"), ("deploy", "revoke"),
                               ("release", "suspend"), ("release", "resume"), ("release", "revoke"),
                               ("release", "retire")}:
                    self.assertEqual(normalized(endpoint.path), normalized(path))
                match = re.search(r"^      x-agentworks-idempotency:\s*(\w+)", block, re.MULTILINE)
                self.assertEqual(endpoint.idempotency, match.group(1) if match else "none")
                expected_etag = ("required" if "#/components/parameters/IfMatchRequired" in block else
                                 "optional" if "#/components/parameters/IfMatchOptional" in block else "none")
                self.assertEqual(endpoint.etag, expected_etag)
                self.assertEqual(endpoint.body, request_body_cardinality(block))
                self.assertEqual({wire_name(name) for name in endpoint.query}, query_parameters(block))
                expected_public = bool(re.search(
                    r"^      x-agentworks-authorization:\s*\{need:\s*PUBLIC\}", block, re.MULTILINE))
                self.assertEqual(endpoint.public, expected_public)
                if endpoint.public:
                    self.assertRegex(block, r"(?m)^      security:\s*\[\]")
        openapi = ENDPOINTS[("diagnostic", "openapi")]
        _, block = self.index.lookup(("diagnostic", "openapi"), openapi)
        responses = block.split("      responses:", 1)[1]
        self.assertEqual("application/yaml", openapi.accept)
        self.assertIn("application/yaml:", responses)


if __name__ == "__main__":
    unittest.main()
