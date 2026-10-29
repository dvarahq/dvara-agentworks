#!/usr/bin/env python3
"""Refuse a change to a database migration that a published release already shipped (#201).

Flyway records each applied migration's checksum and refuses to start against a database whose applied migration
has since changed, so a released migration can never be edited or removed: a fix goes in a new migration. Every tag
named v* is a published release. Each migration file present in a release tag must still exist, byte for byte, in
the working tree. Until the first release is tagged there is nothing to protect, and the gate passes.

Each tag costs two git processes, whatever its number of migrations: one lists the tag's migrations with their blob
ids, and one hashes the working tree's copies of them (#206). A git failure, for example a v* tag that names no
commit, fails the gate with its message instead of a traceback.
"""

import os
import subprocess
from pathlib import Path

ROOT = Path(os.environ.get("AGENTWORKS_REPOSITORY_ROOT", Path(__file__).resolve().parents[2]))
MIGRATIONS = "agentworks-storage-jdbc/src/main/resources/db"


def git(root: Path, *arguments: str) -> str:
    return subprocess.run(["git", *arguments], cwd=root, text=True, capture_output=True, check=True).stdout


def released_migrations(root: Path, tag: str) -> dict[str, str]:
    """The migration files of {tag}, by path, with their blob ids."""
    released = {}
    # Each line is "<mode> <type> <object>\t<path>"; -z keeps any path intact.
    for entry in git(root, "ls-tree", "-r", "-z", f"{tag}^{{commit}}", "--", MIGRATIONS).split("\0"):
        if not entry:
            continue
        meta, path = entry.split("\t", 1)
        if path.endswith(".sql"):
            released[path] = meta.split()[2]
    return released


def tag_problems(root: Path, tag: str) -> list[str]:
    released = released_migrations(root, tag)
    found = []
    present = []
    for path in sorted(released):
        if (root / path).is_file():
            present.append(path)
        else:
            found.append(f"{path} was released in {tag} and is removed")
    if present:
        current = git(root, "hash-object", "--", *present).split()
        for path, blob in zip(present, current, strict=True):
            if blob != released[path]:
                found.append(f"{path} was released in {tag} and is modified; add a new migration instead")
    return found


def problems(root: Path) -> list[str]:
    try:
        tags = git(root, "tag", "--list", "v*").split()
    except (subprocess.CalledProcessError, FileNotFoundError) as unreadable:
        return [f"cannot list release tags: {unreadable}"]
    found = []
    for tag in sorted(tags):
        try:
            found.extend(tag_problems(root, tag))
        except subprocess.CalledProcessError as unreadable:
            detail = (unreadable.stderr or "").strip() or str(unreadable)
            found.append(f"cannot read the migrations of release tag {tag}: {detail}")
    return found


def main() -> None:
    if found := problems(ROOT):
        raise SystemExit("migration-immutability=FAIL: " + "; ".join(found))
    print("migration-immutability=PASS")


if __name__ == "__main__":
    main()
