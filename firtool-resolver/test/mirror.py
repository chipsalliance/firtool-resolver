#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Build a file: release mirror containing a valid archive and a deliberately bad digest."""
import os
import pathlib
import tarfile
import zipfile

mirror = pathlib.Path(os.environ["MIRROR"]).resolve()
release = mirror / "firtool-checksum-test"
release.mkdir()
binary = pathlib.Path(os.environ["FIRTOOL"])
for platform in ("linux-x64", "macos-x64", "macos-arm64", "windows-x64"):
    windows = platform == "windows-x64"
    archive = release / ("firrtl-bin-" + platform + (".zip" if windows else ".tar.gz"))
    entry = "firtool-checksum-test/bin/firtool" + (".exe" if windows else "")
    if windows:
        with zipfile.ZipFile(archive, "w", zipfile.ZIP_DEFLATED) as output:
            output.write(binary, entry)
    else:
        with tarfile.open(archive, "w:gz") as output:
            output.add(binary, entry)
    pathlib.Path(str(archive) + ".sha256").write_text("0" * 64 + "  " + archive.name + "\n")
print(mirror.as_uri())
