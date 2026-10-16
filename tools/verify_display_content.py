"""Audit card meaning/example pairing against the bundled Core and Full data."""
import gzip
import json
import re
import shutil
import sqlite3
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.stdout.reconfigure(encoding='utf-8')
JAVA = (ROOT / 'app/src/main/java/com/lafz/app/LafzRepository.java').read_text(encoding='utf-8')


def java_sql(method):
    body = JAVA.split('private static String ' + method + '() {', 1)[1].split('\n    }', 1)[0]
    return ''.join(re.findall(r'"([^"\n]*)"', body))


def main():
    assets = ROOT / 'app/src/main/assets/lexicon'
    manifest = json.loads((assets / 'manifest.json').read_text())
    work = ROOT / 'build/content-audit'
    work.mkdir(parents=True, exist_ok=True)
    select = java_sql('buildWordSelect')
    source = java_sql('buildWordFrom')
    fixture = sqlite3.connect(':memory:')
    fixture.executescript((ROOT / 'tools/lafz_lexicon/schema.sql').read_text())
    for source_id in ('source_a', 'source_b'):
        fixture.execute('INSERT INTO data_sources VALUES(?,?,?,?,?,?,?,?,?,?)',
                        (source_id, source_id, 'publisher', '1', 'url', 'license', 'url', 'credit', 'allowed', None))
    fixture.execute("INSERT INTO words(word_id,spelling,lookup_key) VALUES('fixture','fixture','fixture')")
    for sense_id, pos, order in [('first', 'noun', 1), ('second', 'verb', 2)]:
        fixture.execute('INSERT INTO senses(sense_id,word_id,source_id,part_of_speech,sense_order) VALUES(?,?,?,?,?)',
                        (sense_id, 'fixture', 'source_a', pos, order))
        fixture.execute('INSERT INTO definitions(definition_id,sense_id,source_id,definition_text) VALUES(?,?,?,?)',
                        (sense_id, sense_id, 'source_a', sense_id + ' meaning'))
    fixture.execute("INSERT INTO examples(example_id,sense_id,source_id,example_text,validation_status) "
                    "VALUES('e','second','source_a','fixture is used as a verb','verified_word')")
    card = fixture.execute(select + source).fetchone()
    assert card[4] == 'noun' and card[6] == 'first meaning' and card[7] is None
    fixture.execute("INSERT INTO definitions(definition_id,sense_id,source_id,definition_text,definition_kind) "
                    "VALUES('learner','second','source_b','learner verb meaning','learner')")
    card = fixture.execute(select + source).fetchone()
    assert card[4] == 'verb' and card[6] == 'learner verb meaning' and card[7] is None and card[9] == 'source_b'
    fixture.execute("INSERT INTO examples(example_id,sense_id,source_id,example_text,validation_status) "
                    "VALUES('learner_e','second','source_b','matching learner example','verified_word')")
    assert fixture.execute(select + source).fetchone()[7] == 'matching learner example'
    fixture.execute("INSERT INTO sense_labels VALUES('second','source_b','slang','usage',1)")
    assert fixture.execute(select + source).fetchone()[6] == 'first meaning', 'slang must not displace ordinary meanings'
    fixture.close()
    print('PASS fixtures: no fallback across meanings or sources; part of speech follows the selected definition', flush=True)
    for edition, entry in manifest['editions'].items():
        database = work / (edition + '.db')
        with gzip.open(assets / entry['asset'], 'rb') as compressed, database.open('wb') as expanded:
            shutil.copyfileobj(compressed, expanded)
        db = sqlite3.connect(database)
        assert db.execute('SELECT COUNT(*) FROM words').fetchone()[0] == entry['entries']
        assert db.execute("SELECT value FROM build_metadata WHERE key='topic_revision'").fetchone()[0] == manifest['topic_revision']
        assert str(entry['database_bytes']) == str(database.stat().st_size)
        assert entry['database_sha256'] in JAVA and entry['compressed_sha256'] in JAVA
        assert f"{entry['database_bytes']:_}L" in JAVA
        config = json.loads((ROOT / 'tools/lafz_lexicon/config.json').read_text())
        categories_java = (ROOT / 'app/src/main/java/com/lafz/app/LafzWords.java').read_text()
        assert re.findall(r'"([^"]+)"', categories_java) == config['topics'] == manifest['topics']
        topic_rows = db.execute('SELECT w.word_id,c.category,primary_sense.sense_id' + source +
                               ' LEFT JOIN word_categories c ON c.word_id=w.word_id').fetchall()
        assert len(topic_rows) == entry['entries']
        for word_id, category, sense_id in topic_rows:
            assert category in config['topics'], (word_id, category)
            assert db.execute("SELECT 1 FROM sense_labels WHERE sense_id=? AND label_type='topic' AND label=?",
                              (sense_id, category)).fetchone(), (word_id, category, sense_id)
        for spelling, category in {'bank': 'Work & Money', 'apple': 'Food', 'photosynthesis': 'Science',
                                  'democracy': 'Society', 'computer': 'Technology', 'happy': 'Emotions',
                                  'football': 'Sports', 'poetry': 'Arts & Culture', 'gravity': 'Science',
                                  'reward': 'Work & Money', 'walk': 'Travel & Movement'}.items():
            row = db.execute('SELECT c.category FROM words w JOIN word_categories c USING(word_id) WHERE w.spelling=?',
                             (spelling,)).fetchone()
            if row:
                assert row[0] == category, (spelling, row[0], category)
        print(f'PASS {edition} topics: {len(topic_rows):,} cards classified using their displayed meaning; '
              'Android topics and bundle integrity constants match', flush=True)
        for spelling, expected in {'head': 'The head is the highest and most important part of the body.',
                                   'bank': 'A building where you can keep and borrow money.',
                                   'run': 'If you run, you go quickly, with both feet off the ground at one time.'}.items():
            card = db.execute(select + source + ' WHERE w.lookup_key=?', (spelling,)).fetchone()
            assert card[6] == expected, (spelling, card[6])
        print('PASS everyday meaning anchors and restricted-usage demotion', flush=True)
        member = db.execute(select + source + " WHERE w.lookup_key='member'").fetchone()
        assert member[6] == 'A member is someone belonging to a group or organization.', member[6]
        assert member[7] == 'I am a member of Simple English Wikipedia.', member[7]
        assert db.execute("SELECT COUNT(*) FROM senses s JOIN definitions d USING(sense_id) "
                          "WHERE s.word_id=? AND d.definition_text='the male sex organ'", (member[0],)).fetchone()[0] > 0
        # Audit the exact production example expression and primary definition join
        # for every word, without hydrating unrelated fields such as pronunciation.
        example_expression = select.split('primary_definition.definition_text,', 1)[1]
        example_expression = example_expression.split(',(SELECT target.spelling', 1)[0]
        rows = db.execute('SELECT w.word_id,primary_definition.sense_id,primary_definition.source_id,' +
                          example_expression + source)
        audited = with_examples = missing_examples = 0
        for word_id, sense_id, source_id, example in rows:
            audited += 1
            if example:
                assert db.execute('SELECT 1 FROM examples WHERE sense_id=? AND source_id=? AND example_text=?',
                                  (sense_id, source_id, example)).fetchone(), word_id
                with_examples += 1
            else:
                missing_examples += 1
        assert audited == entry['entries']
        # Exercise complete production cards for all Core entries and a Full sample.
        sample = '' if edition == 'core' else ' WHERE w.word_id IN (SELECT word_id FROM words ORDER BY RANDOM() LIMIT 500)'
        cards = db.execute(select + source + sample).fetchall()
        assert all(card[4] and card[6] and card[9] for card in cards)
        print(f'PASS {edition}: {audited:,} entries audited; {with_examples:,} matched examples; '
              f'{missing_examples:,} omit an unavailable same-source example; {len(cards):,} complete cards checked', flush=True)
        print('member:', member[6], '|', member[7], flush=True)
        db.close()


if __name__ == '__main__':
    main()
