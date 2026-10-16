from __future__ import annotations

import bz2
import gzip
import io
import zipfile
from contextlib import contextmanager
from pathlib import Path
from typing import Iterator, BinaryIO, TextIO


def detect_compression(path: Path) -> str:
    """Identify common archive compression from file signatures, not suffixes."""
    with path.open("rb") as handle:
        magic = handle.read(4)
    if magic.startswith(b"\x1f\x8b"):
        return "gzip"
    if magic.startswith(b"BZh"):
        return "bzip2"
    if magic.startswith(b"PK\x03\x04") or magic.startswith(b"PK\x05\x06"):
        return "zip"
    return "none"


def _choose_zip_member(archive: zipfile.ZipFile, expected_suffix: str | None) -> str:
    files = sorted(name for name in archive.namelist() if not name.endswith("/"))
    if expected_suffix:
        suffix = expected_suffix.casefold()
        matches = [
            name for name in files
            if name.casefold().endswith((suffix, suffix + ".gz", suffix + ".bz2", suffix + ".gzip", suffix + ".bzip2"))
        ]
        if len(matches) == 1:
            return matches[0]
        if len(matches) > 1:
            raise ValueError(f"ZIP contains multiple {expected_suffix} members: {matches[:8]}")
    if len(files) == 1:
        return files[0]
    raise ValueError(f"ZIP has no unique supported member; found {len(files)} files")


@contextmanager
def open_binary_auto(path: Path, expected_suffix: str | None = None) -> Iterator[BinaryIO]:
    """Open plain, gzip, bzip2, or a ZIP containing one requested data file."""
    compression = detect_compression(path)
    if compression == "gzip":
        with gzip.open(path, "rb") as handle:
            yield handle
        return
    if compression == "bzip2":
        with bz2.open(path, "rb") as handle:
            yield handle
        return
    if compression == "zip":
        with zipfile.ZipFile(path) as archive:
            member = _choose_zip_member(archive, expected_suffix)
            with archive.open(member, "r") as raw:
                low = member.casefold()
                if low.endswith((".gz", ".gzip")):
                    with gzip.GzipFile(fileobj=raw, mode="rb") as handle:
                        yield handle
                elif low.endswith((".bz2", ".bzip2")):
                    with bz2.BZ2File(raw, mode="rb") as handle:
                        yield handle
                else:
                    yield raw
        return
    with path.open("rb") as handle:
        yield handle


@contextmanager
def open_text_auto(
    path: Path,
    encoding: str = "utf-8",
    errors: str = "strict",
    expected_suffix: str | None = None,
) -> Iterator[TextIO]:
    with open_binary_auto(path, expected_suffix) as binary:
        text = io.TextIOWrapper(binary, encoding=encoding, errors=errors, newline="")
        try:
            yield text
        finally:
            # TextIOWrapper owns the decompressor stream; it must be detached
            # before the surrounding context closes its archive handle.
            try:
                text.detach()
            except (ValueError, OSError):
                pass
