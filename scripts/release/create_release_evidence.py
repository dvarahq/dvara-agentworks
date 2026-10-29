#!/usr/bin/env python3
"""Bind release artifacts to deterministic SBOM, checksum, source, and provenance evidence."""

from __future__ import annotations

import argparse
import hashlib
import json
import re
import shutil
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
CANONICAL_SOURCE = "https://github.com/dvarahq/dvara-agentworks"
KINDS = {"maven", "cli", "oci", "helm"}


def digest(path: Path) -> str:
    value = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            value.update(block)
    return value.hexdigest()


def write_json(path: Path, data: object) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(data, indent=2, sort_keys=True) + "\n", encoding="utf-8")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--artifacts", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--commit", required=True)
    parser.add_argument("--tag", required=True)
    parser.add_argument("--source-date-epoch", type=int, required=True)
    parser.add_argument("--qualification", action="store_true")
    parser.add_argument("--github-actor")
    args = parser.parse_args()
    if not re.fullmatch(r"[0-9a-f]{40}", args.commit):
        raise SystemExit("release-evidence=FAIL: commit must be a full SHA-1 identity")
    if not re.fullmatch(r"v\d+\.\d+\.\d+(?:-[0-9A-Za-z.-]+)?", args.tag):
        raise SystemExit("release-evidence=FAIL: tag is not a supported immutable release tag")
    if args.source_date_epoch < 315532802:
        raise SystemExit("release-evidence=FAIL: SOURCE_DATE_EPOCH is outside the ZIP-compatible range")
    if args.qualification:
        if args.github_actor is not None:
            raise SystemExit("release-evidence=FAIL: qualification evidence cannot claim a GitHub actor")
    elif args.github_actor != "grabdoc":
        raise SystemExit("release-evidence=FAIL: release-qualified evidence requires GitHub actor grabdoc")
    try:
        args.output.mkdir(parents=True)
    except FileExistsError:
        raise SystemExit("release-evidence=FAIL: output already exists; choose a fresh directory") from None
    artifacts: list[dict] = []
    seen_kinds: set[str] = set()
    for source in sorted(path for path in args.artifacts.rglob("*") if path.is_file()):
        relative = source.relative_to(args.artifacts)
        if not relative.parts or relative.parts[0] not in KINDS:
            raise SystemExit(f"release-evidence=FAIL: artifact has no supported kind: {relative}")
        kind = relative.parts[0]
        seen_kinds.add(kind)
        target = args.output / "artifacts" / relative
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(source, target)
        sha256 = digest(target)
        artifact_name = target.relative_to(args.output).as_posix()
        sbom_name = f"evidence/sbom/{artifact_name.replace('/', '__')}.spdx.json"
        provenance_name = f"evidence/provenance/{artifact_name.replace('/', '__')}.intoto.json"
        write_json(args.output / sbom_name, {
            "SPDXID": "SPDXRef-DOCUMENT", "spdxVersion": "SPDX-2.3", "dataLicense": "CC0-1.0",
            "name": artifact_name, "documentNamespace": f"{CANONICAL_SOURCE}/sbom/{args.commit}/{sha256}",
            "creationInfo": {"creators": ["Tool: agentworks-release-evidence-v1"]},
            "packages": [{"SPDXID": "SPDXRef-Artifact", "name": source.name,
                          "downloadLocation": "NOASSERTION", "filesAnalyzed": False,
                          "licenseConcluded": "Apache-2.0", "licenseDeclared": "Apache-2.0",
                          "checksums": [{"algorithm": "SHA256", "checksumValue": sha256}],
                          "externalRefs": [{"referenceCategory": "OTHER", "referenceType": "source-commit",
                                            "referenceLocator": args.commit}]}],
        })
        write_json(args.output / provenance_name, {
            "_type": "https://in-toto.io/Statement/v1",
            "subject": [{"name": artifact_name, "digest": {"sha256": sha256}}],
            "predicateType": "https://slsa.dev/provenance/v1",
            "predicate": {
                "buildDefinition": {"buildType": f"{CANONICAL_SOURCE}/build-types/oss-release/v1",
                                    "externalParameters": {"tag": args.tag, "sourceDateEpoch": args.source_date_epoch},
                                    "resolvedDependencies": [{"uri": f"git+{CANONICAL_SOURCE}@refs/tags/{args.tag}",
                                                              "digest": {"gitCommit": args.commit}}]},
                "runDetails": {"builder": {"id": f"{CANONICAL_SOURCE}/.github/workflows/release.yml"},
                               "metadata": {"invocationId": "qualification" if args.qualification else args.tag}},
            },
        })
        artifacts.append({"kind": kind, "path": artifact_name, "sha256": sha256,
                          "sbom": sbom_name, "provenance": provenance_name})
    missing = KINDS - seen_kinds
    if missing:
        raise SystemExit(f"release-evidence=FAIL: missing artifact kinds: {', '.join(sorted(missing))}")
    legal_hashes = {}
    for name in ("LICENSE", "NOTICE", "SOURCE-OFFER.md"):
        source = ROOT / "legal/oss" / name
        target = args.output / "legal" / name
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(source, target)
        legal_hashes[name] = digest(target)
    source_record = {
        "repository": CANONICAL_SOURCE, "tag": args.tag, "commit": args.commit,
        "sourceDateEpoch": args.source_date_epoch, "releaseQualified": not args.qualification,
    }
    if args.github_actor is not None:
        source_record["githubActor"] = args.github_actor
    write_json(args.output / "SOURCE.json", source_record)
    write_json(args.output / "MANIFEST.json", {"version": 1, "artifacts": artifacts,
                                                "legal": legal_hashes, "source": source_record})
    checksum_paths = [path for path in args.output.rglob("*") if path.is_file()]
    lines = [f"{digest(path)}  {path.relative_to(args.output).as_posix()}" for path in sorted(checksum_paths)]
    (args.output / "SHA256SUMS").write_text("\n".join(lines) + "\n", encoding="utf-8")
    print(f"release-evidence=PASS artifacts={len(artifacts)} qualification={str(args.qualification).lower()}")


if __name__ == "__main__":
    main()
