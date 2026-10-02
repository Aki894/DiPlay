#!/usr/bin/env python3
"""Explicitly provision the upstream public preview's runtime assets outside Git.

Only the pinned, published APK is accepted. Android signing keys are never read.
Do not print asset bytes or put the output directory in a source/artifact upload.
"""
import argparse
import hashlib
import os
from pathlib import Path
import zipfile

EXPECTED_SHA256 = "f113b534f63542b3df4f7e378f479f85c24bf0c895b12ea22dbbd64a0497db05"
ASSETS = ("identity.pk8", "certificate.p7b")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("apk", type=Path)
    parser.add_argument("output", type=Path)
    args = parser.parse_args()
    repository = Path(__file__).resolve().parents[1]
    output = args.output.resolve()
    if output == repository or repository in output.parents:
        parser.error("Runtime assets must be outside the repository")
    if hashlib.sha256(args.apk.read_bytes()).hexdigest() != EXPECTED_SHA256:
        parser.error("APK does not match the pinned upstream DiPlay 0.2.9 release")
    selected = {}
    with zipfile.ZipFile(args.apk) as archive:
        for name in ASSETS:
            info = archive.getinfo(f"assets/offline-mfi/{name}")
            if not 1 <= info.file_size <= 1024 * 1024:
                parser.error("Unexpected runtime asset size")
            selected[name] = archive.read(info)
    directory = output / "offline-mfi"
    directory.mkdir(parents=True, exist_ok=True, mode=0o700)
    for name, data in selected.items():
        path = directory / name
        with path.open("wb") as stream:
            os.chmod(path, 0o600)
            stream.write(data)
    print("Pinned upstream preview runtime assets provisioned; no Android signing material imported.")


if __name__ == "__main__":
    main()
