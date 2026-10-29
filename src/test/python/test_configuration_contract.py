import importlib.util
import json
import shutil
import tempfile
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[3]
MODULE_PATH = ROOT / "scripts/ci/verify_configuration_contract.py"
SPEC = importlib.util.spec_from_file_location("verify_configuration_contract", MODULE_PATH)
assert SPEC and SPEC.loader
contract = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(contract)


class ConfigurationContractTest(unittest.TestCase):
    def fixture(self) -> Path:
        temporary = Path(self.enterContext(tempfile.TemporaryDirectory()))
        for relative in (
            "agentworks-server/src/main/resources/META-INF/agentworks/configuration-schema.json",
            "agentworks-server/src/main/resources/META-INF/spring.factories",
            "docs/reference/configuration.md",
        ):
            target = temporary / relative
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(ROOT / relative, target)
        for relative in (
            "agentworks-server/src/main/java",
            "agentworks-spring-boot-starter/src/main/java",
        ):
            shutil.copytree(ROOT / relative, temporary / relative)
        return temporary

    def test_repository_contract_passes(self) -> None:
        self.assertEqual([], contract.problems(ROOT))

    def test_sensitive_metadata_cannot_be_weakened(self) -> None:
        fixture = self.fixture()
        path = fixture / contract.SCHEMA
        schema = json.loads(path.read_text(encoding="utf-8"))
        rule = next(item for item in schema["properties"] if item["name"].endswith("private-key"))
        rule["sensitive"] = False
        path.write_text(json.dumps(schema), encoding="utf-8")

        self.assertTrue(any("must be sensitive" in problem for problem in contract.problems(fixture)))

    def test_public_lookup_cannot_escape_the_catalogue(self) -> None:
        fixture = self.fixture()
        source = fixture / "agentworks-server/src/main/java/com/dvarahq/agentworks/autoconfigure/AgentWorksLocalDevelopment.java"
        source.write_text(source.read_text(encoding="utf-8").replace(
            'environment.getProperty(FLAG, Boolean.class, false)',
            'environment.getProperty("agentworks.uncatalogued.switch", Boolean.class, false)'), encoding="utf-8")

        self.assertTrue(any("agentworks.uncatalogued.switch" in problem for problem in contract.problems(fixture)))

    def test_early_validator_registration_is_mandatory(self) -> None:
        fixture = self.fixture()
        (fixture / "agentworks-server/src/main/resources/META-INF/spring.factories").unlink()

        self.assertTrue(any("before application-context creation" in problem for problem in contract.problems(fixture)))


if __name__ == "__main__":
    unittest.main()
