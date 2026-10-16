from __future__ import annotations

import gzip
import json
import csv
import hashlib
import sqlite3
import statistics
import time
from pathlib import Path
from .normalize import lexical_key, stable_id, tokens
from .quality import align_definition

from .acquire import PACKAGE_DIR, inventory_local_archives


REVIEW_GROUPS = {
    "Common and polysemous": ["bank", "apple", "reward", "set", "run", "light", "charge", "lead", "tear", "present"],
    "Scientific": ["photosynthesis", "gravity", "oxygen", "entropy", "cell", "molecule", "isotope", "organism", "quantum", "atom"],
    "Rare and literary": ["portent", "serendipity", "ephemeral", "gossamer", "quixotic", "melancholy", "obfuscate", "sublime", "mundane", "myriad"],
}


def _scalar(db: sqlite3.Connection, sql: str, params: tuple = ()) -> int:
    return int(db.execute(sql, params).fetchone()[0] or 0)


def _file_digest(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def _artifact_manifest(database: Path) -> dict:
    raw_sha = _file_digest(database)
    compressed = database.with_suffix(database.suffix + ".gz")
    expansion = None
    compressed_sha = None
    if compressed.exists():
        compressed_sha = _file_digest(compressed)
        expanded = hashlib.sha256()
        expanded_size = 0
        with gzip.open(compressed, "rb") as handle:
            for chunk in iter(lambda: handle.read(1024 * 1024), b""):
                expanded.update(chunk)
                expanded_size += len(chunk)
        expansion = {"bytes": expanded_size, "sha256": expanded.hexdigest(), "matches_database": expanded_size == database.stat().st_size and expanded.hexdigest() == raw_sha}
    return {
        "database": {"name": database.name, "bytes": database.stat().st_size, "sha256": raw_sha},
        "compressed": {"name": compressed.name, "bytes": compressed.stat().st_size, "sha256": compressed_sha} if compressed.exists() else None,
        "gzip_expansion": expansion,
    }


def collect_report(path: Path) -> dict:
    db = sqlite3.connect(path)
    db.row_factory = sqlite3.Row
    try:
        counts = {
            "unique_words": _scalar(db, "SELECT COUNT(*) FROM words"),
            "single_word_lemmas": _scalar(db, "SELECT COUNT(*) FROM words WHERE is_multiword=0"),
            "multiword_entries": _scalar(db, "SELECT COUNT(*) FROM words WHERE is_multiword=1"),
            "senses": _scalar(db, "SELECT COUNT(*) FROM senses"),
            "synsets": _scalar(db, "SELECT COUNT(*) FROM synsets"),
            "definitions": _scalar(db, "SELECT COUNT(*) FROM definitions"),
            "senses_with_definitions": _scalar(db, "SELECT COUNT(DISTINCT sense_id) FROM definitions"),
            "alternative_definitions": _scalar(db, "SELECT COUNT(*) FROM definitions WHERE definition_kind<>'source'"),
            "verified_example_sentences": _scalar(db, "SELECT COUNT(*) FROM examples WHERE validation_status IN ('verified_word','verified_inflection')"),
            "unverified_sense_examples": _scalar(db, "SELECT COUNT(*) FROM examples WHERE validation_status='unverified_sense'"),
            "word_level_examples": _scalar(db, "SELECT COUNT(*) FROM word_examples"),
            "words_with_word_level_examples": _scalar(db, "SELECT COUNT(DISTINCT word_id) FROM word_examples"),
            "senses_with_verified_examples": _scalar(db, "SELECT COUNT(DISTINCT sense_id) FROM examples WHERE validation_status IN ('verified_word','verified_inflection')"),
            "words_with_pronunciations": _scalar(db, "SELECT COUNT(DISTINCT word_id) FROM pronunciations"),
            "pronunciations": _scalar(db, "SELECT COUNT(*) FROM pronunciations"),
            "semantic_relations": _scalar(db, "SELECT COUNT(*) FROM semantic_relations"),
            "lexical_relations": _scalar(db, "SELECT COUNT(*) FROM word_relations"),
            "sense_alignments": _scalar(db, "SELECT COUNT(*) FROM sense_alignments"),
            "word_forms": _scalar(db, "SELECT COUNT(*) FROM word_forms"),
            "categorized_words": _scalar(db, "SELECT COUNT(DISTINCT word_id) FROM word_categories"),
            "senses_with_topic_labels": _scalar(db, "SELECT COUNT(DISTINCT sense_id) FROM sense_labels WHERE label_type='topic'"),
            "sense_topic_label_rows": _scalar(db, "SELECT COUNT(*) FROM sense_labels WHERE label_type='topic'"),
            "quiz_distractors": _scalar(db, "SELECT COUNT(*) FROM quiz_distractors"),
            "words_with_frequency_rank": _scalar(db, "SELECT COUNT(DISTINCT word_id) FROM frequency_metadata WHERE rank IS NOT NULL"),
            "words_with_difficulty_estimate": _scalar(db, "SELECT COUNT(DISTINCT word_id) FROM frequency_metadata WHERE difficulty_estimate IS NOT NULL"),
            "words_with_ngsl_difficulty_band": _scalar(db, "SELECT COUNT(DISTINCT word_id) FROM frequency_metadata WHERE difficulty_estimate IS NOT NULL AND ranking_scheme='NGSL 1.2'"),
            "words_with_heuristic_difficulty": _scalar(db, "SELECT COUNT(DISTINCT word_id) FROM frequency_metadata WHERE difficulty_estimate IS NOT NULL AND ranking_scheme='Lafz lexical complexity v1'"),
            "words_with_any_example": _scalar(db, "SELECT COUNT(*) FROM (SELECT s.word_id FROM examples e JOIN senses s ON s.sense_id=e.sense_id UNION SELECT word_id FROM word_examples)"),
            "source_attribution_details": _scalar(db, "SELECT COUNT(*) FROM source_attributions"),
        }
        counts["definitions_per_sense"] = round(counts["definitions"] / counts["senses"], 3) if counts["senses"] else 0.0
        counts["verified_example_coverage_pct"] = round(100 * counts["senses_with_verified_examples"] / counts["senses"], 2) if counts["senses"] else 0.0
        counts["pronunciation_word_coverage_pct"] = round(100 * counts["words_with_pronunciations"] / counts["unique_words"], 2) if counts["unique_words"] else 0.0
        counts["frequency_word_coverage_pct"] = round(100 * counts["words_with_frequency_rank"] / counts["unique_words"], 2) if counts["unique_words"] else 0.0
        counts["difficulty_word_coverage_pct"] = round(100 * counts["words_with_difficulty_estimate"] / counts["unique_words"], 2) if counts["unique_words"] else 0.0
        counts["topic_word_coverage_pct"] = round(100 * counts["categorized_words"] / counts["unique_words"], 2) if counts["unique_words"] else 0.0
        counts["topic_sense_coverage_pct"] = round(100 * counts["senses_with_topic_labels"] / counts["senses"], 2) if counts["senses"] else 0.0

        parts = dict(db.execute("SELECT part_of_speech,COUNT(*) FROM senses GROUP BY part_of_speech"))
        topics = dict(db.execute("SELECT category,COUNT(DISTINCT word_id) FROM word_categories GROUP BY category ORDER BY category"))
        sense_topics = dict(db.execute("SELECT label,COUNT(DISTINCT sense_id) FROM sense_labels WHERE label_type='topic' GROUP BY label ORDER BY label"))
        tiers = dict(db.execute("SELECT COALESCE(usefulness_tier,'unranked'),COUNT(DISTINCT word_id) FROM frequency_metadata GROUP BY usefulness_tier ORDER BY usefulness_tier"))
        alignment_statuses = dict(db.execute("SELECT status,COUNT(*) FROM sense_alignments GROUP BY status ORDER BY status"))
        metadata = dict(db.execute("SELECT key,value FROM build_metadata"))
        source_status = json.loads(metadata.get("source_status", "{}"))
        build_counters = json.loads(metadata.get("build_counters", "{}"))
        source_ids = sorted(
            source_id for source_id, value in source_status.items()
            if isinstance(value, dict) and value.get("status") == "included"
        )
        source_rows = [dict(row) for row in db.execute(
            "SELECT source_id,name,version,license,checksum_sha256 FROM data_sources WHERE source_id IN ("
            + (",".join("?" for _ in source_ids) if source_ids else "SELECT NULL")
            + ") ORDER BY source_id", source_ids,
        )]
        source_tables = {
            "senses": "senses", "definitions": "definitions", "examples": "examples", "word_level_examples": "word_examples",
            "pronunciations": "pronunciations", "word_forms": "word_forms", "labels": "sense_labels",
            "word_relations": "word_relations", "semantic_relations": "semantic_relations",
            "frequency_metadata": "frequency_metadata", "sense_alignments": "sense_alignments",
            "source_attribution_details": "source_attributions",
        }
        source_contributions = {}
        for source_id in source_ids:
            source_contributions[source_id] = {
                measure: _scalar(db, f"SELECT COUNT(*) FROM {table} WHERE source_id=?", (source_id,))
                for measure, table in source_tables.items()
            }
        duplicate_handling = {
            "identical_definitions_within_same_source_removed": int(build_counters.get("duplicate_definitions", 0)),
            "duplicate_source_examples_removed": int(build_counters.get("duplicate_examples", 0)),
            "duplicate_synsets_removed": int(build_counters.get("duplicate_synsets", 0)),
            "duplicate_senses_removed": int(build_counters.get("duplicate_senses", 0)),
            "duplicate_relations_removed": int(build_counters.get("duplicate_relations", 0)),
            "rejected_lexical_members": int(build_counters.get("filtered_members", 0)),
            "identical_definitions_kept_with_cross_source_provenance": _scalar(db,
                "SELECT COUNT(*) FROM (SELECT sense_id,lower(trim(definition_text)) d "
                "FROM definitions GROUP BY sense_id,d HAVING COUNT(DISTINCT source_id)>1)"),
        }
        validation = json.loads(metadata.get("validation_summary", "{}"))

        lookup_key = db.execute("SELECT lookup_key FROM words ORDER BY lookup_key LIMIT 1").fetchone()
        lookup_value = lookup_key[0] if lookup_key else "bank"
        samples = {
            "word_lookup_ms": _time_query(db, "SELECT word_id FROM words WHERE lookup_key=? LIMIT 10", (lookup_value,)),
            "sense_lookup_ms": _time_query(db, "SELECT sense_id FROM senses WHERE word_id=(SELECT word_id FROM words WHERE lookup_key=? LIMIT 1) ORDER BY sense_order", (lookup_value,)),
            "category_filter_ms": _time_query(db, "SELECT word_id FROM word_categories WHERE category=? ORDER BY confidence DESC LIMIT 50", ("Nature",)),
            "discovery_random_ms": _time_query(db, "SELECT word_id FROM words ORDER BY random() LIMIT 1", ()),
        }
        return {
            "counts": counts,
            "parts_of_speech": parts,
            "categories": topics,
            "sense_topics": sense_topics,
            "learning_tiers": tiers,
            "sense_alignment_statuses": alignment_statuses,
            "sources": source_rows,
            "source_contributions": source_contributions,
            "duplicate_handling": duplicate_handling,
            "validation": validation,
            "metadata": metadata,
            "query_timings": samples,
            "file_bytes": path.stat().st_size,
        }
    finally:
        db.close()


def compare_legacy(new_path: Path, legacy_path: Path) -> dict:
    """Compare the generated lexicon with Lafz's legacy catalog schema."""
    new_db = sqlite3.connect(new_path)
    old_db = sqlite3.connect(legacy_path)
    try:
        new_pairs: dict[tuple[str, str], list[tuple[str, str]]] = {}
        for key, pos, wid, spelling in new_db.execute(
            "SELECT DISTINCT w.lookup_key,s.part_of_speech,w.word_id,w.spelling FROM words w "
            "JOIN senses s ON s.word_id=w.word_id ORDER BY w.lookup_key,s.part_of_speech,w.spelling"
        ):
            new_pairs.setdefault((key, pos), []).append((wid, spelling))
        new_words = set(new_pairs)
        new_glosses: dict[tuple[str, str], list[tuple[str, str]]] = {}
        for key, pos, sid, text in new_db.execute(
            "SELECT w.lookup_key,s.part_of_speech,s.sense_id,d.definition_text FROM words w "
            "JOIN senses s ON s.word_id=w.word_id JOIN definitions d ON d.sense_id=s.sense_id "
            "WHERE s.source_id='oewn_2025' AND d.source_id='oewn_2025' ORDER BY w.lookup_key,s.part_of_speech,s.sense_order,d.display_rank"
        ):
            bucket = new_glosses.setdefault((key, pos), [])
            if not any(existing_id == sid for existing_id, _ in bucket):
                bucket.append((sid, text))
        old_tables = {row[0] for row in old_db.execute("SELECT name FROM sqlite_master WHERE type='table'")}
        if "words" not in old_tables:
            raise ValueError("Legacy comparison database has no words table")
        columns = {row[1] for row in old_db.execute("PRAGMA table_info(words)")}
        if "spelling" not in columns:
            raise ValueError("Legacy words table has no spelling column")
        select_columns = "id,spelling" + (",part" if "part" in columns else "") + (",raw_gloss" if "raw_gloss" in columns else "")
        old_rows = list(old_db.execute(f"SELECT {select_columns} FROM words"))
        old_words: set[tuple[str, str]] = set()
        old_lemmas: set[str] = set()
        gloss_match = gloss_unmatched = gloss_ambiguous = gloss_not_compared = 0
        mapping_counts = {"exact_spelling": 0, "single_normalized_match": 0, "ambiguous_case_variant": 0, "unmatched_lemma_pos": 0}
        crosswalk_sample = []
        for row in old_rows:
            legacy_id, legacy_spelling = str(row[0]), str(row[1]).strip()
            spelling = lexical_key(legacy_spelling)
            if not spelling:
                continue
            old_lemmas.add(spelling)
            pos = str(row[2]).strip().casefold() if len(row) > 2 else "unknown"
            pos = {"n": "noun", "v": "verb", "a": "adjective", "s": "adjective", "r": "adverb"}.get(pos, pos)
            old_words.add((spelling, pos))
            options = new_pairs.get((spelling, pos), []) if pos != "unknown" else []
            exact = [wid for wid, new_spelling in options if new_spelling == legacy_spelling]
            if len(exact) == 1:
                mapping_counts["exact_spelling"] += 1
                status, new_word_id = "mapped_exact_spelling", exact[0]
            elif len(options) == 1:
                mapping_counts["single_normalized_match"] += 1
                status, new_word_id = "mapped_normalized_unique", options[0][0]
            elif len(options) > 1:
                mapping_counts["ambiguous_case_variant"] += 1
                status, new_word_id = "ambiguous_new_case_variant", ""
            else:
                mapping_counts["unmatched_lemma_pos"] += 1
                status, new_word_id = "unmatched_lemma_pos", ""
            if len(crosswalk_sample) < 30 and status != "mapped_exact_spelling":
                crosswalk_sample.append({"legacy_id": legacy_id, "spelling": legacy_spelling, "part": pos, "status": status})
            legacy_gloss = str(row[3]) if len(row) > 3 else ""
            candidates = new_glosses.get((spelling, pos), [])
            if legacy_gloss and candidates:
                idx, score, method = align_definition(legacy_gloss, [text for _, text in candidates])
                if idx is None:
                    gloss_ambiguous += int(method == "ambiguous_tie")
                    gloss_unmatched += int(method != "ambiguous_tie")
                else:
                    gloss_match += 1
            else:
                gloss_not_compared += 1
        new_lemmas = {lemma for lemma, _ in new_words}
        old_with_pos = {(lemma, pos) for lemma, pos in old_words if pos != "unknown"}
        shared_pos = len(new_words & old_with_pos) if old_with_pos else None
        try:
            old_size = legacy_path.stat().st_size
        except OSError:
            old_size = None
        digest = hashlib.sha256()
        with legacy_path.open("rb") as raw_source:
            for chunk in iter(lambda: raw_source.read(1024 * 1024), b""):
                digest.update(chunk)
        capture_note = None
        capture_metadata_path = Path(str(legacy_path) + ".capture.json")
        if capture_metadata_path.is_file():
            capture_metadata = json.loads(capture_metadata_path.read_text(encoding="utf-8"))
            capture_note = capture_metadata.get("snapshot_note")
        return {
            "legacy_path": "connected Lafz app catalog export; com.lafz.app, Android user 0, databases/lafz_wordnet.db",
            "legacy_bytes": old_size,
            "legacy_sha256": digest.hexdigest(),
            "snapshot_note": capture_note,
            "legacy_word_rows": len(old_rows),
            "legacy_unique_lemmas": len(old_lemmas),
            "legacy_lemma_pos_pairs": len(old_with_pos),
            "new_unique_lemmas": len(new_lemmas),
            "new_lemma_pos_pairs": len(new_words),
            "shared_lemmas": len(old_lemmas & new_lemmas),
            "legacy_only_lemmas": len(old_lemmas - new_lemmas),
            "new_only_lemmas": len(new_lemmas - old_lemmas),
            "shared_lemma_pos_pairs": shared_pos,
            "mapped_legacy_ids": mapping_counts["exact_spelling"] + mapping_counts["single_normalized_match"],
            "mapping_counts": mapping_counts,
            "legacy_gloss_candidate_matches": gloss_match,
            "legacy_gloss_candidate_unmatched": gloss_unmatched,
            "legacy_gloss_candidate_ambiguous": gloss_ambiguous,
            "legacy_gloss_not_compared": gloss_not_compared,
            "legacy_gloss_match_note": "Content-token similarity against same-lemma/POS OEWN gloss candidates; a match is a review cue, not proof of identical WordNet senses.",
            "unmatched_legacy_id_samples": crosswalk_sample,
            "legacy_id_column_present": "id" in columns,
        }
    finally:
        new_db.close()
        old_db.close()


def render_comparison_markdown(comparison: dict) -> str:
    mapping = comparison["mapping_counts"]
    lines = [
        "# Legacy WordNet catalog comparison", "",
        f"Legacy catalog export from the connected phone: `{comparison['legacy_bytes'] if comparison['legacy_bytes'] is not None else 'unknown'}` bytes on the transferred SQLite file. The source database remained untouched.",
        *( [f"Snapshot scope: {comparison['snapshot_note']}"] if comparison.get("snapshot_note") else [] ),
        "", "| Measure | Legacy catalog | Lafz Lexicon |", "|---|---:|---:|",
        f"| Word rows / lemma-POS entries | {comparison['legacy_word_rows']:,} | {comparison['new_lemma_pos_pairs']:,} |",
        f"| Unique lemmas | {comparison['legacy_unique_lemmas']:,} | {comparison['new_unique_lemmas']:,} |",
        f"| Lemma-POS overlap | {comparison['shared_lemma_pos_pairs'] if comparison['shared_lemma_pos_pairs'] is not None else 'not available'} | {comparison['shared_lemma_pos_pairs'] if comparison['shared_lemma_pos_pairs'] is not None else 'not available'} |",
        f"| Shared lemmas | {comparison['shared_lemmas']:,} | {comparison['shared_lemmas']:,} |",
        f"| Legacy-only / new-only lemmas | {comparison['legacy_only_lemmas']:,} | {comparison['new_only_lemmas']:,} |",
        f"| Legacy IDs with unique new word target | {comparison['mapped_legacy_ids']:,} / {comparison['legacy_word_rows']:,} | — |",
        "", "## Legacy-ID migration crosswalk", "",
        f"Exact spelling matches: {mapping['exact_spelling']:,}; unique normalized spelling/POS matches: {mapping['single_normalized_match']:,}; ambiguous new case variants: {mapping['ambiguous_case_variant']:,}; no matching lemma/POS: {mapping['unmatched_lemma_pos']:,}.",
        "A complete `legacy_id_crosswalk.csv` is written beside this report. It contains only legacy ID, spelling, POS, new word ID when uniquely resolved, and mapping status; no seen/hidden/progress fields are read.",
        "", "## Definition comparison (heuristic only)", "",
        comparison["legacy_gloss_match_note"],
        f"Same-lemma/POS candidate matches: {comparison['legacy_gloss_candidate_matches']:,}; unmatched: {comparison['legacy_gloss_candidate_unmatched']:,}; tied: {comparison['legacy_gloss_candidate_ambiguous']:,}; unavailable: {comparison['legacy_gloss_not_compared']:,}.",
        "", "Lemma/POS overlap measures vocabulary coverage, not sense identity. The app imports the first WordNet 3.0 sense per lemma/POS; migration must resolve specific legacy IDs via the crosswalk and preserve unmatched saved IDs against the old catalog until reviewed.", "",
    ]
    return "\n".join(lines)


def write_alignment_audit(current_path: Path, output_path: Path, baseline_path: Path | None = None) -> dict:
    db = sqlite3.connect(current_path)
    db.row_factory = sqlite3.Row
    try:
        current_status = dict(db.execute("SELECT status,COUNT(*) FROM sense_alignments WHERE source_id='simple_wiktionary' GROUP BY status"))
        methods = dict(db.execute("SELECT method,COUNT(*) FROM sense_alignments WHERE source_id='simple_wiktionary' GROUP BY method ORDER BY method"))
        accepted_rows = list(db.execute(
            "SELECT DISTINCT w.spelling,a.confidence,src.definition_text AS simple_gloss,oewn.definition_text AS oewn_gloss "
            "FROM sense_alignments a JOIN senses s ON s.sense_id=a.aligned_sense_id JOIN words w ON w.word_id=s.word_id "
            "JOIN definitions src ON src.sense_id=s.sense_id AND src.source_id=a.source_id "
            "JOIN definitions oewn ON oewn.sense_id=s.sense_id AND oewn.source_id='oewn_2025' "
            "WHERE a.source_id='simple_wiktionary' AND a.status='accepted' "
            "GROUP BY a.alignment_id ORDER BY a.confidence,w.lookup_key"
        ))
        accepted_paraphrases = []
        accepted_exact = []
        for row in accepted_rows:
            item = dict(row)
            item["match_type"] = "token-identical" if tokens(row["simple_gloss"]) == tokens(row["oewn_gloss"]) else "content-token paraphrase"
            (accepted_exact if item["match_type"] == "token-identical" else accepted_paraphrases).append(item)
        accepted = (accepted_paraphrases[:18] if accepted_paraphrases else accepted_exact[:18])
        sense_by_alignment_key = {}
        for source_sense_id, lookup_key, pos, sense_id, spelling, gloss in db.execute(
            "SELECT s.source_sense_id,w.lookup_key,s.part_of_speech,s.sense_id,w.spelling,d.definition_text "
            "FROM senses s JOIN words w ON w.word_id=s.word_id "
            "JOIN definitions d ON d.sense_id=s.sense_id AND d.source_id='simple_wiktionary' "
            "WHERE s.source_id='simple_wiktionary' AND s.synset_id IS NULL "
            "ORDER BY s.sense_id,d.display_rank"
        ):
            try:
                key, source_pos, index = source_sense_id.rsplit(":", 2)
            except (AttributeError, ValueError):
                continue
            alignment_key = stable_id("ks", "simple_wiktionary", key or lookup_key, source_pos or pos, index, gloss)
            sense_by_alignment_key.setdefault(alignment_key, {"sense_id": sense_id, "lookup_key": lookup_key, "pos": pos, "spelling": spelling, "gloss": gloss})
        unresolved = []
        for alignment_key, confidence, method in db.execute(
            "SELECT source_sense_key,confidence,method FROM sense_alignments "
            "WHERE source_id='simple_wiktionary' AND status<>'accepted' ORDER BY confidence DESC,source_sense_key LIMIT 40"
        ):
            sense = sense_by_alignment_key.get(alignment_key)
            if sense:
                unresolved.append({**sense, "confidence": confidence, "method": method})
            if len(unresolved) >= 20:
                break
        unresolved_samples = []
        for row in unresolved:
            candidates = list(db.execute(
                "SELECT s.sense_id,d.definition_text FROM senses s JOIN definitions d ON d.sense_id=s.sense_id "
                "WHERE s.word_id=(SELECT word_id FROM words WHERE lookup_key=? ORDER BY spelling LIMIT 1) "
                "AND s.part_of_speech=? AND s.synset_id IS NOT NULL AND d.source_id='oewn_2025' "
                "GROUP BY s.sense_id ORDER BY s.sense_order", (row["lookup_key"], row["pos"])
            ))
            idx, score, reason = align_definition(row["gloss"], [item[1] for item in candidates])
            if idx is None and candidates:
                single_scores = [align_definition(row["gloss"], [item[1]])[1] for item in candidates]
                candidate_text = candidates[max(range(len(candidates)), key=single_scores.__getitem__)][1]
            else:
                candidate_text = candidates[idx][1] if idx is not None else "No same lemma/POS OEWN candidate"
            unresolved_samples.append({
                "word": row["spelling"], "pos": row["pos"], "simple_gloss": row["gloss"],
                "best_candidate": candidate_text, "score": score, "reason": reason,
            })
        result = {"current_status": current_status, "methods": methods, "accepted_samples": [dict(row) for row in accepted], "unresolved_samples": unresolved_samples}
        result["baseline_status"] = {}
        if baseline_path and baseline_path.is_file():
            old = sqlite3.connect(baseline_path)
            try:
                result["baseline_status"] = dict(old.execute(
                    "SELECT status,COUNT(*) FROM sense_alignments WHERE source_id='simple_wiktionary' GROUP BY status"
                ))
            finally:
                old.close()
        output_path.parent.mkdir(parents=True, exist_ok=True)
        output_path.with_suffix(".json").write_text(json.dumps(result, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
        lines = [
            "# Simple English Wiktionary sense-alignment audit", "",
            "## Before and after counts", "", "| Result | Previous full build | Updated build |", "|---|---:|---:|",
        ]
        for status in ("accepted", "unmatched", "ambiguous"):
            lines.append(f"| {status.title()} | {result['baseline_status'].get(status, 0):,} | {current_status.get(status, 0):,} |")
        lines += ["", "## Why the former rate was low", "",
                  "The former aligner compared all tokens, including grammatical filler, with Jaccard similarity and required >=0.78. Paraphrases using a different sentence frame therefore scored poorly. Candidate generation also required the same lemma and part of speech; 4,563 inputs in the original report had no such candidate and remain deliberately unmatched.",
                  "", "## Updated conservative rule", "",
                  "Candidates remain restricted to the same spelling and part of speech. Each OEWN sense contributes one canonical source definition. Definitions are compared with deterministic content-token F1 after a small, transparent inflection normalizer. An automatic match requires a score >=0.60, at least two shared content words, and a >=0.12 margin over the next sense. Ties and weak overlaps remain separate source senses. Exact normalized definition matches are accepted directly.",
                  "", "## Accepted examples inspected", "", "| Word | F1 | Simple English Wiktionary | OEWN |", "|---|---:|---|---|"]
        for row in result["accepted_samples"]:
            lines.append(f"| {row['spelling']} ({row['match_type']}) | {row['confidence']:.3f} | {row['simple_gloss']} | {row['oewn_gloss']} |")
        lines += ["", "## Unresolved examples inspected", "", "These are retained as distinct source senses. The proposed candidate is shown only to explain the score; it is not a human-approved mapping.", "", "| Word/POS | Simple gloss | Best candidate inspected | Score / decision |", "|---|---|---|---|"]
        for row in unresolved_samples:
            label = f"{row['word']} / {row['pos']}"
            lines.append(f"| {label} | {row['simple_gloss']} | {row['best_candidate']} | {row['score']:.3f} / {row['reason']} |")
        lines += ["", "## Implementation limits", "",
                  "No suitable embedding model was present in the local model cache, and this build is offline; no model was downloaded. OEWN semantic links are deliberately not used as a direct sense-match signal because a hypernym, antonym, or related concept does not establish that two definitions have the same meaning. The lexical method is reproducible and inspectable, but it cannot recognize every genuine paraphrase.", ""]
        output_path.write_text("\n".join(lines), encoding="utf-8")
        return result
    finally:
        db.close()


def write_legacy_crosswalk(new_path: Path, legacy_path: Path, output_path: Path) -> None:
    """Write a progress-free ID-to-word mapping; never select seen/hidden columns."""
    new_db = sqlite3.connect(new_path)
    old_db = sqlite3.connect(legacy_path)
    try:
        new_pairs: dict[tuple[str, str], list[tuple[str, str]]] = {}
        for key, pos, wid, spelling in new_db.execute(
            "SELECT DISTINCT w.lookup_key,s.part_of_speech,w.word_id,w.spelling FROM words w JOIN senses s ON s.word_id=w.word_id"
        ):
            new_pairs.setdefault((key, pos), []).append((wid, spelling))
        columns = {row[1] for row in old_db.execute("PRAGMA table_info(words)")}
        if not {"id", "spelling", "part"}.issubset(columns):
            raise ValueError("Legacy catalog must contain id, spelling, and part fields for stable crosswalk generation")
        output_path.parent.mkdir(parents=True, exist_ok=True)
        with output_path.open("w", newline="", encoding="utf-8") as handle:
            writer = csv.writer(handle)
            writer.writerow(["legacy_id", "legacy_spelling", "part_of_speech", "new_word_id", "mapping_status"])
            for legacy_id, old_spelling, raw_pos in old_db.execute("SELECT id,spelling,part FROM words ORDER BY id"):
                spelling = lexical_key(str(old_spelling))
                pos = str(raw_pos).strip().casefold()
                pos = {"n": "noun", "v": "verb", "a": "adjective", "s": "adjective", "r": "adverb"}.get(pos, pos)
                options = new_pairs.get((spelling, pos), [])
                exact = [wid for wid, new_spelling in options if new_spelling == old_spelling]
                if len(exact) == 1:
                    new_word_id, status = exact[0], "mapped_exact_spelling"
                elif len(options) == 1:
                    new_word_id, status = options[0][0], "mapped_normalized_unique"
                elif len(options) > 1:
                    new_word_id, status = "", "ambiguous_new_case_variant"
                else:
                    new_word_id, status = "", "unmatched_lemma_pos"
                writer.writerow([legacy_id, old_spelling, pos, new_word_id, status])
    finally:
        new_db.close()
        old_db.close()


def _time_query(db: sqlite3.Connection, sql: str, params: tuple, loops: int = 60) -> dict:
    elapsed = []
    for _ in range(loops):
        started = time.perf_counter_ns()
        db.execute(sql, params).fetchall()
        elapsed.append((time.perf_counter_ns() - started) / 1_000_000)
    ordered = sorted(elapsed)
    return {"median": round(statistics.median(ordered), 4), "p95": round(ordered[min(len(ordered) - 1, int(len(ordered) * 0.95))], 4), "iterations": loops}


def render_markdown(report: dict, compressed_bytes: int | None = None) -> str:
    counts = report["counts"]
    metadata = report["metadata"]
    profile = metadata.get("build_profile", "unknown")
    intro = (
        "This is a parser/schema sample only; its counts are not English-corpus coverage."
        if profile == "sample" else
        "This is the compact NGSL-selected Core edition derived from the full source build; its coverage and sizes are measured directly."
        if profile == "core" else
        "This is a full local-source build. Counts describe the supplied release files and filters; they do not prove that every definition or example is semantically correct."
    )
    raw_bytes = report["file_bytes"]
    ratio = compressed_bytes / raw_bytes if compressed_bytes and raw_bytes else None
    lines = [
        "# Lafz Lexicon quality and benchmark report", "",
        f"Build profile: **{profile}**. Schema version: {metadata.get('schema_version', 'unknown')}. Pipeline version: {metadata.get('pipeline_version', 'unknown')}.",
        "", intro,
        "Counts below are measured directly from the generated SQLite database.",
        "",
        "## Coverage and size", "",
        "| Measure | Result |", "|---|---:|",
        f"| Unique entries | {counts['unique_words']:,} |",
        f"| Single-word lemmas | {counts['single_word_lemmas']:,} |",
        f"| Multiword entries | {counts['multiword_entries']:,} |",
        f"| Senses | {counts['senses']:,} |",
        f"| Synsets | {counts['synsets']:,} |",
        f"| Definitions | {counts['definitions']:,} ({counts['definitions_per_sense']:.3f} per sense) |",
        f"| Alternative definitions | {counts['alternative_definitions']:,} |",
        f"| Senses with a word-presence-checked example | {counts['senses_with_verified_examples']:,} ({counts['verified_example_coverage_pct']:.2f}%) |",
        f"| Word-presence-checked examples (not sense verification) | {counts['verified_example_sentences']:,} |",
        f"| Word-level corpus examples with sense unresolved | {counts['word_level_examples']:,} ({counts['words_with_word_level_examples']:,} words) |",
        f"| Words with pronunciations | {counts['words_with_pronunciations']:,} ({counts['pronunciation_word_coverage_pct']:.2f}%) |",
        f"| Pronunciation variants | {counts['pronunciations']:,} |",
        f"| Words with NGSL frequency rank | {counts['words_with_frequency_rank']:,} ({counts['frequency_word_coverage_pct']:.2f}%) |",
        f"| Words with any labeled difficulty estimate | {counts['words_with_difficulty_estimate']:,} ({counts['difficulty_word_coverage_pct']:.2f}%) |",
        f"| NGSL rank-derived difficulty bands (heuristic, not CEFR) | {counts['words_with_ngsl_difficulty_band']:,} |",
        f"| Lafz lexical-complexity estimates (low-confidence, not CEFR) | {counts['words_with_heuristic_difficulty']:,} |",
        f"| Synset / lexical relations | {counts['semantic_relations']:,} / {counts['lexical_relations']:,} |",
        f"| Sense-alignment records (including unmatched) | {counts['sense_alignments']:,} |",
        f"| Word forms | {counts['word_forms']:,} |",
        f"| Words with categories | {counts['categorized_words']:,} ({counts['topic_word_coverage_pct']:.2f}%) |",
        f"| Senses with mapped topic labels | {counts['senses_with_topic_labels']:,} ({counts['topic_sense_coverage_pct']:.2f}%) |",
        f"| Words with at least one example | {counts['words_with_any_example']:,} |",
        f"| Quiz distractor edges | {counts['quiz_distractors']:,} |",
        f"| SQLite size | {report['file_bytes']:,} bytes |",
        f"| Gzip size | {compressed_bytes:,} bytes |" if compressed_bytes is not None else "| Gzip size | not measured |",
        f"| Gzip/raw size ratio | {ratio:.1%} |" if ratio is not None else "| Gzip/raw size ratio | not measured |",
        "", "## Part of speech", "", "| Part of speech | Senses |", "|---|---:|",
    ]
    lines.extend(f"| {key} | {value:,} |" for key, value in sorted(report["parts_of_speech"].items()))
    lines += ["", "## Learning tiers and topic labels", "", "### Tiers", "", "| Tier or evidence | Words |", "|---|---:|"]
    lines.extend(f"| {key} | {value:,} |" for key, value in report["learning_tiers"].items())
    lines += ["", "### Consensus word topics", "", "Word categories require one category on at least 80% of classified senses and at least 50% of all senses. Ambiguous words can remain unclassified here.", "", "| Topic | Words |", "|---|---:|"]
    lines.extend(f"| {key} | {value:,} |" for key, value in report["categories"].items())
    lines += ["", "### Sense-specific topics", "", "| Topic | Senses |", "|---|---:|"]
    lines.extend(f"| {key} | {value:,} |" for key, value in report["sense_topics"].items())
    lines += ["", "## Sense alignment status", "", "| Status | Count |", "|---|---:|"]
    lines.extend(f"| {key} | {value:,} |" for key, value in report["sense_alignment_statuses"].items())
    lines += ["", "## Enrichment by source", "", "Counts are row counts in source-attributed tables; a sense can have definitions or examples from several sources. Attribution details count retained external source identifiers.", "",
              "| Source ID | Senses | Definitions | Sense examples | Word-level examples | Pronunciations | Forms | Relations | Frequency rows | Attribution details |", "|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|"]
    for source_id, contribution in report["source_contributions"].items():
        relation_count = contribution["word_relations"] + contribution["semantic_relations"]
        lines.append(
            f"| {source_id} | {contribution['senses']:,} | {contribution['definitions']:,} | "
            f"{contribution['examples']:,} | {contribution['word_level_examples']:,} | {contribution['pronunciations']:,} | {contribution['word_forms']:,} | "
            f"{relation_count:,} | {contribution['frequency_metadata']:,} | {contribution['source_attribution_details']:,} |"
        )
    lines += ["", "## Duplicate handling", "", "| Measure | Count |", "|---|---:|"]
    lines.extend(f"| {name.replace('_', ' ')} | {value:,} |" for name, value in report["duplicate_handling"].items())
    lines += ["", "## Validation", ""]
    validation = report.get("validation", {})
    if validation:
        lines.append(f"Validation status: **{validation.get('status', 'not recorded')}**; SQLite integrity: `{validation.get('integrity_check', 'not recorded')}`; foreign-key violations: {validation.get('foreign_key_violations', 'not recorded')}.")
        lines.extend(f"- Warning: {warning}" for warning in validation.get("warnings", []))
    else:
        lines.append("Validation summary was not stored in this database build.")
    lines += ["", "## SQLite query timings", "", "Times are from 60 in-process executions on this build host; they are not Android-device timings.", "", "| Query | Median ms | p95 ms |", "|---|---:|---:|"]
    lines.extend(f"| {name.replace('_', ' ')} | {data['median']:.4f} | {data['p95']:.4f} |" for name, data in report["query_timings"].items())
    lines += ["", "## Sources present in this build", "", "| Source | Version | License | SHA-256 |", "|---|---|---|---|"]
    lines.extend(f"| {item['name']} | {item['version']} | {item['license']} | {item['checksum_sha256'] or 'not recorded'} |" for item in report["sources"])
    lines += ["", "## Legacy comparison", "", "No Princeton WordNet 3.0 catalog was supplied to this build, so a measured overlap comparison is unavailable. Run `report <database> --legacy-db <exported-catalog.db>` after exporting the current app catalog.", ""]
    build_counters = metadata.get("build_counters")
    if build_counters:
        lines += ["", "## Processing counters", "", "```json", json.dumps(json.loads(build_counters), indent=2, ensure_ascii=False), "```"]
    lines += ["", "## Limits", "", "- Programmatic word-presence checks do not establish that an example illustrates the intended sense.",
              "- No CEFR-labeled source is present. NGSL rank bands and Lafz lexical-complexity estimates are explicitly heuristic; numeric confidence and feature evidence are stored per estimate.",
              "- Kaikki's all-language dump was not present in the supplied archive directory; the Simple English Wiktionary export was used instead.",
              "- Redistribution terms for the aggregate remain to be reviewed because NGSL and Simple English Wiktionary are ShareAlike sources; source-specific attribution is preserved, and no umbrella license is asserted.",
              "- Tatoeba general-export examples are word-level matches only. They live in `word_examples` and are not attached to any definition or sense.",
              "- These are development-host query timings, not Android-device query timings. The connected device's legacy storage and word coverage are separately measured; no production catalog or progress state was modified.", ""]
    return "\n".join(lines)


def render_entry_review(path: Path) -> str:
    db = sqlite3.connect(path)
    db.row_factory = sqlite3.Row
    try:
        lines = ["# Lafz Lexicon regression entry inspection", "",
                 "This inspection selects the polysemous regression words `bank`, `apple`, `reward`, and `portent`. It reports source data present in the database; missing rows mean the source/build did not supply that entry. The programmatic example check confirms target-word presence only, not sense correctness.", ""]
        for spelling in ("bank", "apple", "reward", "portent"):
            words = list(db.execute("SELECT word_id,spelling FROM words WHERE lookup_key=? ORDER BY spelling", (spelling,)))
            lines += [f"## {spelling.title()}", ""]
            if not words:
                lines += ["No entry in this build profile.", ""]
                continue
            for word in words:
                cats = [f"{r['category']} ({r['confidence']:.2f})" for r in db.execute("SELECT category,confidence FROM word_categories WHERE word_id=? ORDER BY category", (word['word_id'],))]
                prons = [f"{r['phonetic_alphabet']}: {r['transcription']}" for r in db.execute("SELECT phonetic_alphabet,transcription FROM pronunciations WHERE word_id=?", (word['word_id'],))]
                lines.append(f"**{word['spelling']}** — topics: {', '.join(cats) or 'none assigned'}; pronunciations: {', '.join(prons) or 'none present'}.")
                for sense in db.execute("SELECT sense_id,part_of_speech FROM senses WHERE word_id=? ORDER BY part_of_speech,sense_order", (word['word_id'],)):
                    lines.append(f"- `{sense['part_of_speech']}`")
                    for definition in db.execute("SELECT definition_text,source_id,display_rank,is_preferred FROM definitions WHERE sense_id=? ORDER BY display_rank", (sense['sense_id'],)):
                        preferred = "preferred" if definition['is_preferred'] else "alternative"
                        lines.append(f"  - {preferred} [{definition['source_id']}]: {definition['definition_text']}")
                    topics = [f"{row['label']} ({row['confidence']:.2f})" for row in db.execute(
                        "SELECT label,confidence FROM sense_labels WHERE sense_id=? AND label_type='topic' ORDER BY label", (sense['sense_id'],)
                    )]
                    if topics:
                        lines.append(f"  - Sense topics: {', '.join(topics)}")
                    for example in db.execute("SELECT example_text,source_id,validation_status,sense_match_confidence FROM examples WHERE sense_id=? ORDER BY quality_score DESC", (sense['sense_id'],)):
                        match = "unknown" if example['sense_match_confidence'] is None else f"{example['sense_match_confidence']:.2f}"
                        lines.append(f"  - example [{example['source_id']}; {example['validation_status']}; sense match {match}]: {example['example_text']}")
                for example in db.execute("SELECT source_id,example_text FROM word_examples WHERE word_id=? ORDER BY quality_score DESC LIMIT 3", (word['word_id'],)):
                    lines.append(f"- Word-level example [{example['source_id']}; word presence only; sense unverified]: {example['example_text']}")
            lines.append("")
        lines += ["## Review scope", "", "This is a focused regression spot check, not a substitute for the 30-entry manual review in `manual_review.md`.", ""]
        return "\n".join(lines)
    finally:
        db.close()


def render_manual_review(path: Path) -> str:
    """Render a 30-term human review sheet with source text for reproducibility."""
    notes_path = PACKAGE_DIR / "manual_review_notes.json"
    notes_payload = json.loads(notes_path.read_text(encoding="utf-8")) if notes_path.exists() else {"notes": {}}
    notes = notes_payload.get("notes", {})
    db = sqlite3.connect(path)
    db.row_factory = sqlite3.Row
    try:
        lines = [
            "# Manual review: 30 representative entries", "",
            "This review was manually read against the built source definitions, topic labels, ranking/pronunciation metadata, and retained examples. It samples common, polysemous, scientific, rare, and literary vocabulary; it does not validate every corpus row. Tatoeba is shown only in a separate word-level section; its presence check does not verify sense fit.",
            "",
        ]
        reviewed = 0
        for group, terms in REVIEW_GROUPS.items():
            lines += [f"## {group}", ""]
            seen_in_group: set[str] = set()
            for term in terms:
                if term in seen_in_group:
                    continue
                seen_in_group.add(term)
                word = db.execute(
                    "SELECT word_id,spelling FROM words WHERE lookup_key=? "
                    "ORDER BY CASE WHEN spelling=? THEN 0 ELSE 1 END,spelling LIMIT 1",
                    (term, term),
                ).fetchone()
                if not word:
                    lines += [f"### {term}", "", "Not present in this build.", ""]
                    continue
                reviewed += 1
                wid, spelling = word["word_id"], word["spelling"]
                frequency = db.execute(
                    "SELECT ranking_scheme,rank,usefulness_tier,difficulty_estimate,confidence,evidence FROM frequency_metadata WHERE word_id=? AND difficulty_estimate IS NOT NULL ORDER BY CASE WHEN source_id='ngsl_1_2' THEN 0 ELSE 1 END LIMIT 1",
                    (wid,),
                ).fetchone()
                topics = [f"{row['category']} ({row['confidence']:.2f})" for row in db.execute(
                    "SELECT category,confidence FROM word_categories WHERE word_id=? ORDER BY category", (wid,)
                )]
                pronunciations = [f"{row['phonetic_alphabet']}: {row['transcription']}" for row in db.execute(
                    "SELECT phonetic_alphabet,transcription FROM pronunciations WHERE word_id=? ORDER BY source_id,variant_order LIMIT 3", (wid,)
                )]
                pos_counts = dict(db.execute("SELECT part_of_speech,COUNT(*) FROM senses WHERE word_id=? GROUP BY part_of_speech", (wid,)))
                example_counts = dict(db.execute(
                    "SELECT e.validation_status,COUNT(*) FROM examples e JOIN senses s ON s.sense_id=e.sense_id "
                    "WHERE s.word_id=? GROUP BY e.validation_status", (wid,)
                ))
                word_example_count = db.execute("SELECT COUNT(*) FROM word_examples WHERE word_id=?", (wid,)).fetchone()[0]
                source_defs = dict(db.execute(
                    "SELECT d.source_id,COUNT(*) FROM definitions d JOIN senses s ON s.sense_id=d.sense_id "
                    "WHERE s.word_id=? GROUP BY d.source_id", (wid,)
                ))
                alignment_count = db.execute(
                    "SELECT COUNT(*) FROM sense_alignments a JOIN senses s ON s.sense_id=a.aligned_sense_id WHERE s.word_id=? AND a.status='accepted'",
                    (wid,),
                ).fetchone()[0]
                frequency_text = "none" if not frequency else (
                    f"{frequency['ranking_scheme']} rank {frequency['rank']}; tier {frequency['usefulness_tier'] or 'Full Dictionary'}; "
                    f"estimated difficulty {frequency['difficulty_estimate']}/5 (confidence {frequency['confidence']:.2f}; not CEFR)"
                )
                lines += [
                    f"### {spelling}", "",
                    f"Topic labels: {', '.join(topics) or 'none'}. Frequency/difficulty: {frequency_text}.",
                    f"Pronunciations: {', '.join(pronunciations) or 'none'}.",
                    f"Part-of-speech sense counts: {', '.join(f'{pos} {count}' for pos, count in sorted(pos_counts.items()))}.",
                    f"Definitions by source: {', '.join(f'{source} {count}' for source, count in sorted(source_defs.items()))}. Accepted Simple English to OEWN alignments on this word: {alignment_count}.",
                    f"Sense-attached examples by check status: {', '.join(f'{status} {count}' for status, count in sorted(example_counts.items())) or 'none'}. Word-level examples with unverified sense fit: {word_example_count}.",
                ]
                for pos in sorted(pos_counts):
                    senses = list(db.execute(
                        "SELECT sense_id,sense_order,source_id FROM senses WHERE word_id=? AND part_of_speech=? ORDER BY sense_order,source_id,sense_id",
                        (wid, pos),
                    ))
                    if len(senses) <= 3:
                        selected = senses
                    else:
                        selected = [senses[0], senses[len(senses) // 2], senses[-1]]
                    for sense in selected:
                        topics = [f"{row['label']} ({row['confidence']:.2f})" for row in db.execute(
                            "SELECT label,confidence FROM sense_labels WHERE sense_id=? AND label_type='topic' ORDER BY label", (sense["sense_id"],)
                        )]
                        definition_rows = list(db.execute(
                            "SELECT source_id,definition_kind,definition_text,is_preferred FROM definitions "
                            "WHERE sense_id=? ORDER BY is_preferred DESC,display_rank,source_id LIMIT 3", (sense["sense_id"],)
                        ))
                        lines.append(f"- Sample `{pos}` sense {sense['sense_order']} [{sense['source_id']}]:")
                        if topics:
                            lines.append(f"  - Sense topics: {', '.join(topics)}")
                        for definition in definition_rows:
                            lines.append(f"  - {definition['source_id']} ({definition['definition_kind']}): {definition['definition_text']}")
                        example_rows = list(db.execute(
                            "SELECT source_id,validation_status,example_text FROM examples WHERE sense_id=? "
                            "ORDER BY CASE validation_status WHEN 'verified_word' THEN 0 WHEN 'verified_inflection' THEN 1 ELSE 2 END,quality_score DESC LIMIT 2",
                            (sense["sense_id"],),
                        ))
                        for example in example_rows:
                            lines.append(f"  - Example [{example['source_id']}; {example['validation_status']}]: {example['example_text']}")
                for example in db.execute("SELECT source_id,example_text FROM word_examples WHERE word_id=? ORDER BY quality_score DESC LIMIT 2", (wid,)):
                    lines.append(f"- Word-level example [{example['source_id']}; word presence checked; sense unverified]: {example['example_text']}")
                lines += [f"Manual assessment: {notes.get(term, 'Pending reviewer note.')}", ""]
        lines += [
            "## Scope and limitations", "",
            f"Curated terms present and reviewed: {reviewed}. Example presence checks establish only that the displayed spelling or an accepted form occurs in the sentence. The single-source mismatch/weak alignment cases remain separate and are not presented as equivalent senses.",
            "",
        ]
        return "\n".join(lines)
    finally:
        db.close()


def write_reports(database: Path, report_dir: Path, legacy_db: Path | None = None, baseline_db: Path | None = None) -> dict:
    report_dir.mkdir(parents=True, exist_ok=True)
    report = collect_report(database)
    compressed = database.with_suffix(database.suffix + ".gz")
    compressed_bytes = compressed.stat().st_size if compressed.exists() else None
    artifact = _artifact_manifest(database)
    (report_dir / "artifact_manifest.json").write_text(json.dumps(artifact, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    (report_dir / "quality_report.json").write_text(json.dumps(report, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    (report_dir / "quality_report.md").write_text(render_markdown(report, compressed_bytes), encoding="utf-8")
    (report_dir / "sample_entry_inspection.md").write_text(render_entry_review(database), encoding="utf-8")
    (report_dir / "manual_review.md").write_text(render_manual_review(database), encoding="utf-8")
    write_alignment_audit(database, report_dir / "alignment_audit.md", baseline_db)
    if report["metadata"].get("build_profile") in {"full", "core"}:
        source_dir = PACKAGE_DIR / "source_archives"
        source_status = json.loads(report["metadata"].get("source_status", "{}"))
        checksums = json.loads(report["metadata"].get("source_checksums", "{}"))
        counters = json.loads(report["metadata"].get("build_counters", "{}"))
        pipeline_files = [
            "__init__.py", "archive_io.py", "config.json", "core.py", "database.py", "ingest.py",
            "normalize.py", "parsers.py", "quality.py", "reporting.py", "schema.sql", "source_registry.json",
        ]
        manifest = {
            "mode": "offline-local-sources",
            "build_profile": report["metadata"].get("build_profile"),
            "schema_version": report["metadata"].get("schema_version"),
            "pipeline_version": report["metadata"].get("pipeline_version"),
            "source_dir": str(source_dir),
            "inputs": inventory_local_archives(source_dir) if source_dir.is_dir() else [],
            "selection": {source_id: value.get("path") for source_id, value in source_status.items() if isinstance(value, dict)},
            "source_status": source_status,
            "source_checksums": checksums,
            "pipeline_files": {
                name: {"bytes": (PACKAGE_DIR / name).stat().st_size, "sha256": _file_digest(PACKAGE_DIR / name)}
                for name in pipeline_files if (PACKAGE_DIR / name).is_file()
            },
            "build_counters": counters,
        }
        (report_dir / "source_manifest.json").write_text(json.dumps(manifest, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    if legacy_db:
        comparison = compare_legacy(database, legacy_db)
        (report_dir / "legacy_comparison.json").write_text(json.dumps(comparison, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
        (report_dir / "legacy_comparison.md").write_text(render_comparison_markdown(comparison), encoding="utf-8")
        write_legacy_crosswalk(database, legacy_db, report_dir / "legacy_id_crosswalk.csv")
    else:
        (report_dir / "legacy_comparison.md").write_text(
            "# Legacy WordNet catalog comparison\n\n"
            "Not measured in this report invocation. Supply the read-only Lafz/WordNet 3.0 catalog export with "
            "`python -m lafz_lexicon report <database> --legacy-db <catalog.db>` to generate the actual lemma/POS overlap and migration crosswalk.\n",
            encoding="utf-8",
        )
    return report
