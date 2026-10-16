from __future__ import annotations

import gzip
import json
import os
import sqlite3
import statistics
import time
from pathlib import Path

from . import SCHEMA_VERSION, __version__
from .acquire import PACKAGE_DIR
from .database import _record_validation, validate_database
from .normalize import stable_id


def _compress(path: Path, destination: Path) -> None:
    destination.parent.mkdir(parents=True, exist_ok=True)
    with path.open("rb") as source, destination.open("wb") as raw:
        with gzip.GzipFile(filename="", mode="wb", fileobj=raw, compresslevel=9, mtime=0) as output:
            for chunk in iter(lambda: source.read(1024 * 1024), b""):
                output.write(chunk)


def build_core_database(full_path: Path, output: Path) -> dict:
    """Create a deterministic learner Core edition from NGSL 1.2 ranks 1–2809."""
    if not full_path.is_file():
        raise FileNotFoundError(f"Full dictionary not found: {full_path}")
    output.parent.mkdir(parents=True, exist_ok=True)
    temp = output.with_suffix(output.suffix + ".building")
    temp.unlink(missing_ok=True)
    source = sqlite3.connect(full_path)
    source.row_factory = sqlite3.Row
    selected_words = [row[0] for row in source.execute(
        "SELECT DISTINCT word_id FROM frequency_metadata WHERE source_id='ngsl_1_2' "
        "AND ranking_scheme='NGSL 1.2' AND rank BETWEEN 1 AND 2809 ORDER BY word_id"
    )]
    if not selected_words:
        source.close()
        raise ValueError("The full dictionary has no ranked NGSL 1.2 words; refusing to emit an empty Core edition")

    selected_word_set = set(selected_words)
    unmatched_alignment_keys = set()
    for row in source.execute(
        "SELECT s.source_sense_id,w.lookup_key,s.part_of_speech,d.definition_text,s.word_id "
        "FROM senses s JOIN words w ON w.word_id=s.word_id "
        "JOIN definitions d ON d.sense_id=s.sense_id AND d.source_id='simple_wiktionary' "
        "WHERE s.source_id='simple_wiktionary' AND s.synset_id IS NULL "
        "ORDER BY s.word_id,s.part_of_speech,s.sense_order,d.display_rank"
    ):
        if row[0] is None or row[4] not in selected_word_set:
            continue
        try:
            key, pos, index = row[0].rsplit(":", 2)
        except ValueError:
            continue
        unmatched_alignment_keys.add(stable_id("ks", "simple_wiktionary", key or row[1], pos, index, row[3]))

    destination = sqlite3.connect(temp)
    destination.execute("PRAGMA journal_mode=OFF")
    destination.execute("PRAGMA synchronous=OFF")
    destination.execute("PRAGMA temp_store=MEMORY")
    try:
        destination.executescript((PACKAGE_DIR / "schema.sql").read_text(encoding="utf-8"))
        destination.execute("ATTACH DATABASE ? AS srcdb", (str(full_path.resolve()),))
        destination.execute("CREATE TEMP TABLE core_word_ids(word_id TEXT PRIMARY KEY)")
        destination.executemany("INSERT INTO core_word_ids(word_id) VALUES(?)", ((wid,) for wid in selected_words))
        destination.execute("CREATE TEMP TABLE core_alignment_keys(source_sense_key TEXT PRIMARY KEY)")
        destination.executemany("INSERT INTO core_alignment_keys(source_sense_key) VALUES(?)", ((key,) for key in unmatched_alignment_keys))
        destination.execute("CREATE TEMP TABLE core_sense_ids(sense_id TEXT PRIMARY KEY)")
        destination.execute(
            "INSERT INTO core_sense_ids SELECT s.sense_id FROM srcdb.senses s "
            "JOIN core_word_ids w ON w.word_id=s.word_id"
        )
        destination.execute("CREATE TEMP TABLE core_synset_ids(synset_id TEXT PRIMARY KEY)")
        destination.execute(
            "INSERT INTO core_synset_ids SELECT DISTINCT s.synset_id FROM srcdb.senses s "
            "JOIN core_sense_ids c ON c.sense_id=s.sense_id WHERE s.synset_id IS NOT NULL"
        )
        destination.commit()
        total_full_words = source.execute("SELECT COUNT(*) FROM words").fetchone()[0]
        total_full_senses = source.execute("SELECT COUNT(*) FROM senses").fetchone()[0]
        total_full_definitions = source.execute("SELECT COUNT(*) FROM definitions").fetchone()[0]
        total_full_examples = source.execute("SELECT COUNT(*) FROM examples").fetchone()[0]
        total_full_word_examples = source.execute("SELECT COUNT(*) FROM word_examples").fetchone()[0]
        destination.execute("BEGIN")
        destination.execute("INSERT INTO data_sources SELECT * FROM srcdb.data_sources")
        destination.execute("INSERT INTO words SELECT w.* FROM srcdb.words w JOIN core_word_ids c ON c.word_id=w.word_id")
        destination.execute("INSERT INTO synsets SELECT sy.* FROM srcdb.synsets sy JOIN core_synset_ids c ON c.synset_id=sy.synset_id")
        destination.execute("INSERT INTO senses SELECT s.* FROM srcdb.senses s JOIN core_sense_ids c ON c.sense_id=s.sense_id")
        destination.execute("INSERT INTO definitions SELECT d.* FROM srcdb.definitions d JOIN core_sense_ids c ON c.sense_id=d.sense_id")
        destination.execute("INSERT INTO examples SELECT e.* FROM srcdb.examples e JOIN core_sense_ids c ON c.sense_id=e.sense_id")
        destination.execute("INSERT INTO word_examples SELECT e.* FROM srcdb.word_examples e JOIN core_word_ids c ON c.word_id=e.word_id")
        destination.execute("INSERT INTO pronunciations SELECT p.* FROM srcdb.pronunciations p JOIN core_word_ids c ON c.word_id=p.word_id")
        destination.execute("INSERT INTO word_forms SELECT f.* FROM srcdb.word_forms f JOIN core_word_ids c ON c.word_id=f.word_id")
        destination.execute("INSERT INTO sense_labels SELECT l.* FROM srcdb.sense_labels l JOIN core_sense_ids c ON c.sense_id=l.sense_id")
        destination.execute(
            "INSERT INTO sense_alignments SELECT a.* FROM srcdb.sense_alignments a WHERE "
            "(a.aligned_sense_id IS NOT NULL AND a.aligned_sense_id IN (SELECT sense_id FROM core_sense_ids)) "
            "OR a.source_sense_key IN (SELECT source_sense_key FROM core_alignment_keys)"
        )
        destination.execute(
            "INSERT INTO word_relations SELECT r.* FROM srcdb.word_relations r "
            "JOIN core_word_ids a ON a.word_id=r.source_word_id JOIN core_word_ids b ON b.word_id=r.target_word_id "
            "WHERE r.sense_id IS NULL OR r.sense_id IN (SELECT sense_id FROM core_sense_ids)"
        )
        destination.execute(
            "INSERT INTO semantic_relations SELECT r.* FROM srcdb.semantic_relations r "
            "JOIN core_synset_ids a ON a.synset_id=r.source_synset_id JOIN core_synset_ids b ON b.synset_id=r.target_synset_id"
        )
        destination.execute("INSERT INTO word_categories SELECT c.* FROM srcdb.word_categories c JOIN core_word_ids w ON w.word_id=c.word_id")
        destination.execute("INSERT INTO frequency_metadata SELECT f.* FROM srcdb.frequency_metadata f JOIN core_word_ids w ON w.word_id=f.word_id")
        destination.execute(
            "INSERT INTO quiz_distractors SELECT q.* FROM srcdb.quiz_distractors q "
            "JOIN core_word_ids a ON a.word_id=q.word_id JOIN core_word_ids b ON b.word_id=q.distractor_word_id"
        )
        destination.execute(
            "INSERT INTO source_attributions SELECT a.* FROM srcdb.source_attributions a "
            "JOIN word_examples e ON e.word_example_id=a.record_id WHERE a.record_type='word_example'"
        )
        destination.execute("INSERT INTO build_metadata SELECT * FROM srcdb.build_metadata")
        source_status_row = destination.execute("SELECT value FROM build_metadata WHERE key='source_status'").fetchone()
        source_status = json.loads(source_status_row[0]) if source_status_row else {}
        full_counters_row = destination.execute("SELECT value FROM build_metadata WHERE key='build_counters'").fetchone()
        full_counters = json.loads(full_counters_row[0]) if full_counters_row else {}
        source_status["core_selection"] = {"status": "included", "policy": "NGSL 1.2 ranks 1–2809; all matching word/POS senses retained"}
        counters = {
            "selected_word_count": len(selected_words),
            "selection_policy": "NGSL 1.2 ranks 1–2809; every selected lemma keeps all available POS and senses",
            "full_build_counters": full_counters,
        }
        destination.execute("UPDATE build_metadata SET value=? WHERE key='build_profile'", ("core",))
        for key, value in {
            "schema_version": str(SCHEMA_VERSION),
            "pipeline_version": __version__,
            "core_selection_policy": counters["selection_policy"],
            "source_status": json.dumps(source_status, sort_keys=True),
            "build_counters": json.dumps(counters, sort_keys=True),
        }.items():
            destination.execute("INSERT OR REPLACE INTO build_metadata(key,value) VALUES(?,?)", (key, value))
        destination.commit()
        destination.execute("DETACH DATABASE srcdb")
        destination.execute("DROP TABLE core_word_ids")
        destination.execute("DROP TABLE core_alignment_keys")
        destination.execute("DROP TABLE core_sense_ids")
        destination.execute("DROP TABLE core_synset_ids")
        destination.execute("PRAGMA journal_mode=DELETE")
        destination.execute("PRAGMA synchronous=NORMAL")
        destination.execute("VACUUM")
        destination.commit()
    except Exception:
        destination.close()
        source.close()
        temp.unlink(missing_ok=True)
        raise
    destination.close()
    source.close()

    issues = validate_database(temp)
    errors = [issue for issue in issues if issue["severity"] == "error"]
    if errors:
        temp.unlink(missing_ok=True)
        raise RuntimeError("Core database validation failed: " + "; ".join(issue["message"] for issue in errors[:5]))
    _record_validation(temp, issues)
    os.replace(temp, output)
    compressed = output.with_suffix(output.suffix + ".gz")
    _compress(output, compressed)

    core = sqlite3.connect(output)
    try:
        measures = {
            "core_words": core.execute("SELECT COUNT(*) FROM words").fetchone()[0],
            "core_senses": core.execute("SELECT COUNT(*) FROM senses").fetchone()[0],
            "core_definitions": core.execute("SELECT COUNT(*) FROM definitions").fetchone()[0],
            "core_sense_examples": core.execute("SELECT COUNT(*) FROM examples").fetchone()[0],
            "core_word_examples": core.execute("SELECT COUNT(*) FROM word_examples").fetchone()[0],
            "core_pronunciations": core.execute("SELECT COUNT(*) FROM pronunciations").fetchone()[0],
            "core_bytes": output.stat().st_size,
            "core_gzip_bytes": compressed.stat().st_size,
            "full_words": total_full_words,
            "full_senses": total_full_senses,
            "full_definitions": total_full_definitions,
            "full_sense_examples": total_full_examples,
            "full_word_examples": total_full_word_examples,
        }
    finally:
        core.close()
    measures["core_word_coverage_pct"] = round(100 * measures["core_words"] / max(1, measures["full_words"]), 2)
    measures["core_sense_coverage_pct"] = round(100 * measures["core_senses"] / max(1, measures["full_senses"]), 2)
    measures["core_definition_coverage_pct"] = round(100 * measures["core_definitions"] / max(1, measures["full_definitions"]), 2)
    measures["core_size_ratio_pct"] = round(100 * measures["core_bytes"] / max(1, full_path.stat().st_size), 2)
    measures["core_compressed_under_30mb"] = measures["core_gzip_bytes"] < 30_000_000
    measures["core_database"] = str(output)
    measures["core_compressed"] = str(compressed)
    measures["validation_warnings"] = len(issues)
    return measures


def _timings(path: Path, loops: int = 60) -> dict:
    db = sqlite3.connect(path)
    db.execute("PRAGMA query_only=ON")
    db.row_factory = sqlite3.Row
    try:
        key_row = db.execute("SELECT lookup_key FROM words ORDER BY lookup_key LIMIT 1").fetchone()
        key = key_row[0] if key_row else "bank"
        queries = {
            "word_lookup": ("SELECT word_id FROM words WHERE lookup_key=? LIMIT 10", (key,)),
            "sense_lookup": ("SELECT sense_id FROM senses WHERE word_id=(SELECT word_id FROM words WHERE lookup_key=? LIMIT 1) ORDER BY sense_order", (key,)),
            "category_filter": ("SELECT word_id FROM word_categories WHERE category=? ORDER BY confidence DESC LIMIT 50", ("Nature",)),
            "discovery_random": ("SELECT word_id FROM words ORDER BY random() LIMIT 1", ()),
        }
        result = {}
        for name, (sql, args) in queries.items():
            values = []
            for _ in range(loops):
                start = time.perf_counter_ns()
                db.execute(sql, args).fetchall()
                values.append((time.perf_counter_ns() - start) / 1_000_000)
            values.sort()
            result[name] = {"median_ms": round(statistics.median(values), 4), "p95_ms": round(values[min(len(values)-1, int(len(values)*0.95))], 4)}
        page_size = db.execute("PRAGMA page_size").fetchone()[0]
        page_count = db.execute("PRAGMA page_count").fetchone()[0]
        cache_size = db.execute("PRAGMA cache_size").fetchone()[0]
        return {
            "iterations_per_query": loops,
            "query_timings": result,
            "page_size_bytes": page_size,
            "page_count": page_count,
            "sqlite_cache_size_setting_pages": cache_size,
            "sqlite_default_cache_budget_bytes": abs(cache_size) * 1024 if cache_size < 0 else cache_size * page_size,
        }
    finally:
        db.close()


def write_core_comparison(full_path: Path, core_path: Path, report_path: Path) -> dict:
    full = sqlite3.connect(full_path)
    core = sqlite3.connect(core_path)
    try:
        def counts(db):
            return {
                "words": db.execute("SELECT COUNT(*) FROM words").fetchone()[0],
                "senses": db.execute("SELECT COUNT(*) FROM senses").fetchone()[0],
                "definitions": db.execute("SELECT COUNT(*) FROM definitions").fetchone()[0],
                "sense_examples": db.execute("SELECT COUNT(*) FROM examples").fetchone()[0],
                "word_level_examples": db.execute("SELECT COUNT(*) FROM word_examples").fetchone()[0],
                "frequency_ranked_words": db.execute("SELECT COUNT(DISTINCT word_id) FROM frequency_metadata WHERE rank IS NOT NULL").fetchone()[0],
                "categorized_words": db.execute("SELECT COUNT(DISTINCT word_id) FROM word_categories").fetchone()[0],
                "pronounced_words": db.execute("SELECT COUNT(DISTINCT word_id) FROM pronunciations").fetchone()[0],
            }
        full_counts, core_counts = counts(full), counts(core)
    finally:
        full.close()
        core.close()
    full_gzip = full_path.with_suffix(full_path.suffix + ".gz")
    core_gzip = core_path.with_suffix(core_path.suffix + ".gz")
    result = {
        "selection_policy": "NGSL 1.2 ranks 1–2809; all POS, meanings, and available enrichment retained for selected spellings",
        "full": {**full_counts, "database_bytes": full_path.stat().st_size, "gzip_bytes": full_gzip.stat().st_size if full_gzip.exists() else None, "host_query_and_storage": _timings(full_path)},
        "core": {**core_counts, "database_bytes": core_path.stat().st_size, "gzip_bytes": core_gzip.stat().st_size if core_gzip.exists() else None, "host_query_and_storage": _timings(core_path)},
        "android_query_timing": "Not measured on device: a production app/runtime change would be required to execute the benchmark in Android SQLite. The phone was used read-only to measure legacy catalog storage and overlap.",
        "android_memory": "Not directly measured on Android; SQLite page size and default cache budget are reported for both host SQLite files. Device RSS requires a future app-level instrumentation benchmark.",
    }
    result["core_compressed_under_30mb"] = bool(result["core"]["gzip_bytes"] is not None and result["core"]["gzip_bytes"] < 30_000_000)
    report_path.parent.mkdir(parents=True, exist_ok=True)
    report_path.write_text(render_core_comparison(result), encoding="utf-8")
    report_path.with_suffix(".json").write_text(json.dumps(result, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    return result


def render_core_comparison(result: dict) -> str:
    lines = [
        "# Full vs Core edition comparison", "",
        f"Selection: {result['selection_policy']}.", "",
        "## Coverage and storage", "",
        "| Measure | Full | Core |", "|---|---:|---:|",
    ]
    for key, label in (("words", "Word entries"), ("senses", "Senses"), ("definitions", "Definitions"),
                       ("sense_examples", "Sense-associated examples"), ("word_level_examples", "Word-level examples; sense unverified"),
                       ("frequency_ranked_words", "NGSL-ranked words"), ("categorized_words", "Consensus word topics"),
                       ("pronounced_words", "Words with pronunciations"), ("database_bytes", "SQLite bytes"), ("gzip_bytes", "Gzip bytes")):
        lines.append(f"| {label} | {result['full'].get(key, 0):,} | {result['core'].get(key, 0) or 0:,} |")
    lines += ["", f"Core gzip target below 30,000,000 bytes: **{'met' if result['core_compressed_under_30mb'] else 'not met'}**.",
              "", "## Development-host query timing and SQLite pages", "", "60 iterations per query; these measurements are not Android timings.", ""]
    for edition in ("full", "core"):
        data = result[edition]["host_query_and_storage"]
        lines += [f"### {edition.title()}", "", "| Query | Median ms | p95 ms |", "|---|---:|---:|"]
        for name, timing in data["query_timings"].items():
            lines.append(f"| {name.replace('_', ' ')} | {timing['median_ms']:.4f} | {timing['p95_ms']:.4f} |")
        lines += ["", f"Page size: {data['page_size_bytes']:,} bytes; pages: {data['page_count']:,}; default SQLite cache setting: {data['sqlite_default_cache_budget_bytes']:,} bytes.", ""]
    lines += ["## Android measurement scope", "", result["android_query_timing"], result["android_memory"], "",
              "The connected phone’s current legacy database size was measured separately in `legacy_comparison.md`. No database, preferences, or progress state was written on the phone.", ""]
    return "\n".join(lines)
