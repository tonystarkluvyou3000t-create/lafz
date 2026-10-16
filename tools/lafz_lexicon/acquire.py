from __future__ import annotations

import hashlib
import json
import os
import urllib.error
import urllib.request
from datetime import datetime, timezone
from pathlib import Path

from .archive_io import detect_compression

PACKAGE_DIR = Path(__file__).resolve().parent


def source_registry() -> dict:
    return json.loads((PACKAGE_DIR / "source_registry.json").read_text(encoding="utf-8"))


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def download_source(source_id: str, cache_dir: Path, timeout: int = 60) -> tuple[Path, str]:
    registry = source_registry()
    source = next((item for item in registry["sources"] if item["id"] == source_id), None)
    if not source:
        raise ValueError(f"Unknown source id: {source_id}")
    if source_id in {"kaikki_english", "simple_wiktionary", "tatoeba_cc0", "tatoeba_english", "princeton_wordnet_3_0_reference"}:
        raise ValueError(f"{source_id} is optional or comparison-only; pass a reviewed local file instead of downloading it automatically")
    cache_dir.mkdir(parents=True, exist_ok=True)
    suffix = ".xml.gz" if source_id == "oewn_2025" else ".dict" if source_id == "cmudict" else ".csv"
    path = cache_dir / f"{source_id}{suffix}"
    manifest_path = cache_dir / "source_manifest.json"

    def record_manifest(digest: str) -> None:
        manifest = json.loads(manifest_path.read_text(encoding="utf-8")) if manifest_path.exists() else {"downloads": {}}
        previous = manifest["downloads"].get(source_id)
        if previous and previous.get("sha256") != digest:
            raise ValueError(f"Cached {source_id} checksum differs from the recorded source manifest; inspect the cache before rebuilding")
        expected = source.get("checksum_sha256")
        if expected and digest.casefold() != expected.casefold():
            raise ValueError(f"Checksum mismatch for {source_id}: expected {expected}, got {digest}")
        if not previous:
            manifest["downloads"][source_id] = {
                "url": source["download_url"], "version": source["version"],
                "sha256": digest, "bytes": path.stat().st_size,
                "downloaded_at_utc": datetime.now(timezone.utc).isoformat(timespec="seconds"),
            }
            manifest_path.write_text(json.dumps(manifest, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")

    if path.is_file():
        digest = sha256_file(path)
        record_manifest(digest)
        return path, digest
    temporary = path.with_suffix(path.suffix + ".part")
    request = urllib.request.Request(
        source["download_url"],
        headers={"User-Agent": "LafzLexicon/0.1 (offline dictionary build; contact: project maintainers)"},
    )
    try:
        with urllib.request.urlopen(request, timeout=timeout) as response, temporary.open("wb") as output:
            while True:
                chunk = response.read(1024 * 1024)
                if not chunk:
                    break
                output.write(chunk)
        digest = sha256_file(temporary)
        expected = source.get("checksum_sha256")
        if expected and digest.casefold() != expected.casefold():
            raise ValueError(f"Checksum mismatch for {source_id}: expected {expected}, got {digest}")
        os.replace(temporary, path)
    except (OSError, urllib.error.URLError, TimeoutError) as error:
        temporary.unlink(missing_ok=True)
        raise RuntimeError(
            f"Could not download {source_id} from {source['download_url']}: {error}. "
            "No fallback or synthetic source data was substituted. Supply a reviewed local source file or retry with network access."
        ) from error
    finally:
        temporary.unlink(missing_ok=True)
    record_manifest(digest)
    return path, digest


def classify_local_archive(path: Path) -> str | None:
    name = path.name.casefold()
    suffix = path.suffix.casefold()
    if "english-wordnet-2025" in name and (".xml" in name or suffix == ".zip"):
        return "oewn_2025"
    if "cmudict" in name and suffix in {".dict", ".txt", ".gz", ".bz2", ".zip"}:
        return "cmudict"
    if "ngsl" in name and suffix == ".csv" and "stat" in name:
        return "ngsl_1_2"
    if "ngsl" in name and suffix == ".xlsx":
        return "ngsl_definitions_workbook"
    if "simple-extract" in name or "simplewiktionary" in name:
        return "simple_wiktionary"
    if ("raw-wiktextract" in name or "kaikki" in name) and suffix in {".jsonl", ".gz", ".bz2", ".zip"}:
        return "kaikki_english"
    if ("eng_sentences" in name or "tatoeba" in name) and suffix in {".tsv", ".txt", ".bz2", ".gz", ".zip"}:
        return "tatoeba_english"
    return None


def find_local_source(source_dir: Path, source_id: str) -> Path | None:
    """Find one unambiguous source archive by known dataset file naming."""
    matches = sorted(
        path for path in source_dir.rglob("*")
        if path.is_file() and classify_local_archive(path) == source_id
    ) if source_dir.is_dir() else []
    if not matches:
        return None
    preferred = {
        "oewn_2025": ("english-wordnet-2025.xml.gz",),
        "cmudict": ("cmudict.dict.txt", "cmudict.dict"),
        "ngsl_1_2": ("ngsl_1.2_stats.csv", "ngsl_12_stats.csv"),
        "simple_wiktionary": ("simple-extract.jsonl.gz",),
        "tatoeba_english": ("eng_sentences.tsv.bz2",),
    }.get(source_id, ())
    exact = [path for path in matches if path.name.casefold() in preferred]
    if len(exact) == 1:
        return exact[0]
    if len(matches) == 1:
        return matches[0]
    raise ValueError(f"Multiple local files look like {source_id}: {', '.join(str(path) for path in matches)}")


def inventory_local_archives(source_dir: Path) -> list[dict]:
    """Inspect local source files and record actual sizes, compression, and hashes."""
    registry = {item["id"]: item for item in source_registry()["sources"]}
    results = []
    if not source_dir.is_dir():
        return results
    for path in sorted(source_dir.rglob("*")):
        if not path.is_file():
            continue
        source_id = classify_local_archive(path)
        source = registry.get(source_id or "", {})
        decision = "eligible if selected by the build"
        if source_id == "ngsl_definitions_workbook":
            decision = "inspected, not imported: the ranked CSV is present; this word-level workbook has no POS/sense alignment metadata"
        elif source_id == "tatoeba_english":
            decision = "general English export; import with CC BY 2.0 France attribution, not as CC0"
        elif source_id is None:
            decision = "unrecognized file; not imported"
        try:
            compression = detect_compression(path)
        except OSError:
            compression = "unreadable"
        results.append({
            "path": str(path.relative_to(source_dir)),
            "source_id": source_id,
            "format": source.get("format") or path.suffix.lstrip(".").upper() or "unknown",
            "version": source.get("version", "not encoded in filename or source registry"),
            "compression": compression,
            "bytes": path.stat().st_size,
            "sha256": sha256_file(path),
            "license": source.get("license", "not established; not imported"),
            "decision": decision,
        })
    return results
