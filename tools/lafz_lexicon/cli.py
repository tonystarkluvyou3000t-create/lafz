from __future__ import annotations

import argparse
import json
import logging
import sys
from pathlib import Path

from .acquire import PACKAGE_DIR, download_source, find_local_source, inventory_local_archives, source_registry
from .database import build_database, refresh_topics_database, validate_database
from .core import build_core_database, write_core_comparison
from .reporting import render_markdown, write_reports

LOG = logging.getLogger("lafz_lexicon")


def _path(value: str | None) -> Path | None:
    return Path(value).resolve() if value else None


def _download_or_path(path: Path | None, source_id: str, cache: Path) -> Path:
    if path:
        if not path.is_file():
            raise FileNotFoundError(f"Source file does not exist: {path}")
        return path
    downloaded, _ = download_source(source_id, cache)
    return downloaded


def make_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(prog="python -m lafz_lexicon", description="Build and inspect the Lafz offline lexical database.")
    parser.add_argument("--verbose", action="store_true", help="Show debug logging.")
    commands = parser.add_subparsers(dest="command", required=True)

    build = commands.add_parser("build", help="Build the SQLite lexical database.")
    build.add_argument("--sample", action="store_true", help="Build the fast checked-in OEWN parser fixture; never used as a fallback for a full build.")
    build.add_argument("--oewn", help="Local Open English WordNet 2025 WN-LMF XML/.xml.gz file.")
    build.add_argument("--cmudict", help="Local CMUdict text file.")
    build.add_argument("--ngsl", help="Local official NGSL list. Ranked file adds ranks; alphabetized file adds membership only.")
    build.add_argument("--kaikki", help="Optional English Kaikki/Wiktextract JSONL or .gz export; streamed and limited to OEWN headwords.")
    build.add_argument("--simple-wiktionary", help="Optional Simple English Wiktionary JSONL or .gz export.")
    build.add_argument("--tatoeba", "--tatoeba-cc0", dest="tatoeba", help="Optional Tatoeba English sentences export (.tsv/.bz2); the general export is CC BY 2.0 France, not CC0.")
    build.add_argument("--offline", action="store_true", help="Use only local files; never attempt network downloads.")
    build.add_argument("--source-dir", help="Directory recursively scanned for local archives in offline mode; defaults to tools/lafz_lexicon/source_archives/.")
    build.add_argument("--output", help="Database output path; defaults under tools/lafz_lexicon/build/.")
    build.add_argument("--core-output", help="Core database output path; defaults to build/core/lafz_core.db.")
    build.add_argument("--legacy-db", help="Optional read-only Lafz WordNet 3.0 catalog export for real overlap and ID crosswalk reports.")

    core = commands.add_parser("core", help="Build or rebuild the NGSL 1.2 learner Core edition from a full database.")
    core.add_argument("full_database", nargs="?", help="Full Lafz Lexicon database; defaults to the full build output.")
    core.add_argument("--output", help="Core output path; defaults to build/core/lafz_core.db.")
    core.add_argument("--legacy-db", help="Optional read-only Lafz WordNet 3.0 catalog export for ID migration reports.")

    download = commands.add_parser("download", help="Download and checksum the approved core source archives.")
    download.add_argument("--source", action="append", choices=["oewn_2025", "cmudict", "ngsl_1_2"], help="Source ID; repeat to select multiple. Defaults to all core sources.")
    download.add_argument("--cache-dir", help="Source cache directory.")

    validate = commands.add_parser("validate", help="Run SQLite and lexical quality checks.")
    validate.add_argument("database", nargs="?", help="Database to validate; default is the full build output.")

    refresh = commands.add_parser("refresh-topics", help="Reapply configured topic rules to an existing database without reingesting sources.")
    refresh.add_argument("database", nargs="?", help="Database to refresh; defaults to the full build output.")

    report = commands.add_parser("report", help="Write measured quality, size, and query timing reports.")
    report.add_argument("database", nargs="?", help="Database to report; default is the full build output.")
    report.add_argument("--report-dir", help="Report output directory.")
    report.add_argument("--legacy-db", help="Existing Lafz WordNet catalog to compare by lemma and POS.")

    return parser


def main(argv: list[str] | None = None) -> int:
    args = make_parser().parse_args(argv)
    logging.basicConfig(level=logging.DEBUG if args.verbose else logging.INFO, format="%(levelname)s %(message)s")
    cache_dir = PACKAGE_DIR / "cache" / "sources"
    try:
        if args.command == "download":
            selected = args.source or ["oewn_2025", "cmudict", "ngsl_1_2"]
            cache_dir = _path(args.cache_dir) or cache_dir
            for source_id in selected:
                path, digest = download_source(source_id, cache_dir)
                print(f"{source_id}\t{path}\t{path.stat().st_size} bytes\tsha256={digest}")
            return 0

        if args.command == "build":
            output = _path(args.output)
            if args.sample:
                if args.offline or any((args.oewn, args.cmudict, args.ngsl, args.kaikki, args.simple_wiktionary, args.tatoeba)):
                    raise ValueError("--sample uses the checked-in OEWN fixture; do not combine it with full-source arguments")
                oewn = PACKAGE_DIR / "fixtures" / "oewn_sample.json"
                output = output or PACKAGE_DIR / "build" / "sample" / "lafz_dictionary.db"
                cmudict = ngsl = kaikki = simple = tatoeba = None
                source_manifest = None
            elif args.offline:
                source_dir = _path(args.source_dir) or PACKAGE_DIR / "source_archives"
                source_manifest = {
                    "mode": "offline",
                    "source_dir": str(source_dir),
                    "inputs": inventory_local_archives(source_dir),
                    "selection": {},
                }

                def local_source(explicit_path: str | None, source_id: str) -> Path | None:
                    if explicit_path:
                        selected = _path(explicit_path)
                        if not selected or not selected.is_file():
                            raise FileNotFoundError(f"Explicit local {source_id} file does not exist: {explicit_path}")
                        return selected
                    try:
                        selected = find_local_source(source_dir, source_id)
                    except ValueError as error:
                        LOG.warning("Skipping %s: %s", source_id, error)
                        return None
                    if selected is None:
                        LOG.warning("No local %s source found under %s; continuing without it.", source_id, source_dir)
                    return selected

                oewn = local_source(args.oewn, "oewn_2025")
                if oewn is None:
                    raise FileNotFoundError(f"Offline build requires Open English WordNet 2025. No matching local file was found under {source_dir}; no network or fixture fallback was used.")
                cmudict = local_source(args.cmudict, "cmudict")
                ngsl = local_source(args.ngsl, "ngsl_1_2")
                kaikki = local_source(args.kaikki, "kaikki_english")
                simple = local_source(args.simple_wiktionary, "simple_wiktionary")
                tatoeba = local_source(args.tatoeba, "tatoeba_english")
                output = output or PACKAGE_DIR / "build" / "full" / "lafz_dictionary.db"
                source_manifest["selection"] = {
                    source_id: str(path) if path else None for source_id, path in (
                        ("oewn_2025", oewn), ("cmudict", cmudict), ("ngsl_1_2", ngsl),
                        ("kaikki_english", kaikki), ("simple_wiktionary", simple), ("tatoeba_english", tatoeba),
                    )
                }
            else:
                oewn = _download_or_path(_path(args.oewn), "oewn_2025", cache_dir)
                cmudict = _download_or_path(_path(args.cmudict), "cmudict", cache_dir)
                ngsl = _download_or_path(_path(args.ngsl), "ngsl_1_2", cache_dir)
                output = output or PACKAGE_DIR / "build" / "full" / "lafz_dictionary.db"
                kaikki = _path(args.kaikki)
                simple = _path(args.simple_wiktionary)
                tatoeba = _path(args.tatoeba)
                source_manifest = None
            result = build_database(output, oewn, cmudict, ngsl, kaikki, simple, tatoeba, args.sample)
            report_dir = output.parent / "reports"
            baseline = PACKAGE_DIR / "build" / "full-before-quality" / "lafz_dictionary.db"
            baseline = baseline if baseline.is_file() and output.name == "lafz_dictionary.db" else None
            legacy_db = _path(args.legacy_db)
            report = write_reports(output, report_dir, legacy_db, baseline)
            core_result = None
            if not args.sample:
                core_output = _path(args.core_output) or PACKAGE_DIR / "build" / "core" / "lafz_core.db"
                core_result = build_core_database(output, core_output)
                core_report_dir = core_output.parent / "reports"
                write_reports(core_output, core_report_dir, legacy_db, baseline)
                core_result["comparison_report"] = str(core_report_dir / "core_comparison.md")
                comparison_path = core_report_dir / "core_comparison.md"
                write_core_comparison(output, core_output, comparison_path)
            if source_manifest is not None:
                source_manifest["source_status"] = result.get("source_status", {})
                source_manifest["build_counters"] = result
                source_manifest["core_build"] = core_result
                (report_dir / "source_manifest.json").write_text(
                    json.dumps(source_manifest, indent=2, ensure_ascii=False) + "\n", encoding="utf-8"
                )
            print(json.dumps({**result, "core_build": core_result, "report_dir": str(report_dir), "words": report["counts"]["unique_words"], "senses": report["counts"]["senses"]}, indent=2))
            return 0

        if args.command == "validate":
            database = _path(args.database) or PACKAGE_DIR / "build" / "full" / "lafz_dictionary.db"
            issues = validate_database(database)
            print(json.dumps({"database": str(database), "issues": issues}, indent=2, ensure_ascii=False))
            return 1 if any(item["severity"] == "error" for item in issues) else 0

        if args.command == "refresh-topics":
            database = _path(args.database) or PACKAGE_DIR / "build" / "full" / "lafz_dictionary.db"
            result = refresh_topics_database(database)
            baseline = PACKAGE_DIR / "build" / "full-before-quality" / "lafz_dictionary.db"
            baseline = baseline if baseline.is_file() and database.name == "lafz_dictionary.db" else None
            legacy_db = PACKAGE_DIR / "build" / "legacy-comparison" / "legacy_raw.db"
            legacy_db = legacy_db if legacy_db.is_file() else None
            reports = write_reports(database, database.parent / "reports", legacy_db, baseline)
            print(json.dumps({**result, "reports": str(database.parent / "reports"), "word_topics": reports["counts"].get("categorized_words"), "sense_topics": reports["counts"].get("senses_with_topic_labels")}, indent=2))
            return 0

        if args.command == "report":
            database = _path(args.database) or PACKAGE_DIR / "build" / "full" / "lafz_dictionary.db"
            report_dir = _path(args.report_dir) or database.parent / "reports"
            baseline = PACKAGE_DIR / "build" / "full-before-quality" / "lafz_dictionary.db"
            baseline = baseline if baseline.is_file() and database.name == "lafz_dictionary.db" else None
            result = write_reports(database, report_dir, _path(args.legacy_db), baseline)
            print(render_markdown(result, database.with_suffix(database.suffix + ".gz").stat().st_size if database.with_suffix(database.suffix + ".gz").exists() else None))
            print(f"\nReports written to {report_dir}")
            return 0

        if args.command == "core":
            full_database = _path(args.full_database) or PACKAGE_DIR / "build" / "full" / "lafz_dictionary.db"
            output = _path(args.output) or PACKAGE_DIR / "build" / "core" / "lafz_core.db"
            result = build_core_database(full_database, output)
            report_dir = output.parent / "reports"
            baseline = PACKAGE_DIR / "build" / "full-before-quality" / "lafz_dictionary.db"
            baseline = baseline if baseline.is_file() and full_database.name == "lafz_dictionary.db" else None
            write_reports(output, report_dir, _path(args.legacy_db), baseline)
            write_core_comparison(full_database, output, report_dir / "core_comparison.md")
            result["comparison_report"] = str(report_dir / "core_comparison.md")
            print(json.dumps(result, indent=2))
            return 0
    except Exception as error:
        LOG.error("%s", error)
        return 2
    return 2
