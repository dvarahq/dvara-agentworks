#!/usr/bin/env python3
"""Create a byte-for-byte reproducible Helm chart archive."""

from __future__ import annotations

import argparse
import gzip
import io
import tarfile
from pathlib import Path


def package(source: Path, output: Path, epoch: int) -> None:
    root = source.name
    output.parent.mkdir(parents=True, exist_ok=True)
    buffer = io.BytesIO()
    with tarfile.open(fileobj=buffer, mode="w", format=tarfile.PAX_FORMAT) as archive:
        for path in [source, *sorted(source.rglob("*"))]:
            relative = Path(root) / path.relative_to(source)
            info = archive.gettarinfo(str(path), arcname=relative.as_posix())
            info.uid = info.gid = 0
            info.uname = info.gname = "root"
            info.mtime = epoch
            info.pax_headers = {}
            if path.is_file():
                with path.open("rb") as stream:
                    archive.addfile(info, stream)
            else:
                archive.addfile(info)
    with output.open("wb") as stream:
        with gzip.GzipFile(filename="", mode="wb", fileobj=stream, mtime=epoch, compresslevel=9) as zipped:
            zipped.write(buffer.getvalue())


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--source", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--source-date-epoch", type=int, required=True)
    args = parser.parse_args()
    package(args.source.resolve(), args.output.resolve(), args.source_date_epoch)


if __name__ == "__main__":
    main()
