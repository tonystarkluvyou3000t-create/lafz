from __future__ import annotations

import json
import sqlite3
import tempfile
import unittest
import bz2
import gzip
import zipfile
from contextlib import closing
from pathlib import Path
from unittest.mock import patch

from lafz_lexicon.archive_io import detect_compression, open_text_auto
from lafz_lexicon.acquire import PACKAGE_DIR, find_local_source, inventory_local_archives
from lafz_lexicon.cli import main
from lafz_lexicon.database import build_database, validate_database
from lafz_lexicon.core import build_core_database
from lafz_lexicon.ingest import add_tatoeba_sentences, classify_definition_topic, classify_lexname
from lafz_lexicon.normalize import sentence_contains
from lafz_lexicon.parsers import parse_cmudict, parse_ngsl_words, parse_oewn_json_records, parse_oewn_xml, parse_tatoeba_sentences
from lafz_lexicon.quality import align_definition, example_quality, valid_arpabet
from lafz_lexicon.reporting import REVIEW_GROUPS
from lafz_lexicon.topics import refresh_topics


class NormalizationTests(unittest.TestCase):
    def test_surface_and_inflection_validation(self):
        self.assertTrue(sentence_contains("portent", "The portents were unsettling."))
        self.assertFalse(sentence_contains("portent", "A sign of trouble appeared."))
        self.assertTrue(sentence_contains("banking company", "She works at a banking company."))
        self.assertFalse(sentence_contains("banking company", "She works for a bank."))
        self.assertEqual(example_quality("The portent arrived.", "portent")[0], "verified_word")
        self.assertIsNone(example_quality("A sign of trouble arrived.", "portent"))

    def test_alignment_is_conservative(self):
        index, confidence, reason = align_definition("a small domestic cat", ["public transportation system"])
        self.assertIsNone(index)
        self.assertLess(confidence, 0.60)
        self.assertIn("threshold", reason)

    def test_alignment_accepts_unique_content_paraphrase_but_keeps_ties(self):
        index, confidence, reason = align_definition("to throw eggs at something", ["throw eggs at", "make a joke"])
        self.assertEqual(index, 0)
        self.assertGreaterEqual(confidence, 0.60)
        self.assertEqual(reason, "definition_content_f1")
        index, _confidence, reason = align_definition(
            "a person who throws eggs", ["someone who throws eggs", "an individual throwing eggs"]
        )
        self.assertIsNone(index)
        self.assertEqual(reason, "ambiguous_tie")

    def test_reward_is_not_forced_into_nature(self):
        config = json.loads((PACKAGE_DIR / "config.json").read_text(encoding="utf-8"))
        self.assertIsNone(classify_lexname("reward", "noun.plant", config))
        self.assertEqual(classify_lexname("apple", "noun.plant", config)[0], "Nature")
        self.assertEqual(classify_lexname("apple", "noun.food", config)[0], "Food")

    def test_broad_phenomenon_topic_requires_physical_definition_evidence(self):
        config = json.loads((PACKAGE_DIR / "config.json").read_text(encoding="utf-8"))
        self.assertEqual(classify_lexname("serendipity", "noun.phenomenon", config)[0], "Ideas & Concepts")
        self.assertEqual(
            classify_definition_topic("noun.phenomenon", "the force of attraction between masses", config),
            ("Science", 0.86),
        )
        self.assertIsNone(classify_definition_topic(
            "noun.phenomenon", "good luck in making unexpected and fortunate discoveries", config,
        ))

    def test_arpabet_symbols_and_stress(self):
        self.assertTrue(valid_arpabet("B AE1 NG K"))
        self.assertTrue(valid_arpabet("AH0 P AH0 L"))
        self.assertFalse(valid_arpabet("/ˈæp.əl/"))


class ReportingTests(unittest.TestCase):
    def test_manual_review_notes_cover_all_curated_terms(self):
        payload = json.loads((PACKAGE_DIR / "manual_review_notes.json").read_text(encoding="utf-8"))
        notes = payload.get("notes", {})
        terms = {term for group in REVIEW_GROUPS.values() for term in group}
        self.assertEqual(len(terms), 30)
        self.assertEqual(set(notes), terms)
        self.assertTrue(all(isinstance(note, str) and note.strip() for note in notes.values()))


class ParserTests(unittest.TestCase):
    def test_cmudict_variants_keep_stress(self):
        test_root = PACKAGE_DIR / "build" / "test-temp"
        test_root.mkdir(parents=True, exist_ok=True)
        with tempfile.TemporaryDirectory(dir=test_root) as temp:
            path = Path(temp) / "cmudict.dict"
            path.write_text(
                "BANK B AE1 NG K\nBANK(2) B AE0 NG K\nAALBORG AO1 L B AO0 R G # place, danish\n",
                encoding="utf-8",
            )
            self.assertEqual(list(parse_cmudict(path)), [
                ("BANK", "B AE1 NG K", 1), ("BANK", "B AE0 NG K", 2),
                ("AALBORG", "AO1 L B AO0 R G", 1),
            ])

    def test_oewn_capture_has_polysemy_and_pos(self):
        rows = list(parse_oewn_json_records(PACKAGE_DIR / "fixtures" / "oewn_sample.json"))
        bank_rows = [row for row in rows if any(member["lemma"] == "bank" for member in row["members"])]
        self.assertGreaterEqual(len(bank_rows), 4)
        self.assertEqual({row["partOfSpeech"] for row in bank_rows}, {"n", "v"})

    def test_oewn_lmf_xml_resolves_lemma_pos_and_example(self):
        test_root = PACKAGE_DIR / "build" / "test-temp"
        test_root.mkdir(parents=True, exist_ok=True)
        with tempfile.TemporaryDirectory(dir=test_root) as temp:
            path = Path(temp) / "tiny.xml"
            path.write_text(
                '<LexicalResource><Lexicon>'
                '<LexicalEntry id="word-n"><Lemma writtenForm="word" partOfSpeech="n"/>'
                '<Sense id="word-n-1" synset="00000001-n"/></LexicalEntry>'
                '<Synset id="00000001-n" partOfSpeech="n" lexfile="noun.act">'
                '<Definition>a small XML parser fixture</Definition><Example>the word appears</Example>'
                '</Synset></Lexicon></LexicalResource>', encoding="utf-8")
            records = list(parse_oewn_xml(path))
            self.assertEqual(records[0]["members"][0]["poskey"], "noun")
            self.assertEqual(records[0]["definition"], ["a small XML parser fixture"])
            self.assertEqual(records[0]["example"], ["the word appears"])

    def test_ngsl_ranked_csv_keeps_rank_and_unranked_list_stays_unranked(self):
        test_root = PACKAGE_DIR / "build" / "test-temp"
        test_root.mkdir(parents=True, exist_ok=True)
        with tempfile.TemporaryDirectory(dir=test_root) as temp:
            path = Path(temp) / "ngsl.csv"
            path.write_text("word,SFI Rank,frequency\napple,1,50\nbank,12,5\n", encoding="utf-8")
            self.assertEqual(list(parse_ngsl_words(path)), [("apple", 1), ("bank", 12)])
            alpha = Path(temp) / "alphabetized.txt"
            alpha.write_text("a\nabandon\nability\n", encoding="utf-8")
            self.assertEqual(list(parse_ngsl_words(alpha)), [("a", None), ("abandon", None), ("ability", None)])

    def test_tatoeba_bzip2_export_is_stream_readable(self):
        test_root = PACKAGE_DIR / "build" / "test-temp"
        test_root.mkdir(parents=True, exist_ok=True)
        with tempfile.TemporaryDirectory(dir=test_root) as temp:
            path = Path(temp) / "eng_sentences.tsv.bz2"
            with bz2.open(path, "wt", encoding="utf-8") as handle:
                handle.write("1276\teng\tLet's try something.\n")
            self.assertEqual(list(parse_tatoeba_sentences(path)), [("1276", "Let's try something.")])


class ArchiveTests(unittest.TestCase):
    def test_detects_gzip_bzip2_and_zip_by_signature(self):
        test_root = PACKAGE_DIR / "build" / "test-temp"
        test_root.mkdir(parents=True, exist_ok=True)
        with tempfile.TemporaryDirectory(dir=test_root) as temp:
            root = Path(temp)
            gzip_path = root / "input.gz"
            with gzip.open(gzip_path, "wt", encoding="utf-8") as handle:
                handle.write("gzip text\n")
            bzip_path = root / "input.bz2"
            with bz2.open(bzip_path, "wt", encoding="utf-8") as handle:
                handle.write("bzip text\n")
            zip_path = root / "input.zip"
            with zipfile.ZipFile(zip_path, "w") as archive:
                archive.writestr("folder/sentences.tsv", "1\teng\tzip text\n")
            self.assertEqual(detect_compression(gzip_path), "gzip")
            self.assertEqual(detect_compression(bzip_path), "bzip2")
            self.assertEqual(detect_compression(zip_path), "zip")
            with open_text_auto(zip_path, expected_suffix=".tsv") as handle:
                self.assertEqual(handle.readline(), "1\teng\tzip text\n")

    def test_offline_missing_core_never_calls_downloader(self):
        test_root = PACKAGE_DIR / "build" / "test-temp"
        test_root.mkdir(parents=True, exist_ok=True)
        with tempfile.TemporaryDirectory(dir=test_root) as temp:
            with patch("lafz_lexicon.cli.download_source", side_effect=AssertionError("network path called")) as download:
                result = main(["build", "--offline", "--source-dir", temp, "--output", str(Path(temp) / "out.db")])
            self.assertEqual(result, 2)
            download.assert_not_called()

    def test_local_inventory_records_hash_and_source_classification(self):
        test_root = PACKAGE_DIR / "build" / "test-temp"
        test_root.mkdir(parents=True, exist_ok=True)
        with tempfile.TemporaryDirectory(dir=test_root) as temp:
            source_dir = Path(temp)
            archive = source_dir / "english-wordnet-2025.xml.gz"
            with gzip.open(archive, "wb") as handle:
                handle.write(b"<LexicalResource/>")
            inventory = inventory_local_archives(source_dir)
            self.assertEqual(inventory[0]["source_id"], "oewn_2025")
            self.assertEqual(inventory[0]["compression"], "gzip")
            self.assertEqual(len(inventory[0]["sha256"]), 64)
            self.assertEqual(find_local_source(source_dir, "oewn_2025"), archive)


class DatabaseTests(unittest.TestCase):
    def test_topics_follow_displayed_sense_and_keep_polysemy(self):
        test_root = PACKAGE_DIR / "build" / "test-temp"
        test_root.mkdir(parents=True, exist_ok=True)
        config = json.loads((PACKAGE_DIR / "config.json").read_text())
        with tempfile.TemporaryDirectory(dir=test_root) as temp:
            path = Path(temp) / "topics.db"
            build_database(path, PACKAGE_DIR / "fixtures/oewn_sample.json", sample=True)
            with closing(sqlite3.connect(path)) as db:
                # Apple has both food and tree meanings. Their disagreement
                # must not discard its useful primary food topic.
                apple = db.execute("SELECT word_id FROM words WHERE lookup_key='apple'").fetchone()[0]
                self.assertEqual(db.execute("SELECT category FROM word_categories WHERE word_id=?", (apple,)).fetchone()[0], "Food")
                labels = {row[0] for row in db.execute("SELECT label FROM sense_labels l JOIN senses s USING(sense_id) WHERE s.word_id=? AND label_type='topic'", (apple,))}
                self.assertEqual(labels, {"Food", "Nature"})
                bank = db.execute("SELECT word_id FROM words WHERE lookup_key='bank'").fetchone()[0]
                before = db.execute('SELECT * FROM senses ORDER BY sense_id').fetchall()
                db.execute("INSERT INTO senses(sense_id,word_id,source_id,part_of_speech,sense_order) VALUES('learner_bank',?,'oewn_2025','noun',99)", (bank,))
                db.execute("INSERT INTO definitions(definition_id,sense_id,source_id,definition_text,definition_kind) VALUES('learner_bank','learner_bank','oewn_2025','A building where you can keep and borrow money.','learner')")
                refresh_topics(db, config)
                self.assertEqual(db.execute("SELECT category FROM word_categories WHERE word_id=?", (bank,)).fetchone()[0], "Work & Money")
                self.assertIsNone(db.execute("SELECT synset_id FROM senses WHERE sense_id='learner_bank'").fetchone()[0])
                self.assertEqual(db.execute("SELECT * FROM senses WHERE sense_id<>'learner_bank' ORDER BY sense_id").fetchall(), before)
                first = db.execute('SELECT * FROM word_categories ORDER BY word_id').fetchall()
                refresh_topics(db, config)
                self.assertEqual(db.execute('SELECT * FROM word_categories ORDER BY word_id').fetchall(), first)
                self.assertEqual(db.execute('SELECT COUNT(*) FROM words').fetchone()[0], db.execute('SELECT COUNT(DISTINCT word_id) FROM word_categories').fetchone()[0])

    def test_all_source_domains_and_parts_of_speech_have_broad_topics(self):
        config = json.loads((PACKAGE_DIR / "config.json").read_text())
        lexnames = ('noun.Tops noun.act noun.animal noun.artifact noun.attribute noun.body noun.cognition '
                    'noun.communication noun.event noun.feeling noun.food noun.group noun.location noun.motive '
                    'noun.object noun.person noun.phenomenon noun.plant noun.possession noun.process noun.quantity '
                    'noun.relation noun.shape noun.state noun.substance noun.time verb.body verb.change '
                    'verb.cognition verb.communication verb.competition verb.consumption verb.contact '
                    'verb.creation verb.emotion verb.motion verb.perception verb.possession verb.social '
                    'verb.stative verb.weather adj.all adj.pert adj.ppl adv.all').split()
        for lexname in lexnames:
            self.assertIn(classify_lexname('fixture', lexname, config)[0], config['topics'])
        self.assertEqual(set(config['topic_pos_fallbacks']), {'noun', 'verb', 'adjective', 'adverb', 'other'})

    def test_sample_build_and_validation(self):
        test_root = PACKAGE_DIR / "build" / "test-temp"
        test_root.mkdir(parents=True, exist_ok=True)
        with tempfile.TemporaryDirectory(dir=test_root) as temp:
            output = Path(temp) / "lafz_dictionary.db"
            fixture = PACKAGE_DIR / "fixtures" / "oewn_sample.json"
            result = build_database(output, fixture, sample=True)
            self.assertEqual(result["words"], 6)
            self.assertEqual(result["filtered_members"], 1)
            self.assertEqual(validate_database(output), [])
            db = sqlite3.connect(output)
            try:
                bank_pos = {row[0] for row in db.execute(
                    "SELECT DISTINCT s.part_of_speech FROM senses s JOIN words w ON w.word_id=s.word_id WHERE w.lookup_key='bank'"
                )}
                self.assertEqual(bank_pos, {"noun", "verb"})
                self.assertEqual(db.execute("SELECT COUNT(*) FROM words WHERE lookup_key='malus pumila'").fetchone()[0], 0)
                self.assertEqual(db.execute("SELECT COUNT(*) FROM semantic_relations").fetchone()[0], 0)
                self.assertIsNone(db.execute("SELECT checksum_sha256 FROM data_sources WHERE source_id='oewn_2025'").fetchone()[0])
                invalid_examples = db.execute("SELECT COUNT(*) FROM examples WHERE validation_status='unverified'").fetchone()[0]
                self.assertEqual(invalid_examples, 0)
            finally:
                db.close()

    def test_tatoeba_examples_are_capped_and_sense_unverified(self):
        test_root = PACKAGE_DIR / "build" / "test-temp"
        test_root.mkdir(parents=True, exist_ok=True)
        with tempfile.TemporaryDirectory(dir=test_root) as temp:
            temp_path = Path(temp)
            output = temp_path / "sample.db"
            build_database(output, PACKAGE_DIR / "fixtures" / "oewn_sample.json", sample=True)
            sentences = temp_path / "eng_sentences.tsv.bz2"
            with bz2.open(sentences, "wt", encoding="utf-8") as handle:
                handle.write("100\teng\tI found an apple.\n")
                handle.write("101\teng\tThe apple was red.\n")
                handle.write("102\teng\tWe bought an apple.\n")
            db = sqlite3.connect(output)
            try:
                counts = add_tatoeba_sentences(db, sentences, max_examples_per_word=2)
                db.commit()
                apple_id = db.execute("SELECT word_id FROM words WHERE lookup_key='apple'").fetchone()[0]
                self.assertEqual(db.execute(
                    "SELECT COUNT(*) FROM word_examples WHERE word_id=? AND source_id='tatoeba_english'", (apple_id,)
                ).fetchone()[0], 2)
                self.assertEqual(db.execute(
                    "SELECT COUNT(*) FROM examples WHERE source_id='tatoeba_english'"
                ).fetchone()[0], 0)
                self.assertEqual(db.execute(
                    "SELECT COUNT(*) FROM word_examples WHERE source_id='tatoeba_english' AND validation_status='word_presence_only' AND sense_match_status='unverified'"
                ).fetchone()[0], 2)
                self.assertEqual(db.execute(
                    "SELECT COUNT(*) FROM source_attributions WHERE source_id='tatoeba_english' AND record_type='word_example' AND detail LIKE 'sentence_id=%'"
                ).fetchone()[0], 2)
                self.assertEqual(counts["examples_kept"], 2)
            finally:
                db.close()

    def test_core_build_keeps_all_senses_for_ranked_lemma(self):
        test_root = PACKAGE_DIR / "build" / "test-temp"
        test_root.mkdir(parents=True, exist_ok=True)
        with tempfile.TemporaryDirectory(dir=test_root) as temp:
            temp_path = Path(temp)
            ngsl = temp_path / "ngsl.csv"
            ngsl.write_text("word,SFI Rank\nbank,1\n", encoding="utf-8")
            full = temp_path / "full.db"
            core = temp_path / "lafz_core.db"
            build_database(full, PACKAGE_DIR / "fixtures" / "oewn_sample.json", ngsl_path=ngsl, sample=True)
            result = build_core_database(full, core)
            self.assertEqual(result["core_words"], 1)
            self.assertTrue(result["core_compressed_under_30mb"])
            self.assertEqual(validate_database(core), [])
            db = sqlite3.connect(core)
            try:
                self.assertEqual(db.execute("SELECT COUNT(*) FROM senses").fetchone()[0], 4)
                self.assertEqual(db.execute("SELECT value FROM build_metadata WHERE key='build_profile'").fetchone()[0], "core")
            finally:
                db.close()


if __name__ == "__main__":
    unittest.main()
