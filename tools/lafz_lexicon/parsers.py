from __future__ import annotations

import csv
import json
import re
import xml.etree.ElementTree as ET
from pathlib import Path
from typing import Iterator

from .archive_io import open_binary_auto, open_text_auto
from .normalize import clean_text, normalize_pos, valid_lemma


def parse_oewn_json_records(path: Path) -> Iterator[dict]:
    """Parse a captured OEWN lemma API JSON array (used for small fixtures)."""
    with path.open("r", encoding="utf-8") as handle:
        payload = json.load(handle)
    if not isinstance(payload, list):
        raise ValueError(f"Expected an OEWN synset array in {path}")
    yield from payload


def _tag(element: ET.Element) -> str:
    return element.tag.rsplit("}", 1)[-1]


def _text_list(parent: ET.Element, child_name: str) -> list[str]:
    result = []
    for child in parent.iter():
        if _tag(child) == child_name:
            value = clean_text("".join(child.itertext()))
            if value:
                result.append(value)
    return result


def parse_oewn_xml(path: Path) -> Iterator[dict]:
    """Stream WN-LMF XML into the same record shape as the OEWN lemma API.

    LexicalEntry/Sense identifiers are resolved in a first pass; the XML file is
    parsed twice so no complete XML tree has to remain in memory.
    """
    members_by_synset: dict[str, list[dict]] = {}
    with open_binary_auto(path, ".xml") as stream:
        for _, element in ET.iterparse(stream, events=("end",)):
            if _tag(element) != "LexicalEntry":
                continue
            lemma = ""
            lemma_pos = normalize_pos(element.attrib.get("partOfSpeech"))
            forms: set[str] = set()
            for child in element.iter():
                tag = _tag(child)
                if tag == "Lemma":
                    lemma = clean_text(child.attrib.get("writtenForm"))
                    lemma_pos = normalize_pos(child.attrib.get("partOfSpeech")) or lemma_pos
                elif tag in {"FormRepresentation", "Form"}:
                    form = clean_text(child.attrib.get("writtenForm"))
                    if form:
                        forms.add(form)
                elif tag == "Sense":
                    sense_id = child.attrib.get("id", "")
                    synset_id = child.attrib.get("synset", "")
                    pos = normalize_pos(child.attrib.get("partOfSpeech")) or lemma_pos
                    if lemma and valid_lemma(lemma) and sense_id and synset_id:
                        member = {"lemma": lemma, "sense": {"id": sense_id}, "poskey": pos or "other"}
                        if forms:
                            member["form"] = sorted(forms, key=str.casefold)
                        members_by_synset.setdefault(synset_id, []).append(member)
            element.clear()

    with open_binary_auto(path, ".xml") as stream:
        for _, element in ET.iterparse(stream, events=("end",)):
            if _tag(element) != "Synset":
                continue
            synset_id = element.attrib.get("id", "")
            pos = normalize_pos(element.attrib.get("partOfSpeech"))
            if not synset_id or not pos:
                element.clear()
                continue
            definitions = _text_list(element, "Definition")
            examples = _text_list(element, "Example")
            relations: dict[str, list[str]] = {}
            for child in element.iter():
                tag = _tag(child)
                target = child.attrib.get("target", "")
                if target and tag.lower().endswith("relation"):
                    relation = child.attrib.get("relType", tag.removesuffix("Relation"))
                    relations.setdefault(relation, []).append(target)
            record = {
                "id": synset_id,
                "lexname": element.attrib.get("lexfile", ""),
                "members": members_by_synset.get(synset_id, []),
                "definition": definitions,
                "example": examples,
                "partOfSpeech": pos,
                "relations": relations,
                "ili": element.attrib.get("ili", ""),
            }
            yield record
            element.clear()


def parse_cmudict(path: Path) -> Iterator[tuple[str, str, int]]:
    """Yield word, ARPABET transcription, and pronunciation variant number."""
    phone = re.compile(r"^[A-Z]+[0-2]?$ ".strip())
    with open_text_auto(path, encoding="utf-8", errors="strict", expected_suffix=".txt") as handle:
        for line_no, raw in enumerate(handle, 1):
            line = raw.split("#", 1)[0].strip()
            if not line or line.startswith(";") or line.startswith("#"):
                continue
            try:
                word, pronunciation = line.split(maxsplit=1)
            except ValueError:
                raise ValueError(f"Malformed CMUdict line {line_no}") from None
            variant = 1
            match = re.fullmatch(r"(.+?)\((\d+)\)", word)
            if match:
                word, variant = match.group(1), int(match.group(2))
            phones = pronunciation.split()
            if not phones or any(not phone.fullmatch(value) for value in phones):
                raise ValueError(f"Invalid ARPABET symbols on CMUdict line {line_no}")
            yield word.replace("_", " "), " ".join(phones), variant


def parse_ngsl_words(path: Path) -> Iterator[tuple[str, int | None]]:
    """Read an official NGSL list or ranked TSV/CSV without inventing ranks.

    Alphabetized NGSL releases provide membership only; only explicit numeric
    rank columns or explicit rank-order lists become rank values.
    """
    with open_text_auto(path, encoding="utf-8-sig", errors="strict", expected_suffix=".csv") as handle:
        lines = handle.read().splitlines()
    if path.suffix.lower() == ".csv" or (lines and "," in lines[0]):
        rows = list(csv.reader(lines))
        header = [re.sub(r"[^a-z]", "", col.casefold()) for col in rows[0]] if rows else []
        word_index = next((i for i, name in enumerate(header) if name in {"word", "lemma", "headword"}), None)
        rank_index = next((i for i, name in enumerate(header) if "rank" in name and ("sfi" in name or "frequency" in name or name == "rank")), None)
        if word_index is not None:
            for row in rows[1:]:
                if word_index >= len(row):
                    continue
                word = clean_text(row[word_index])
                if not valid_lemma(word):
                    continue
                rank = None
                if rank_index is not None and rank_index < len(row):
                    raw_rank = row[rank_index].strip()
                    rank = int(raw_rank) if raw_rank.isdigit() else None
                yield word, rank
            return
    candidates = []
    # The official alphabetized description file starts with a version note and
    # change log. Its word list starts at the distinctive `a / abandon` pair.
    start = 0
    for index in range(len(lines) - 1):
        if lines[index].strip().casefold() == "a" and lines[index + 1].strip().casefold() == "abandon":
            start = index
            break
    for raw in lines[start:]:
        line = raw.strip()
        if not line or "\t" in line and line.casefold().startswith("word\t"):
            continue
        fields = re.split(r"\t|,|;", line)
        word = clean_text(fields[0])
        if not valid_lemma(word):
            continue
        rank = None
        for field in fields[1:]:
            if field.strip().isdigit():
                rank = int(field.strip())
                break
        candidates.append((word, rank))
    # Alphabetical lists stay membership-only; ordered plain lists have ordinal
    # rank only if the file has no alphabetical ordering and no header.
    words = [item[0].casefold() for item in candidates]
    alphabetical = words == sorted(words)
    explicit = any(rank is not None for _, rank in candidates)
    for index, (word, rank) in enumerate(candidates, 1):
        yield word, rank if rank is not None else (None if alphabetical or explicit else index)


def parse_kaikki_jsonl(path: Path) -> Iterator[dict]:
    with open_text_auto(path, encoding="utf-8", errors="replace", expected_suffix=".jsonl") as handle:
        for line_no, line in enumerate(handle, 1):
            if not line.strip():
                continue
            try:
                row = json.loads(line)
            except json.JSONDecodeError as error:
                raise ValueError(f"Invalid Kaikki JSON on line {line_no}: {error}") from error
            if row.get("lang_code") != "en":
                continue
            word = row.get("word", "")
            pos = normalize_pos(row.get("pos"))
            if not valid_lemma(word) or not pos:
                continue
            yield row


def parse_tatoeba_sentences(path: Path) -> Iterator[tuple[str, str]]:
    """Read a Tatoeba sentence export (tab-separated id/lang/text[/metadata])."""
    with open_text_auto(path, encoding="utf-8", errors="replace", expected_suffix=".tsv") as handle:
        for line_no, raw in enumerate(handle, 1):
            line = raw.rstrip("\r\n")
            if not line:
                continue
            fields = line.split("\t")
            if line_no == 1 and fields[0].strip().casefold() in {"id", "sentence_id"}:
                continue
            if len(fields) >= 3 and fields[1].strip().casefold() in {"en", "eng", "english"}:
                sentence_id, text = fields[0].strip(), "\t".join(fields[2:-1] if len(fields) >= 4 else fields[2:])
            elif len(fields) == 2:
                # A single-language English CC0 export may omit its language column.
                sentence_id, text = fields[0].strip(), fields[1].strip()
            else:
                continue
            text = clean_text(text)
            if sentence_id and text:
                yield sentence_id, text


# Backward-compatible parser name for callers that already use the CC0-only
# local export path. The importer records the actual source license separately.
parse_tatoeba_cc0 = parse_tatoeba_sentences
