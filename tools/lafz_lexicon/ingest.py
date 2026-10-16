from __future__ import annotations

import hashlib
import json
import logging
import re
import sqlite3
import unicodedata
from pathlib import Path
from collections.abc import Callable, Iterable

from .normalize import clean_text, inflections, lexical_key, normalize_pos, stable_id, tokens, valid_lemma
from .parsers import parse_kaikki_jsonl, parse_ngsl_words, parse_oewn_json_records, parse_oewn_xml, parse_cmudict, parse_tatoeba_sentences
from .quality import definition_quality, example_quality, valid_arpabet, align_definition


from .topics import add_topics, classify_definition_topic, classify_lexname, refresh_topics


POS_MAP = {"n": "noun", "v": "verb", "a": "adjective", "s": "adjective", "r": "adverb"}
RELATION_MAP = {
    "hypernym": "hypernym", "hyponym": "hyponym", "antonym": "antonym",
    "similar": "similar_to", "also_sense": "also_sense", "meronym": "meronym",
    "holonym": "holonym", "domain_topic": "domain_topic", "domain_region": "domain_region",
    "domain_usage": "domain_usage", "derivation": "derivation", "attribute": "attribute",
}
LOG = logging.getLogger(__name__)


def _word_id(spelling: str) -> str:
    exact = unicodedata.normalize("NFKC", clean_text(spelling))
    digest = hashlib.sha256(exact.encode("utf-8")).hexdigest()[:24]
    return f"w_{digest}"


def _source_sense_id(source_id: str, word_id: str, native_id: str | None, synset_id: str) -> str:
    return f"s_{hashlib.sha256((source_id + chr(31) + (native_id or '') + chr(31) + word_id + chr(31) + synset_id).encode()).hexdigest()[:24]}"


def _definition_id(sense_id: str, source_id: str, text: str, ordinal: int) -> str:
    return stable_id("d", sense_id, source_id, str(ordinal), text)


def _example_id(sense_id: str, source_id: str, text: str, ordinal: int) -> str:
    return stable_id("e", sense_id, source_id, str(ordinal), text)


def _prefer_ordinary_lemma(spelling: str) -> bool:
    """Exclude likely named multiword entities from the learner-facing target set.

    OEWN 2025 already separates proper nouns into Open English Namenet. This
    catches capitalized multiword exceptions (such as botanical binomials)
    without lowercasing or merging any retained capitalization variants.
    """
    parts = spelling.split()
    return len(parts) == 1 or not any(part[:1].isupper() for part in parts)


def _attribution(db: sqlite3.Connection, source_id: str, record_type: str, record_id: str, detail: str = "") -> None:
    # Every normalized content row already carries source_id. Store only
    # provenance details that cannot be reconstructed from that row (for
    # example a Tatoeba source sentence ID), avoiding a duplicate index row
    # for every definition, form, and relationship in the database.
    if not detail:
        return
    attribution_id = stable_id("attr", source_id, record_type, record_id, detail)
    db.execute(
        "INSERT OR IGNORE INTO source_attributions VALUES(?,?,?,?,?)",
        (attribution_id, source_id, record_type, record_id, detail),
    )


def insert_oewn(
    db: sqlite3.Connection,
    records: Iterable[dict] | Callable[[], Iterable[dict]],
    source_id: str = "oewn_2025",
) -> dict:
    """Insert OEWN rows while replaying a source iterator instead of retaining it.

    The XML parser itself streams synsets after building only its compact
    lexical-entry-to-synset index. A callable lets this importer make a second
    source pass for relations without keeping every record/sense in RAM.
    """
    if callable(records):
        record_factory = records
    elif isinstance(records, (list, tuple)):
        record_factory = lambda: iter(records)
    else:
        cached = list(records)
        record_factory = lambda: iter(cached)

    counters = {
        "synsets_seen": 0, "members_seen": 0, "filtered_members": 0,
        "duplicate_synsets": 0, "duplicate_senses": 0, "duplicate_definitions": 0,
        "duplicate_examples": 0, "omitted_examples": 0, "semantic_relations": 0, "duplicate_relations": 0,
    }
    sense_order: dict[tuple[str, str], int] = {}

    for record in record_factory():
        synset_id = clean_text(record.get("id"))
        pos = normalize_pos(str(record.get("partOfSpeech", "")))
        if not synset_id or not pos:
            continue
        counters["synsets_seen"] += 1
        inserted_synset = db.execute(
            "INSERT OR IGNORE INTO synsets(synset_id,source_id,part_of_speech,lexname,ili) VALUES(?,?,?,?,?)",
            (synset_id, source_id, pos, clean_text(record.get("lexname")) or None, record.get("ili")),
        ).rowcount
        counters["duplicate_synsets"] += int(not inserted_synset)

        for member in record.get("members", []):
            counters["members_seen"] += 1
            spelling = clean_text(member.get("lemma"))
            member_pos = normalize_pos(str(member.get("poskey", record.get("partOfSpeech", "")))) or pos
            if not valid_lemma(spelling) or not member_pos or not _prefer_ordinary_lemma(spelling):
                counters["filtered_members"] += 1
                continue
            wid = _word_id(spelling)
            db.execute(
                "INSERT OR IGNORE INTO words(word_id,spelling,lookup_key,is_multiword,quality_score) VALUES(?,?,?,?,?)",
                (wid, spelling, lexical_key(spelling), int(" " in spelling), 0.0),
            )
            native_sense_id = (member.get("sense") or {}).get("id")
            sid = _source_sense_id(source_id, wid, native_sense_id, synset_id)
            pos_key = (wid, member_pos)
            next_order = sense_order.get(pos_key, 0) + 1
            inserted_sense = db.execute(
                "INSERT OR IGNORE INTO senses(sense_id,word_id,synset_id,source_id,source_sense_id,part_of_speech,sense_order,alignment_confidence,quality_score) VALUES(?,?,?,?,?,?,?,?,?)",
                (sid, wid, synset_id, source_id, native_sense_id, member_pos, next_order, 1.0, 0.85),
            ).rowcount
            if not inserted_sense:
                counters["duplicate_senses"] += 1
                continue
            sense_order[pos_key] = next_order
            _attribution(db, source_id, "sense", sid)

            seen_definitions: set[str] = set()
            for index, definition in enumerate(record.get("definition", []) or []):
                text = clean_text(definition)
                if not text:
                    continue
                normalized = text.casefold()
                if normalized in seen_definitions:
                    counters["duplicate_definitions"] += 1
                    continue
                seen_definitions.add(normalized)
                did = _definition_id(sid, source_id, text, index)
                db.execute(
                    "INSERT OR IGNORE INTO definitions(definition_id,sense_id,source_id,definition_text,language,definition_kind,display_rank,quality_score,is_preferred) VALUES(?,?,?,?,?,?,?,?,?)",
                    (did, sid, source_id, text, "en", "source", index, definition_quality(text, 0), int(not any(seen_definitions - {normalized}))),
                )
                _attribution(db, source_id, "definition", did)

            forms: set[str] = set()
            for form in member.get("form", []) or []:
                form = clean_text(form)
                if not form or not valid_lemma(form):
                    continue
                forms.add(form)
                fid = stable_id("form", wid, source_id, form, "inflection")
                db.execute(
                    "INSERT OR IGNORE INTO word_forms VALUES(?,?,?,?,?,?)",
                    (fid, wid, source_id, form, lexical_key(form), "inflection"),
                )
                _attribution(db, source_id, "word_form", fid)

            for variant, pronunciation in enumerate(member.get("pronunciation", []) or [], 1):
                if not isinstance(pronunciation, dict):
                    continue
                transcription = clean_text(pronunciation.get("value"))
                if not transcription:
                    continue
                alphabet = "IPA" if any(char in transcription for char in "ɪʊəˈˌθðŋæɑɔɛɹʃʒʌɒ") else "unknown"
                pid = stable_id("p", wid, source_id, alphabet, str(variant), transcription)
                db.execute(
                    "INSERT OR IGNORE INTO pronunciations VALUES(?,?,?,?,?,?,?)",
                    (pid, wid, source_id, alphabet, pronunciation.get("variety"), variant, transcription),
                )
                _attribution(db, source_id, "pronunciation", pid)

            seen_examples: set[str] = set()
            for example in record.get("example", []) or []:
                text = clean_text(example)
                if not text:
                    continue
                if text.casefold() in seen_examples:
                    counters["duplicate_examples"] += 1
                    continue
                seen_examples.add(text.casefold())
                checked = example_quality(text, spelling, forms)
                if not checked:
                    counters["omitted_examples"] += 1
                    continue
                status, score = checked
                eid = _example_id(sid, source_id, text, 0)
                db.execute("INSERT OR IGNORE INTO examples VALUES(?,?,?,?,?,?,?)", (eid, sid, source_id, text, status, None, score))
                _attribution(db, source_id, "example", eid)

    # The word quality estimate is based on OEWN senses only and stays separate
    # from frequency and difficulty scores.
    db.execute(
        "UPDATE words SET quality_score=MIN(1.0,0.62+(CASE WHEN is_multiword=0 THEN 0.04 ELSE 0.0 END)+"
        "MIN(0.18,0.02*(SELECT COUNT(*) FROM senses s WHERE s.word_id=words.word_id AND s.source_id=?)))",
        (source_id,),
    )

    # Relations are replayed after all synsets have been inserted, so targets
    # absent from the selected OEWN base release are not represented as edges.
    for record in record_factory():
        source_synset = record.get("id")
        relation_groups = dict(record.get("relations", {}))
        for key, values in record.items():
            if key in RELATION_MAP and isinstance(values, list):
                relation_groups.setdefault(key, []).extend(
                    item.get("target_synset") if isinstance(item, dict) else item for item in values
                )
        for relation_name, targets in relation_groups.items():
            relation_type = RELATION_MAP.get(str(relation_name).casefold(), str(relation_name).casefold())
            for target in targets or []:
                if isinstance(target, dict):
                    target = target.get("target_synset") or target.get("target")
                if not target or not db.execute("SELECT 1 FROM synsets WHERE synset_id=?", (target,)).fetchone():
                    continue
                rid = stable_id("rel", source_id, source_synset, relation_type, str(target))
                inserted = db.execute(
                    "INSERT OR IGNORE INTO semantic_relations VALUES(?,?,?,?,?,?)",
                    (rid, source_synset, str(target), source_id, relation_type, 1.0),
                ).rowcount
                if inserted:
                    counters["semantic_relations"] += 1
                    _attribution(db, source_id, "semantic_relation", rid)
                else:
                    counters["duplicate_relations"] += 1

    counters["words"] = db.execute("SELECT COUNT(*) FROM words").fetchone()[0]
    counters["senses"] = db.execute("SELECT COUNT(*) FROM senses WHERE source_id=?", (source_id,)).fetchone()[0]
    counters["synsets"] = db.execute("SELECT COUNT(*) FROM synsets WHERE source_id=?", (source_id,)).fetchone()[0]
    return counters


def _word_candidates(db: sqlite3.Connection) -> dict[str, list[tuple[str, str]]]:
    result: dict[str, list[tuple[str, str]]] = {}
    for wid, spelling in db.execute("SELECT word_id,spelling FROM words"):
        result.setdefault(lexical_key(spelling), []).append((wid, spelling))
    return result


def _choose_word_id(candidates: list[tuple[str, str]] | None, spelling: str) -> str | None:
    if not candidates:
        return None
    exact = [wid for wid, candidate_spelling in candidates if candidate_spelling == spelling]
    if len(exact) == 1:
        return exact[0]
    if len(candidates) == 1:
        return candidates[0][0]
    return None


def add_cmudict(db: sqlite3.Connection, path: Path, source_id: str = "cmudict") -> dict:
    word_lookup = _word_candidates(db)
    added = unmatched = ambiguous_case = 0
    for spelling, transcription, variant in parse_cmudict(path):
        candidates = word_lookup.get(lexical_key(spelling))
        wid = _choose_word_id(candidates, spelling)
        if not candidates:
            unmatched += 1
            continue
        if not wid:
            ambiguous_case += 1
            continue
        if not valid_arpabet(transcription):
            continue
        pid = stable_id("p", wid, source_id, "ARPABET", "en-US", str(variant), transcription)
        db.execute(
            "INSERT OR IGNORE INTO pronunciations VALUES(?,?,?,?,?,?,?)",
            (pid, wid, source_id, "ARPABET", "en-US", variant, transcription),
        )
        _attribution(db, source_id, "pronunciation", pid)
        added += 1
    return {"pronunciations_added": added, "unmatched_entries": unmatched, "ambiguous_case_entries": ambiguous_case}


def add_ngsl(db: sqlite3.Connection, path: Path, source_id: str = "ngsl_1_2") -> dict:
    word_lookup = _word_candidates(db)
    matched = ranked = ambiguous_memberships = 0
    for spelling, rank in parse_ngsl_words(path):
        candidates = word_lookup.get(lexical_key(spelling), [])
        if not candidates:
            continue
        # Frequency/list membership applies to a spelling regardless of case;
        # keep its independent capitalized and lowercase word records.
        matched += len(candidates)
        ambiguous_memberships += int(len(candidates) > 1)
        tier = "Everyday" if rank is not None and rank <= 1000 else ("Stretch" if rank is not None and rank <= 2809 else None)
        difficulty = None if rank is None else (1 if rank <= 500 else 2 if rank <= 1000 else 3 if rank <= 1800 else 4 if rank <= 2809 else 5)
        for wid, _ in candidates:
            db.execute(
                "INSERT OR REPLACE INTO frequency_metadata(word_id,source_id,ranking_scheme,rank,usefulness_tier,difficulty_estimate,confidence,evidence) VALUES(?,?,?,?,?,?,?,?)",
                (wid, source_id, "NGSL 1.2", rank, tier, difficulty, 0.95 if rank is not None else 1.0,
                 "NGSL rank-derived heuristic band; not CEFR" if rank is not None else "official NGSL membership; no rank-derived band"),
            )
            _attribution(db, source_id, "frequency_metadata", wid)
            ranked += int(rank is not None)
    return {"matched": matched, "ranked": ranked, "case_variant_memberships": ambiguous_memberships}


def add_kaikki(db: sqlite3.Connection, path: Path, source_id: str) -> dict:
    """Selectively import English senses only for existing OEWN headwords.

    Ambiguous definitions remain separate source senses; only a unique high
    similarity match becomes an alternative definition on the OEWN sense.
    """
    target_words = _word_candidates(db)
    candidates: dict[tuple[str, str], list[tuple[str, str]]] = {}
    for sid, wid, pos, text in db.execute(
        "SELECT sense_id,word_id,part_of_speech,definition_text FROM ("
        "SELECT s.sense_id,s.word_id,s.part_of_speech,d.definition_text,"
        "ROW_NUMBER() OVER (PARTITION BY s.sense_id ORDER BY d.is_preferred DESC,d.display_rank,d.definition_id) AS rn "
        "FROM senses s JOIN definitions d ON d.sense_id=s.sense_id "
        "WHERE s.synset_id IS NOT NULL AND d.source_id=s.source_id) WHERE rn=1"
    ):
        candidates.setdefault((wid, pos), []).append((sid, text))

    counts = {"rows_seen": 0, "rows_kept": 0, "definitions": 0, "examples": 0, "rejected_no_gloss": 0, "ambiguous_word_matches": 0, "alignments_accepted": 0, "alignments_unmatched": 0, "alignments_ambiguous": 0}
    for row in parse_kaikki_jsonl(path):
        counts["rows_seen"] += 1
        key = lexical_key(row.get("word", ""))
        word_options = target_words.get(key, [])
        wid = _choose_word_id(word_options, clean_text(row.get("word", "")))
        if len(word_options) > 1 and not wid:
            counts["ambiguous_word_matches"] += 1
        pos = {"noun": "noun", "verb": "verb", "adj": "adjective", "adjective": "adjective", "adv": "adverb", "adverb": "adverb"}.get(row.get("pos"))
        if not wid or not pos:
            continue
        counts["rows_kept"] += 1
        form_rows = row.get("forms", []) or []
        explicit_forms = set()
        for form_row in form_rows:
            form = clean_text(form_row.get("form")) if isinstance(form_row, dict) else clean_text(str(form_row))
            if not form or not valid_lemma(form):
                continue
            explicit_forms.add(form)
            fid = stable_id("form", wid, source_id, form, "inflection")
            db.execute("INSERT OR IGNORE INTO word_forms VALUES(?,?,?,?,?,?)", (fid, wid, source_id, form, lexical_key(form), "inflection"))
            _attribution(db, source_id, "word_form", fid)
        for sound in row.get("sounds", []) or []:
            if not isinstance(sound, dict):
                continue
            transcription = clean_text(sound.get("ipa"))
            if not transcription:
                continue
            pid = stable_id("p", wid, source_id, "IPA", str(sound.get("tags", "")), transcription)
            db.execute("INSERT OR IGNORE INTO pronunciations VALUES(?,?,?,?,?,?,?)", (pid, wid, source_id, "IPA", ",".join(sound.get("tags", [])), 1, transcription))
            _attribution(db, source_id, "pronunciation", pid)
        for index, sense_row in enumerate(row.get("senses", []) or []):
            glosses = [clean_text(gloss) for gloss in sense_row.get("glosses", []) if clean_text(gloss)]
            if not glosses:
                counts["rejected_no_gloss"] += 1
                continue
            existing = candidates.get((wid, pos), [])
            best_sense = None
            confidence = 0.0
            method = "no_same_lemma_pos_candidate"
            if existing:
                aligned_index, confidence, method = align_definition(glosses[0], [text for _, text in existing])
                if aligned_index is not None:
                    best_sense = existing[aligned_index][0]
            source_sense_key = stable_id("ks", source_id, key, pos, str(index), glosses[0])
            alignment_status = "accepted" if best_sense else ("ambiguous" if method == "ambiguous_tie" else "unmatched")
            alignment_id = stable_id("align", source_id, source_sense_key, best_sense or "unmatched", method)
            db.execute("INSERT OR IGNORE INTO sense_alignments VALUES(?,?,?,?,?,?,?)", (
                alignment_id, source_id, source_sense_key, best_sense, confidence, method, alignment_status,
            ))
            counts[f"alignments_{alignment_status}"] += 1
            sid = best_sense or stable_id("s", source_id, key, pos, str(index), glosses[0])
            if not best_sense:
                db.execute(
                    "INSERT OR IGNORE INTO senses(sense_id,word_id,synset_id,source_id,source_sense_id,part_of_speech,sense_order,alignment_confidence,quality_score) VALUES(?,?,NULL,?,?,?,?,?,?)",
                    (sid, wid, source_id, f"{key}:{pos}:{index}", pos, 1000 + index, confidence if confidence else None, 0.48),
                )
            for gloss_index, gloss in enumerate(glosses):
                did = _definition_id(sid, source_id, gloss, gloss_index)
                db.execute(
                    "INSERT OR IGNORE INTO definitions VALUES(?,?,?,?,?,?,?,?,?)",
                    (did, sid, source_id, gloss, "en", "learner" if source_id == "simple_wiktionary" else "alternative", 20 + gloss_index, definition_quality(gloss, 1), 0),
                )
                _attribution(db, source_id, "definition", did)
                counts["definitions"] += 1
            for label in (sense_row.get("tags", []) or []) + (sense_row.get("raw_tags", []) or []):
                label = clean_text(str(label))
                if label and len(label) < 80:
                    db.execute("INSERT OR IGNORE INTO sense_labels(sense_id,source_id,label,label_type,confidence) VALUES(?,?,?,?,?)", (sid, source_id, label, "usage", 1.0))
            for example_row in sense_row.get("examples", []) or []:
                text = clean_text(example_row.get("text") if isinstance(example_row, dict) else str(example_row))
                checked = example_quality(text, row.get("word", ""), explicit_forms)
                if not checked:
                    continue
                status, score = checked
                eid = stable_id("e", sid, source_id, text)
                db.execute("INSERT OR IGNORE INTO examples VALUES(?,?,?,?,?,?,?)", (eid, sid, source_id, text, status, confidence or None, score))
                _attribution(db, source_id, "example", eid)
                counts["examples"] += 1
            for relation_type in ("synonyms", "antonyms"):
                for relation in sense_row.get(relation_type, []) or []:
                    target_spelling = clean_text(relation.get("word") if isinstance(relation, dict) else str(relation))
                    target_wid = _choose_word_id(target_words.get(lexical_key(target_spelling)), target_spelling)
                    if not target_wid or target_wid == wid:
                        continue
                    rid = stable_id("wrel", wid, target_wid, sid, source_id, relation_type)
                    db.execute("INSERT OR IGNORE INTO word_relations VALUES(?,?,?,?,?,?,?)", (rid, wid, target_wid, sid, source_id, relation_type[:-1] if relation_type == "synonyms" else "antonym", 0.9))
                    _attribution(db, source_id, "word_relation", rid)
    return counts


def add_tatoeba_sentences(
    db: sqlite3.Connection,
    path: Path,
    source_id: str = "tatoeba_english",
    max_examples_per_word: int = 3,
) -> dict:
    """Select short Tatoeba examples and retain source sentence IDs.

    The available archive is the general English export, not the CC0 subset.
    Rows are therefore attributed to Tatoeba under CC BY 2.0 France. The
    import confirms the headword/form only; sense fit stays explicitly
    unverified, and a small deterministic best-of cap prevents common words
    from dominating the offline database.
    """
    word_rows = list(db.execute("SELECT word_id,spelling FROM words"))
    forms: dict[str, set[str]] = {}
    for wid, form in db.execute("SELECT word_id,form FROM word_forms"):
        forms.setdefault(wid, set()).add(form)
    token_index: dict[str, set[str]] = {}
    phrase_trie: dict = {}
    max_phrase_tokens = 0
    for wid, spelling in word_rows:
        for surface in inflections(spelling, forms.get(wid)):
            surface_tokens = tuple(tokens(surface))
            if len(surface_tokens) == 1:
                token_index.setdefault(surface_tokens[0], set()).add(wid)
            elif len(surface_tokens) > 1:
                max_phrase_tokens = max(max_phrase_tokens, len(surface_tokens))
                node = phrase_trie
                for token in surface_tokens:
                    node = node.setdefault(token, {})
                node.setdefault("_matches", set()).add(wid)
    counts = {
        "sentences_seen": 0, "candidate_word_hits": 0, "examples_kept": 0,
        "sentences_rejected": 0, "candidate_hits_limited": 0, "words_with_examples": 0,
        "near_duplicate_sentences_rejected": 0,
    }
    best_by_word: dict[str, list[tuple[float, str, str]]] = {}
    for sentence_id, sentence in parse_tatoeba_sentences(path):
        counts["sentences_seen"] += 1
        if counts["sentences_seen"] % 250_000 == 0:
            LOG.info("Tatoeba scan: %s of local sentence rows", f"{counts['sentences_seen']:,}")
        sentence_tokens = tokens(sentence)
        candidates = set()
        for token in sentence_tokens:
            candidates.update(token_index.get(token, ()))
        if max_phrase_tokens:
            for start in range(len(sentence_tokens)):
                node = phrase_trie
                for token in sentence_tokens[start:start + max_phrase_tokens]:
                    node = node.get(token)
                    if node is None:
                        break
                    candidates.update(node.get("_matches", ()))
        if not candidates:
            continue
        counts["candidate_word_hits"] += len(candidates)
        if (len(sentence) > 200 or not 4 <= len(sentence_tokens) <= 24
                or len(set(sentence_tokens)) < 3
                or "http://" in sentence.casefold() or "https://" in sentence.casefold()):
            counts["sentences_rejected"] += 1
            continue
        ending_score = 0.08 if sentence.rstrip().endswith((".", "?", "!")) else 0.0
        length_score = 0.18 if 4 <= len(sentence_tokens) <= 16 else (0.08 if len(sentence_tokens) <= 22 else 0.0)
        readability_score = -0.08 if any(char.isdigit() for char in sentence) else 0.0
        score = round(max(0.35, min(0.86, 0.6 + ending_score + length_score + readability_score)), 3)
        for wid in candidates:
            best = best_by_word.setdefault(wid, [])
            candidate_tokens = set(sentence_tokens)
            duplicate = False
            for existing in best:
                if existing[1] == sentence_id:
                    duplicate = True
                    break
                existing_tokens = set(tokens(existing[2]))
                union = candidate_tokens | existing_tokens
                overlap = candidate_tokens & existing_tokens
                if union and len(overlap) / len(union) >= 0.82:
                    duplicate = True
                    counts["near_duplicate_sentences_rejected"] += 1
                    break
            if duplicate:
                continue
            best.append((score, sentence_id, sentence))
            best.sort(key=lambda item: (-item[0], (0, int(item[1])) if item[1].isdigit() else (1, item[1]), item[2]))
            if len(best) > max_examples_per_word:
                best.pop()
                counts["candidate_hits_limited"] += 1

    for wid, examples in best_by_word.items():
        counts["words_with_examples"] += 1
        for score, sentence_id, sentence in examples:
            eid = stable_id("we", wid, source_id, sentence_id, sentence)
            inserted = db.execute(
                "INSERT OR IGNORE INTO word_examples(word_example_id,word_id,source_id,example_text,validation_status,sense_match_status,quality_score) VALUES(?,?,?,?,?,?,?)",
                (eid, wid, source_id, sentence, "word_presence_only", "unverified", score),
            ).rowcount
            if inserted:
                _attribution(db, source_id, "word_example", eid, f"sentence_id={sentence_id}")
                counts["examples_kept"] += 1
    return counts


def _orthographic_syllables(word: str) -> int:
    cleaned = re.sub(r"[^a-z]", "", word.casefold())
    if not cleaned:
        return 1
    groups = len(re.findall(r"[aeiouy]+", cleaned))
    if cleaned.endswith("e") and not cleaned.endswith(("le", "ye")) and groups > 1:
        groups -= 1
    if cleaned.endswith("ed") and len(cleaned) > 3 and cleaned[-3] not in "td":
        groups = max(1, groups - 1)
    return max(1, groups)


def add_difficulty_estimates(db: sqlite3.Connection) -> dict:
    """Fill unranked lemmas with an explicit low-confidence lexical heuristic."""
    cmu_syllables: dict[str, int] = {}
    for wid, transcription in db.execute(
        "SELECT word_id,transcription FROM pronunciations WHERE source_id='cmudict' AND phonetic_alphabet='ARPABET' ORDER BY variant_order"
    ):
        cmu_syllables.setdefault(wid, sum(phone[-1:].isdigit() for phone in transcription.split()))
    specialist_lexnames = {"noun.body", "noun.phenomenon", "noun.process", "noun.substance", "noun.act", "verb.change"}
    rare_labels = {"rare", "archaic", "obsolete", "dated", "formal", "literary", "technical"}
    prefixes = ("anti", "auto", "bio", "counter", "de", "dis", "hyper", "inter", "micro", "mis", "multi", "non", "over", "pre", "re", "sub", "trans", "un")
    suffixes = ("able", "ation", "ence", "ency", "esque", "hood", "ical", "ious", "ism", "ity", "ment", "ology", "ous", "tion", "tude")
    existing = {wid for (wid,) in db.execute(
        "SELECT word_id FROM frequency_metadata WHERE ranking_scheme='NGSL 1.2' AND difficulty_estimate IS NOT NULL"
    )}
    added = cmu_based = 0
    for wid, spelling in db.execute("SELECT word_id,spelling FROM words ORDER BY word_id"):
        if wid in existing:
            continue
        pieces = re.findall(r"[A-Za-z]+", spelling)
        letters = sum(map(len, pieces))
        exact_syllables = cmu_syllables.get(wid)
        syllables = exact_syllables if exact_syllables else sum(_orthographic_syllables(piece) for piece in pieces)
        joined = "".join(pieces).casefold()
        has_affix = any(joined.startswith(prefix) and len(joined) > len(prefix) + 2 for prefix in prefixes) or any(
            joined.endswith(suffix) and len(joined) > len(suffix) + 2 for suffix in suffixes
        )
        technical = db.execute(
            "SELECT 1 FROM senses s JOIN synsets sy ON sy.synset_id=s.synset_id WHERE s.word_id=? AND sy.lexname IN ("
            + ",".join("?" for _ in specialist_lexnames) + ") LIMIT 1", (wid, *sorted(specialist_lexnames)),
        ).fetchone() is not None
        rare = db.execute(
            "SELECT 1 FROM sense_labels WHERE sense_id IN (SELECT sense_id FROM senses WHERE word_id=?) "
            "AND lower(label) IN (" + ",".join("?" for _ in rare_labels) + ") LIMIT 1",
            (wid, *sorted(rare_labels)),
        ).fetchone() is not None
        score = 1 + int(syllables >= 2) + int(syllables >= 3) + int(syllables >= 5)
        score += int(letters >= 8) + int(letters >= 12) + int(has_affix) + int(technical or rare)
        score = min(5, score)
        confidence = 0.55 if exact_syllables else 0.32
        if technical or rare:
            confidence += 0.06
        feature_text = (
            f"Lafz lexical complexity heuristic v1; syllables={syllables} ({'CMUdict ARPABET' if exact_syllables else 'orthographic estimate'}); "
            f"letters={letters}; affix_signal={'yes' if has_affix else 'no'}; "
            f"specialist_or_usage_signal={'yes' if technical or rare else 'no'}; not CEFR"
        )
        db.execute(
            "INSERT OR REPLACE INTO frequency_metadata(word_id,source_id,ranking_scheme,rank,usefulness_tier,difficulty_estimate,confidence,evidence) VALUES(?,?,?,?,?,?,?,?)",
            (wid, "lafz_heuristics", "Lafz lexical complexity v1", None, None, score, confidence, feature_text),
        )
        added += 1
        cmu_based += int(bool(exact_syllables))
    return {"heuristic_estimates_added": added, "cmu_syllable_based": cmu_based, "orthographic_fallback": added - cmu_based}


def add_distractors(db: sqlite3.Connection, config: dict) -> int:
    max_count = int(config.get("quiz", {}).get("max_distractors", 4))
    gap = int(config.get("quiz", {}).get("max_rank_gap", 350))
    ranked: dict[str, tuple[int | None, str | None]] = {}
    for wid, rank, tier in db.execute("SELECT word_id,rank,usefulness_tier FROM frequency_metadata WHERE ranking_scheme='NGSL 1.2'"):
        ranked[wid] = (rank, tier)
    if len(ranked) < 2:
        return 0
    pos_by_word: dict[str, set[str]] = {}
    for wid, pos in db.execute("SELECT DISTINCT word_id,part_of_speech FROM senses"):
        pos_by_word.setdefault(wid, set()).add(pos)
    synsets_by_word: dict[str, set[str]] = {}
    for wid, synset_id in db.execute("SELECT DISTINCT word_id,synset_id FROM senses WHERE synset_id IS NOT NULL"):
        synsets_by_word.setdefault(wid, set()).add(synset_id)
    meanings_by_word: dict[str, set[str]] = {}
    for wid, definition in db.execute("SELECT DISTINCT s.word_id,d.definition_text FROM senses s JOIN definitions d ON d.sense_id=s.sense_id"):
        meanings_by_word.setdefault(wid, set()).add(lexical_key(definition))
    known_synonym_pairs = {
        frozenset((source, target)) for source, target in db.execute(
            "SELECT source_word_id,target_word_id FROM word_relations WHERE relation_type='synonym'"
        )
    }
    word_count = 0
    entries = [(wid, rank, tier) for wid, (rank, tier) in ranked.items() if rank is not None]
    entries.sort(key=lambda item: (item[1], item[0]))
    for wid, rank, tier in entries:
        candidates = []
        for other, other_rank, other_tier in entries:
            if other == wid or abs(other_rank - rank) > gap:
                continue
            if synsets_by_word.get(wid, set()) & synsets_by_word.get(other, set()):
                continue
            if frozenset((wid, other)) in known_synonym_pairs:
                continue
            if meanings_by_word.get(wid, set()) & meanings_by_word.get(other, set()):
                continue
            common_pos = pos_by_word.get(wid, set()) & pos_by_word.get(other, set())
            if not common_pos or other_tier != tier:
                continue
            candidates.append((abs(other_rank - rank), other_rank, other, sorted(common_pos)[0]))
        candidates.sort()
        for order, (distance, _, other, pos) in enumerate(candidates[:max_count], 1):
            confidence = round(max(0.35, 0.85 - distance / max(gap * 2, 1)), 3)
            db.execute("INSERT OR IGNORE INTO quiz_distractors VALUES(?,?,?,?,?,?)", (wid, other, pos, order, confidence, "same NGSL tier and part of speech; nearby frequency rank"))
            word_count += 1
    return word_count
