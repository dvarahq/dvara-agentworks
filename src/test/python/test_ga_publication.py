import importlib.util
import json
import subprocess
import sys
import tempfile
import unittest
import zipfile
from datetime import datetime, timezone
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
MODULE_PATH = ROOT / "scripts/release/verify_ga_publication.py"
SPEC = importlib.util.spec_from_file_location("verify_ga_publication", MODULE_PATH)
MODULE = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = MODULE
SPEC.loader.exec_module(MODULE)
OBSERVED_NOW = datetime(2027, 3, 1, tzinfo=timezone.utc)


def write_json(path: Path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, sort_keys=True) + "\n", encoding="utf-8")


def valid_evidence(base: Path):
    policy = MODULE.load(ROOT / MODULE.POLICY)
    commit = "a" * 40
    prerequisites = []
    for number in policy["requiredPrerequisiteIssues"]:
        path = base / f"issue-{number}.json"
        verdict = "GO" if number == 6 else "PASS"
        write_json(path, {"issue": number, "release": {"commit": commit}, "verdict": verdict})
        prerequisites.append({"issue": number, "status": "CLOSED", "commit": commit,
                              "evidence": str(path), "evidenceSha256": MODULE.digest(path)})
    candidate = base / "candidate"
    candidate.mkdir()
    source = {"repository": MODULE.PUBLIC_REPOSITORY, "tag": "v1.0.0", "commit": commit,
              "releaseQualified": True, "sourceDateEpoch": 1800000000, "githubActor": "grabdoc"}
    write_json(candidate / "SOURCE.json", source)
    write_json(candidate / "MANIFEST.json", {"source": source})
    (candidate / "SHA256SUMS").write_text("placeholder\n", encoding="utf-8")

    artifacts = []
    for family in policy["requiredArtifactFamilies"]:
        path = base / "artifacts" / f"agentworks-1.0.0-{family}.txt"
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(f"Dvara AgentWorks Core 1.0.0 {family}\n", encoding="utf-8")
        artifacts.append({"family": family, "name": path.name, "path": str(path),
                          "sha256": MODULE.digest(path), "destination": policy["destinations"][family],
                          "status": "PUBLISHED", "commit": commit})
    inventory = base / "publication-inventory.json"
    write_json(inventory, {"release": {"version": "1.0.0", "tag": "v1.0.0", "commit": commit},
                           "githubActor": "grabdoc", "artifacts": artifacts})
    gates = {}
    for name in policy["requiredVerificationGates"]:
        path = base / "gates" / f"{name}.json"
        write_json(path, {"gate": name, "status": "PASS", "commit": commit})
        gates[name] = {"status": "PASS", "commit": commit,
                       "evidence": {"path": str(path), "sha256": MODULE.digest(path)}}
    index = base / "artifact-index.json"
    write_json(index, {"version": "1.0.0", "tag": "v1.0.0", "commit": commit,
                       "githubActor": "grabdoc", "artifacts": artifacts})
    bundle = base / "artifact-index.sigstore.json"
    write_json(bundle, {"verificationMaterial": {}})
    return {
        "schemaVersion": 1,
        "release": {"version": "1.0.0", "tag": "v1.0.0", "repository": MODULE.PUBLIC_REPOSITORY,
                    "commit": commit, "dirty": False},
        "github": {"actor": "grabdoc", "repository": MODULE.PUBLIC_GITHUB_REPOSITORY},
        "prerequisites": prerequisites,
        "candidate": {"path": str(candidate), "manifestSha256": MODULE.digest(candidate / "MANIFEST.json"),
                      "checksumsSha256": MODULE.digest(candidate / "SHA256SUMS")},
        "inventory": {"path": str(inventory), "sha256": MODULE.digest(inventory)},
        "artifacts": artifacts,
        "gates": gates,
        "promotion": {"status": "COMPLETE", "publishedAt": "2027-02-26T12:00:00Z",
                      "advertisedAt": "2027-02-26T12:05:00Z"},
        "index": {"path": str(index), "sha256": MODULE.digest(index),
                  "sigstoreBundlePath": str(bundle), "sigstoreBundleSha256": MODULE.digest(bundle),
                  "certificateIdentity": policy["indexSigning"]["certificateIdentity"],
                  "certificateOidcIssuer": policy["indexSigning"]["certificateOidcIssuer"]},
        "verdict": "PASS", "verdictAt": "2027-02-26T12:06:00Z",
    }


class GaPublicationTest(unittest.TestCase):
    def problems(self, evidence, base):
        return MODULE.evidence_problems(evidence, ROOT, evidence_base=base,
                                        verify_external=False, now=OBSERVED_NOW)

    def test_repository_policy_is_internally_consistent(self):
        self.assertEqual([], MODULE.policy_problems(ROOT))
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            policy = MODULE.load(ROOT / MODULE.POLICY)
            template = MODULE.load(ROOT / MODULE.TEMPLATE)
            policy["requiredArtifactFamilies"][0] = "replacement"
            policy["destinations"]["replacement"] = policy["destinations"].pop("maven-bom")
            policy["requiredVerificationGates"][0] = "replacement-gate"
            write_json(root / MODULE.POLICY, policy)
            write_json(root / MODULE.TEMPLATE, template)
            problems = MODULE.policy_problems(root)
            self.assertTrue(any("frozen nine-family" in problem for problem in problems))
            self.assertTrue(any("frozen public destinations" in problem for problem in problems))
            self.assertTrue(any("frozen nine-gate" in problem for problem in problems))

    def test_complete_exact_commit_publication_can_pass(self):
        with tempfile.TemporaryDirectory() as directory:
            base = Path(directory)
            self.assertEqual([], self.problems(valid_evidence(base), base))

    def test_pending_template_cannot_pass(self):
        evidence = MODULE.load(ROOT / MODULE.TEMPLATE)
        problems = MODULE.evidence_problems(evidence, ROOT, verify_external=False, now=OBSERVED_NOW)
        self.assertTrue(any("not exact and clean" in problem for problem in problems))
        self.assertTrue(any("no explicit PASS" in problem for problem in problems))

    def test_only_grabdoc_can_be_the_github_actor(self):
        with tempfile.TemporaryDirectory() as directory:
            base = Path(directory)
            evidence = valid_evidence(base)
            evidence["github"]["actor"] = "another-user"
            self.assertTrue(any("sole allowed identity" in problem for problem in self.problems(evidence, base)))

    def test_release_evidence_tool_rejects_every_other_github_actor(self):
        with tempfile.TemporaryDirectory() as directory:
            base = Path(directory)
            output = base / "output"
            output.mkdir()
            sentinel = output / "existing-evidence"
            sentinel.write_text("keep\n", encoding="utf-8")
            command = [sys.executable, str(ROOT / "scripts/release/create_release_evidence.py"),
                       "--artifacts", str(base / "artifacts"), "--output", str(output),
                       "--commit", "a" * 40, "--tag", "v1.0.0", "--source-date-epoch", "1800000000"]
            for arguments in (command, [*command, "--github-actor", "another-user"]):
                result = subprocess.run(arguments, cwd=ROOT, capture_output=True, text=True, check=False)
                self.assertNotEqual(0, result.returncode)
                self.assertIn("requires GitHub actor grabdoc", result.stdout + result.stderr)
                self.assertEqual("keep\n", sentinel.read_text(encoding="utf-8"))

    def test_destination_and_complete_family_are_fail_closed(self):
        with tempfile.TemporaryDirectory() as directory:
            base = Path(directory)
            evidence = valid_evidence(base)
            evidence["artifacts"][0]["destination"] = "https://invalid.example/releases"
            evidence["artifacts"].pop()
            problems = self.problems(evidence, base)
            self.assertTrue(any("destination differs" in problem for problem in problems))
            self.assertTrue(any("all required families" in problem for problem in problems))

    def test_artifact_bytes_are_digest_bound(self):
        with tempfile.TemporaryDirectory() as directory:
            base = Path(directory)
            evidence = valid_evidence(base)
            Path(evidence["artifacts"][0]["path"]).write_text("tampered\n", encoding="utf-8")
            self.assertTrue(any("artifact 0 SHA-256" in problem for problem in self.problems(evidence, base)))

    def test_candidate_and_prerequisite_bodies_must_authorize_the_exact_ga_commit(self):
        with tempfile.TemporaryDirectory() as directory:
            base = Path(directory)
            evidence = valid_evidence(base)
            source = Path(evidence["candidate"]["path"]) / "SOURCE.json"
            body = json.loads(source.read_text(encoding="utf-8"))
            body["tag"] = "v1.0.1"
            write_json(source, body)
            prerequisite = Path(evidence["prerequisites"][0]["evidence"])
            write_json(prerequisite, {"issue": 6, "release": {"commit": "b" * 40}, "verdict": "FAIL"})
            evidence["prerequisites"][0]["evidenceSha256"] = MODULE.digest(prerequisite)
            problems = self.problems(evidence, base)
            self.assertTrue(any("exact release-qualified public commit" in problem for problem in problems))
            self.assertTrue(any("issue #6 evidence does not authorize" in problem for problem in problems))

    def test_duplicate_destination_name_is_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            base = Path(directory)
            evidence = valid_evidence(base)
            evidence["artifacts"][1]["destination"] = evidence["artifacts"][0]["destination"]
            evidence["artifacts"][1]["name"] = evidence["artifacts"][0]["name"]
            self.assertTrue(any("duplicates a published destination/name" in problem
                                for problem in self.problems(evidence, base)))

    def test_malformed_artifact_family_fails_without_crashing(self):
        with tempfile.TemporaryDirectory() as directory:
            base = Path(directory)
            evidence = valid_evidence(base)
            evidence["artifacts"][0]["family"] = []
            self.assertTrue(any("family is not a string" in problem for problem in self.problems(evidence, base)))
            nested = base / "nested"
            nested.mkdir()
            evidence = valid_evidence(nested)
            evidence["prerequisites"].append("not-an-object")
            write_json(Path(evidence["inventory"]["path"]), [])
            evidence["inventory"]["sha256"] = MODULE.digest(Path(evidence["inventory"]["path"]))
            write_json(Path(evidence["index"]["path"]), [])
            evidence["index"]["sha256"] = MODULE.digest(Path(evidence["index"]["path"]))
            problems = self.problems(evidence, base)
            self.assertTrue(any("prerequisite evidence differs" in problem for problem in problems))
            self.assertTrue(any("inventory is not an object" in problem for problem in problems))
            self.assertTrue(any("index is not an object" in problem for problem in problems))

    def test_private_marker_inside_archive_is_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            base = Path(directory)
            evidence = valid_evidence(base)
            artifact = evidence["artifacts"][0]
            path = Path(artifact["path"])
            archive = path.with_suffix(".zip")
            with zipfile.ZipFile(archive, "w") as output:
                output.writestr("metadata.txt", MODULE.PRIVATE_MARKERS[0])
            artifact["path"] = str(archive)
            artifact["name"] = archive.name
            artifact["sha256"] = MODULE.digest(archive)
            self.assertTrue(any("private marker" in problem for problem in self.problems(evidence, base)))

    def test_inventory_and_index_cannot_disagree_with_evidence(self):
        with tempfile.TemporaryDirectory() as directory:
            base = Path(directory)
            evidence = valid_evidence(base)
            evidence["artifacts"][0]["name"] = "different.txt"
            problems = self.problems(evidence, base)
            self.assertTrue(any("digest-bound inventory" in problem for problem in problems))
            self.assertTrue(any("index differs" in problem for problem in problems))

    def test_every_gate_requires_digest_bound_exact_commit_evidence(self):
        with tempfile.TemporaryDirectory() as directory:
            base = Path(directory)
            evidence = valid_evidence(base)
            evidence["gates"]["source-build"]["commit"] = "b" * 40
            evidence["gates"]["cli-smoke"]["evidence"]["sha256"] = "0" * 64
            problems = self.problems(evidence, base)
            self.assertTrue(any("source-build did not pass" in problem for problem in problems))
            self.assertTrue(any("cli-smoke evidence SHA-256" in problem for problem in problems))

    def test_advertising_before_complete_publication_is_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            base = Path(directory)
            evidence = valid_evidence(base)
            evidence["promotion"]["advertisedAt"] = "2027-02-26T11:59:00Z"
            self.assertTrue(any("advertised before" in problem for problem in self.problems(evidence, base)))

    def test_snapshot_artifact_is_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            base = Path(directory)
            evidence = valid_evidence(base)
            evidence["artifacts"][0]["name"] = "agentworks-1.0.0-SNAPSHOT.jar"
            self.assertTrue(any("snapshot identity" in problem for problem in self.problems(evidence, base)))

    def test_non_standard_json_constants_are_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "evidence.json"
            path.write_text('{"value": NaN}', encoding="utf-8")
            with self.assertRaisesRegex(ValueError, "non-standard JSON constant"):
                MODULE.load(path)


if __name__ == "__main__":
    unittest.main()
