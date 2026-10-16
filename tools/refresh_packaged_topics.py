"""Refresh bundled browsing topics without touching lexical content or IDs.

Run from the repository root: python tools/refresh_packaged_topics.py
Core copies the Full labels so truncated Core domain graphs cannot change them.
"""
import gzip
import hashlib
import json
import shutil
import sqlite3
from contextlib import closing
from pathlib import Path

from lafz_lexicon.topics import refresh_topics, TOPIC_POLICY, TOPIC_REVISION

ROOT = Path(__file__).resolve().parents[1]
ASSETS = ROOT / 'app/src/main/assets/lexicon'
REPOSITORY = ROOT / 'app/src/main/java/com/lafz/app/LafzRepository.java'


def digest(path):
    with path.open('rb') as source:
        return hashlib.file_digest(source, 'sha256').hexdigest()


def lexical_fingerprint(db):
    result = {}
    for (name,) in db.execute("SELECT name FROM sqlite_master WHERE type='table' ORDER BY name"):
        if name in ('build_metadata', 'word_categories'):
            continue
        fingerprint = hashlib.sha256()
        predicate = " WHERE label_type<>'topic'" if name == 'sense_labels' else ''
        columns = len(db.execute('PRAGMA table_info(' + name + ')').fetchall())
        for row in db.execute('SELECT * FROM ' + name + predicate + ' ORDER BY ' +
                              ','.join(str(n) for n in range(1, columns + 1))):
            fingerprint.update(json.dumps(row, ensure_ascii=False).encode('utf-8'))
        result[name] = fingerprint.hexdigest()
    return result


def main():
    manifest_path = ASSETS / 'manifest.json'
    manifest = json.loads(manifest_path.read_text())
    config = json.loads((ROOT / 'tools/lafz_lexicon/config.json').read_text())
    work = ROOT / 'build/topic-refresh'
    work.mkdir(parents=True, exist_ok=True)
    java = REPOSITORY.read_text(encoding='utf-8')
    report = {}
    # Stage and validate every database before publishing either bundle.
    for name in ('full', 'core'):
        entry = manifest['editions'][name]
        asset = ASSETS / entry['asset']
        assert digest(asset) == entry['compressed_sha256']
        database = work / (name + '.db')
        with gzip.open(asset, 'rb') as source, database.open('wb') as target:
            shutil.copyfileobj(source, target)
        assert digest(database) == entry['database_sha256']
        with closing(sqlite3.connect(database)) as db:
            before = lexical_fingerprint(db)
            unclassified_before = db.execute('SELECT COUNT(*) FROM words w WHERE NOT EXISTS '
                                             '(SELECT 1 FROM word_categories c WHERE c.word_id=w.word_id)').fetchone()[0]
            if name == 'full':
                counters = refresh_topics(db, config)
            else:
                db.execute('ATTACH DATABASE ? AS full_topics', (str(work / 'full.db'),))
                db.execute("DELETE FROM sense_labels WHERE label_type='topic' AND source_id='oewn_2025'")
                db.execute("DELETE FROM word_categories WHERE source_id='oewn_2025'")
                db.execute("INSERT INTO sense_labels SELECT l.* FROM full_topics.sense_labels l "
                           "JOIN senses s ON s.sense_id=l.sense_id WHERE l.label_type='topic'")
                db.execute('INSERT INTO word_categories SELECT c.* FROM full_topics.word_categories c '
                           'JOIN words w ON w.word_id=c.word_id')
                db.execute("INSERT OR REPLACE INTO build_metadata VALUES('topic_revision',?)", (TOPIC_REVISION,))
                db.execute("INSERT OR REPLACE INTO build_metadata VALUES('topic_policy',?)", (TOPIC_POLICY,))
                counters = {'copied_from_full': True}
            assert before == lexical_fingerprint(db), 'Non-topic content changed'
            assert db.execute('SELECT COUNT(*) FROM words').fetchone()[0] == entry['entries']
            assert not db.execute('PRAGMA foreign_key_check').fetchall()
            assert db.execute('PRAGMA integrity_check').fetchone()[0] == 'ok'
            missing = db.execute('SELECT COUNT(*) FROM words w WHERE NOT EXISTS '
                                 '(SELECT 1 FROM word_categories c WHERE c.word_id=w.word_id)').fetchone()[0]
            assert missing == 0
            coverage = dict(db.execute('SELECT category,COUNT(*) FROM word_categories GROUP BY category'))
            assert set(coverage).issubset(config['topics'])
            report[name] = dict(entries=entry['entries'], unclassified_before=unclassified_before,
                                unclassified_after=missing, topics=coverage, counters=counters,
                                non_topic_content_unchanged=True)
            metadata = json.loads(db.execute("SELECT value FROM build_metadata WHERE key='build_counters'").fetchone()[0])
            metadata['category_assignments'] = report[name]
            db.execute("UPDATE build_metadata SET value=? WHERE key='build_counters'", (json.dumps(metadata, sort_keys=True),))
            db.commit()
            db.execute('VACUUM')
        staged = work / (name + '.dbz')
        with database.open('rb') as source, staged.open('wb') as raw:
            with gzip.GzipFile(filename='', mode='wb', fileobj=raw, compresslevel=9, mtime=0) as target:
                shutil.copyfileobj(source, target)
        updated = dict(entry, database_bytes=database.stat().st_size, database_sha256=digest(database),
                       compressed_bytes=staged.stat().st_size, compressed_sha256=digest(staged))
        java = java.replace(f"{entry['database_bytes']:_}L", f"{updated['database_bytes']:_}L")
        java = java.replace(entry['database_sha256'], updated['database_sha256'])
        java = java.replace(entry['compressed_sha256'], updated['compressed_sha256'])
        manifest['editions'][name] = updated
        print(f'{name}: {unclassified_before:,} -> {missing} unclassified; {len(coverage)} populated topics; content unchanged', flush=True)
    for name in ('core', 'full'):
        shutil.copyfile(work / (name + '.dbz'), ASSETS / manifest['editions'][name]['asset'])
    manifest['topic_revision'] = TOPIC_REVISION
    manifest['topics'] = config['topics']
    manifest_path.write_text(json.dumps(manifest, indent=2) + '\n')
    REPOSITORY.write_text(java, encoding='utf-8')
    (work / 'report.json').write_text(json.dumps(report, indent=2) + '\n')
    print('Published verified Core and Full bundles, manifest and Android integrity constants.', flush=True)


if __name__ == '__main__':
    main()
