from __future__ import annotations

import gzip
import hashlib
import json
import logging
import os
import sqlite3
import time
from pathlib import Path

from . import SCHEMA_VERSION, __version__
from .acquire import PACKAGE_DIR, sha256_file, source_registry
from .ingest import add_cmudict, add_difficulty_estimates, add_distractors, add_kaikki, add_ngsl, add_tatoeba_sentences, add_topics, insert_oewn, refresh_topics
from .normalize import clean_text, inflections, lexical_key, tokens, token_sequences_contain, valid_lemma
from .parsers import parse_oewn_json_records, parse_oewn_xml
from .quality import valid_arpabet
from .topics import TOPIC_POLICY

LOG = logging.getLogger(__name__)


def sha_text(path: Path) -> str:
    return sha256_file(path)


def _record_factory(path: Path):
    if path.suffix.lower() == ".json":
        return lambda: parse_oewn_json_records(path)
    return lambda: parse_oewn_xml(path)


def _insert_sources(db: sqlite3.Connection, checksums: dict[str, str]) -> None:
    for source in source_registry()["sources"]:
        db.execute(
            "INSERT INTO data_sources VALUES(?,?,?,?,?,?,?,?,?,?)",
            (source["id"], source["name"], source["publisher"], source["version"], source["download_url"],
             source["license"], source["license_url"], source["attribution"], source["redistribution"], checksums.get(source["id"]) or source.get("checksum_sha256")),
        )


def _compress(database: Path, destination: Path) -> None:
    destination.parent.mkdir(parents=True, exist_ok=True)
    with database.open("rb") as source, destination.open("wb") as raw:
        with gzip.GzipFile(filename="", mode="wb", fileobj=raw, compresslevel=9, mtime=0) as compressed:
            for chunk in iter(lambda: source.read(1024 * 1024), b""):
                compressed.write(chunk)


def _record_validation(path: Path, issues: list[dict[str, str]]) -> None:
    summary = {
        "status": "passed" if not any(issue["severity"] == "error" for issue in issues) else "failed",
        "integrity_check": "ok" if not any("integrity_check" in issue["message"] for issue in issues) else "failed",
        "foreign_key_violations": sum("Broken foreign key" in issue["message"] for issue in issues),
        "errors": sum(issue["severity"] == "error" for issue in issues),
        "warnings": [issue["message"] for issue in issues if issue["severity"] == "warning"],
    }
    db = sqlite3.connect(path)
    try:
        db.execute("INSERT OR REPLACE INTO build_metadata(key,value) VALUES(?,?)", ("validation_summary", json.dumps(summary, sort_keys=True)))
        db.commit()
    finally:
        db.close()


def build_database(
    output: Path,
    oewn_path: Path,
    cmudict_path: Path | None = None,
    ngsl_path: Path | None = None,
    kaikki_path: Path | None = None,
    simple_path: Path | None = None,
    tatoeba_path: Path | None = None,
    sample: bool = False,
) -> dict:
    output.parent.mkdir(parents=True, exist_ok=True)
    temp = output.with_suffix(output.suffix + ".building")
    temp.unlink(missing_ok=True)
    checksums = {"sample_fixture": sha_text(oewn_path)} if sample else {"oewn_2025": sha_text(oewn_path)}
    if cmudict_path:
        checksums["cmudict"] = sha_text(cmudict_path)
    if ngsl_path:
        checksums["ngsl_1_2"] = sha_text(ngsl_path)
    if kaikki_path:
        checksums["kaikki_english"] = sha_text(kaikki_path)
    if simple_path:
        checksums["simple_wiktionary"] = sha_text(simple_path)
    if tatoeba_path:
        checksums["tatoeba_english"] = sha_text(tatoeba_path)

    config = json.loads((PACKAGE_DIR / "config.json").read_text(encoding="utf-8"))
    try:
        LOG.info("Streaming OEWN records into temporary SQLite database: %s", oewn_path)
        counters = insert_corpus(temp, oewn_path, checksums, config, cmudict_path, ngsl_path, kaikki_path, simple_path, tatoeba_path, sample)
        LOG.info("Running SQLite integrity, foreign-key, and lexical validation")
        issues = validate_database(temp)
    except Exception:
        temp.unlink(missing_ok=True)
        raise
    errors = [item for item in issues if item["severity"] == "error"]
    if errors:
        temp.unlink(missing_ok=True)
        summary = "; ".join(item["message"] for item in errors[:5])
        raise RuntimeError(f"Build validation failed ({len(errors)} errors): {summary}")
    _record_validation(temp, issues)
    os.replace(temp, output)
    compressed = output.with_suffix(output.suffix + ".gz")
    _compress(output, compressed)
    return {**counters, "database": str(output), "database_bytes": output.stat().st_size,
            "compressed": str(compressed), "compressed_bytes": compressed.stat().st_size,
            "validation_errors": 0, "validation_warnings": len(issues)}


def insert_corpus(db_path: Path, oewn_path: Path, checksums: dict[str, str], config: dict,
                  cmudict_path: Path | None, ngsl_path: Path | None, kaikki_path: Path | None,
                  simple_path: Path | None, tatoeba_path: Path | None, sample: bool) -> dict:
    db = sqlite3.connect(db_path)
    try:
        db.execute("PRAGMA foreign_keys=ON")
        db.execute("PRAGMA journal_mode=DELETE")
        db.execute("PRAGMA page_size=4096")
        db.executescript((PACKAGE_DIR / "schema.sql").read_text(encoding="utf-8"))
        _insert_sources(db, checksums)
        counters = insert_oewn(db, _record_factory(oewn_path))
        LOG.info("OEWN import complete: %(words)s words, %(senses)s senses, %(synsets)s synsets", counters)
        source_status = {
            "oewn_2025": {"status": "included", "path": str(oewn_path), "sha256": checksums.get("oewn_2025")},
        }

        def optional_source(source_id: str, path: Path | None, importer, counter_key: str) -> None:
            if path is None:
                source_status[source_id] = {"status": "missing_or_not_selected"}
                return
            LOG.info("Importing optional source %s from %s", source_id, path)
            savepoint = "optional_" + source_id.replace("-", "_")
            db.execute(f"SAVEPOINT {savepoint}")
            try:
                result = importer()
                db.execute(f"RELEASE SAVEPOINT {savepoint}")
                counters[counter_key] = result
                source_status[source_id] = {"status": "included", "path": str(path), "sha256": checksums.get(source_id)}
                LOG.info("Imported %s: %s", source_id, result)
            except Exception as error:
                db.execute(f"ROLLBACK TO SAVEPOINT {savepoint}")
                db.execute(f"RELEASE SAVEPOINT {savepoint}")
                source_status[source_id] = {
                    "status": "skipped_after_import_error", "path": str(path),
                    "sha256": checksums.get(source_id), "error": f"{type(error).__name__}: {error}",
                }
                LOG.warning("Skipped optional source %s after import error: %s", source_id, error)

        optional_source("cmudict", cmudict_path, lambda: add_cmudict(db, cmudict_path), "cmudict")
        optional_source("ngsl_1_2", ngsl_path, lambda: add_ngsl(db, ngsl_path), "ngsl")
        optional_source("kaikki_english", kaikki_path, lambda: add_kaikki(db, kaikki_path, "kaikki_english"), "kaikki")
        optional_source("simple_wiktionary", simple_path, lambda: add_kaikki(db, simple_path, "simple_wiktionary"), "simple_wiktionary")
        optional_source("tatoeba_english", tatoeba_path, lambda: add_tatoeba_sentences(
            db, tatoeba_path, max_examples_per_word=int(config.get("examples", {}).get("tatoeba_max_per_word", 3))), "tatoeba_english")
        counters["difficulty_estimates"] = add_difficulty_estimates(db)
        source_status["lafz_heuristics"] = {"status": "included", "version": "1.0", "confidence_note": "heuristic; not CEFR"}
        counters["category_assignments"] = add_topics(db, config)
        counters["quiz_distractors"] = add_distractors(db, config)
        counters["source_status"] = source_status
        db.executemany("INSERT OR REPLACE INTO build_metadata(key,value) VALUES(?,?)", [
            ("schema_version", str(SCHEMA_VERSION)), ("pipeline_version", __version__),
            ("build_profile", "sample" if sample else "full"),
            ("source_checksums", json.dumps(checksums, sort_keys=True)),
            ("sample_fixture_sha256", checksums.get("sample_fixture", "")),
            ("ranking_policy", "NGSL rank is usefulness/frequency evidence; it is not CEFR."),
            ("alignment_policy", "Same-lemma and same-POS definitions use deterministic content-token F1; require score >=0.60, >=2 shared content tokens, and a >=0.12 unique-best margin. Ambiguous and weak cases remain separate."),
            ("example_policy", "Primary examples must contain the headword or a recorded/conservative inflection."),
            ("difficulty_policy", "NGSL rank bands and Lafz lexical complexity v1 are heuristic learning aids, not CEFR labels; confidence and evidence are stored per row."),
            ("topic_policy", TOPIC_POLICY),
            ("tatoeba_policy", "Tatoeba sentence matches are stored as word_examples with word presence checked and sense fit unverified; they are never attached to a specific sense."),
            ("source_status", json.dumps(source_status, sort_keys=True)),
            ("build_counters", json.dumps(counters, sort_keys=True)),
        ])
        db.commit()
        db.execute("VACUUM")
        db.commit()
        return counters
    finally:
        db.close()


def refresh_topics_database(path: Path) -> dict:
    """Reapply topic rules to an existing full/Core DB without reingesting sources."""
    if not path.is_file():
        raise FileNotFoundError(f"Database not found: {path}")
    config = json.loads((PACKAGE_DIR / "config.json").read_text(encoding="utf-8"))
    db = sqlite3.connect(path)
    try:
        db.execute("PRAGMA foreign_keys=ON")
        db.execute("BEGIN")
        topic_counters = refresh_topics(db, config)
        metadata = dict(db.execute("SELECT key,value FROM build_metadata"))
        counters = json.loads(metadata.get("build_counters", "{}"))
        counters["category_assignments"] = topic_counters
        db.execute("INSERT OR REPLACE INTO build_metadata(key,value) VALUES(?,?)", ("build_counters", json.dumps(counters, sort_keys=True)))
        db.execute("INSERT OR REPLACE INTO build_metadata(key,value) VALUES(?,?)", (
            "pipeline_version", __version__,
        ))
        db.execute("INSERT OR REPLACE INTO build_metadata(key,value) VALUES(?,?)", (
            "topic_policy", TOPIC_POLICY,
        ))
        db.commit()
    except Exception:
        db.rollback()
        raise
    finally:
        db.close()
    issues = validate_database(path)
    errors = [issue for issue in issues if issue["severity"] == "error"]
    if errors:
        raise RuntimeError("Topic refresh validation failed: " + "; ".join(issue["message"] for issue in errors[:5]))
    _record_validation(path, issues)
    _compress(path, path.with_suffix(path.suffix + ".gz"))
    return {"topic_refresh": topic_counters, "validation_warnings": len(issues), "database_bytes": path.stat().st_size}


def validate_database(path: Path) -> list[dict[str, str]]:
    issues: list[dict[str, str]] = []
    if not path.is_file():
        return [{"severity": "error", "message": f"Database not found: {path}"}]
    db = sqlite3.connect(path)
    db.row_factory = sqlite3.Row
    db.execute("PRAGMA foreign_keys=ON")
    try:
        integrity = db.execute("PRAGMA integrity_check").fetchone()[0]
        if integrity != "ok":
            issues.append({"severity": "error", "message": f"SQLite integrity_check: {integrity}"})
        fk = db.execute("PRAGMA foreign_key_check").fetchall()
        for row in fk:
            issues.append({"severity": "error", "message": f"Broken foreign key in {row[0]} row {row[1]}"})
        empty = db.execute("SELECT COUNT(*) FROM words WHERE trim(spelling)='' OR lookup_key='' ").fetchone()[0]
        if empty:
            issues.append({"severity": "error", "message": f"{empty} words lack mandatory spelling/lookup fields"})
        bad_words = [r[0] for r in db.execute("SELECT spelling FROM words") if not valid_lemma(r[0])]
        if bad_words:
            issues.append({"severity": "error", "message": f"Malformed/non-lexical word spellings: {bad_words[:5]}"})
        pos_errors = db.execute(
            "SELECT COUNT(*) FROM senses se JOIN synsets sy ON sy.synset_id=se.synset_id "
            "WHERE se.part_of_speech<>sy.part_of_speech"
        ).fetchone()[0]
        if pos_errors:
            issues.append({"severity": "error", "message": f"{pos_errors} sense/synset part-of-speech mismatches"})
        duplicate_defs = db.execute(
            "SELECT COUNT(*) FROM (SELECT sense_id,source_id,lower(trim(definition_text)) d,COUNT(*) c "
            "FROM definitions GROUP BY 1,2,3 HAVING c>1)"
        ).fetchone()[0]
        if duplicate_defs:
            issues.append({"severity": "warning", "message": f"{duplicate_defs} duplicate definition groups need review"})
        source_errors = db.execute(
            "SELECT COUNT(*) FROM source_attributions a LEFT JOIN data_sources s ON s.source_id=a.source_id WHERE s.source_id IS NULL"
        ).fetchone()[0]
        if source_errors:
            issues.append({"severity": "error", "message": f"{source_errors} records reference unknown sources"})
        reward_nature = db.execute(
            "SELECT COUNT(*) FROM word_categories c JOIN words w ON w.word_id=c.word_id WHERE w.lookup_key='reward' AND c.category='Nature'"
        ).fetchone()[0]
        if reward_nature:
            issues.append({"severity": "error", "message": "Regression: reward was incorrectly classified as Nature"})
        explicit_forms: dict[str, set[str]] = {}
        for wid, form in db.execute("SELECT word_id,form FROM word_forms"):
            explicit_forms.setdefault(wid, set()).add(form)
        expected_forms: dict[str, set[tuple[str, ...]]] = {}
        for wid, spelling in db.execute("SELECT word_id,spelling FROM words"):
            expected_forms[wid] = {tuple(tokens(form)) for form in inflections(spelling, explicit_forms.get(wid))}
        example_errors = 0
        for row in db.execute(
            "SELECT w.word_id,w.spelling,e.example_text,e.validation_status FROM examples e "
            "JOIN senses s ON s.sense_id=e.sense_id JOIN words w ON w.word_id=s.word_id"
        ):
            if not token_sequences_contain(tokens(row[2]), expected_forms.get(row[0], set())):
                example_errors += 1
            if row[3] == "unverified":
                example_errors += 1
        if example_errors:
            issues.append({"severity": "error", "message": f"{example_errors} examples fail headword/inflection checks"})
        bad_pronunciations = [r[0] for r in db.execute("SELECT transcription FROM pronunciations WHERE phonetic_alphabet='ARPABET'") if not valid_arpabet(r[0])]
        if bad_pronunciations:
            issues.append({"severity": "error", "message": f"{len(bad_pronunciations)} malformed ARPABET transcriptions"})
        unaligned = db.execute("SELECT COUNT(*) FROM senses WHERE alignment_confidence IS NOT NULL AND alignment_confidence<0.78 AND source_sense_id IS NOT NULL AND synset_id IS NOT NULL").fetchone()[0]
        if unaligned:
            issues.append({"severity": "warning", "message": f"{unaligned} low-confidence sense alignments need manual review"})
        broken_alignments = db.execute(
            "SELECT COUNT(*) FROM sense_alignments WHERE status='accepted' AND (aligned_sense_id IS NULL OR "
            "(method='definition_content_f1' AND (confidence<0.60 OR confidence>1.0)) OR "
            "(method='definition_token_jaccard' AND confidence<0.78) OR "
            "method NOT IN ('definition_content_f1','definition_token_jaccard'))"
        ).fetchone()[0]
        if broken_alignments:
            issues.append({"severity": "error", "message": f"{broken_alignments} accepted sense alignments lack a valid method-specific confidence"})
        word_example_errors = 0
        for row in db.execute("SELECT word_id,example_text FROM word_examples"):
            if not token_sequences_contain(tokens(row[1]), expected_forms.get(row[0], set())):
                word_example_errors += 1
        if word_example_errors:
            issues.append({"severity": "error", "message": f"{word_example_errors} word-level examples fail headword/inflection checks"})
        misplaced_tatoeba = db.execute("SELECT COUNT(*) FROM examples WHERE source_id='tatoeba_english'").fetchone()[0]
        if misplaced_tatoeba:
            issues.append({"severity": "error", "message": f"{misplaced_tatoeba} Tatoeba examples are incorrectly attached to a sense"})
        if not db.execute("SELECT 1 FROM words LIMIT 1").fetchone():
            issues.append({"severity": "error", "message": "Database contains no words"})
        return issues
    finally:
        db.close()
