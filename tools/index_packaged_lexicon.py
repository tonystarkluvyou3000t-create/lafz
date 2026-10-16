"""Add the launch lookup index to existing verified bundled dictionaries."""
import gzip
import hashlib
import json
import shutil
import sqlite3
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
ASSETS = ROOT / 'app/src/main/assets/lexicon'
REPOSITORY = ROOT / 'app/src/main/java/com/lafz/app/LafzRepository.java'


def digest(path):
    with path.open('rb') as source:
        return hashlib.file_digest(source, 'sha256').hexdigest()


def main():
    manifest_path = ASSETS / 'manifest.json'
    manifest = json.loads(manifest_path.read_text())
    java = REPOSITORY.read_text(encoding='utf-8')
    work = ROOT / 'build/indexed-lexicon'
    work.mkdir(parents=True, exist_ok=True)
    for name, entry in manifest['editions'].items():
        asset = ASSETS / entry['asset']
        assert digest(asset) == entry['compressed_sha256']
        database = work / (name + '.db')
        with gzip.open(asset, 'rb') as source, database.open('wb') as target:
            shutil.copyfileobj(source, target)
        assert database.stat().st_size == entry['database_bytes']
        assert digest(database) == entry['database_sha256']
        with sqlite3.connect(database) as db:
            db.execute('CREATE INDEX IF NOT EXISTS idx_pronunciations_word ON pronunciations(word_id, variant_order)')
            assert db.execute('PRAGMA quick_check').fetchone()[0] == 'ok'
            assert db.execute('SELECT COUNT(*) FROM words').fetchone()[0] == entry['entries']
            plan = db.execute('EXPLAIN QUERY PLAN SELECT transcription FROM pronunciations WHERE word_id=?', ('test',)).fetchall()
            assert any('idx_pronunciations_word' in row[3] for row in plan)
        temporary = asset.with_suffix('.part')
        with database.open('rb') as source, temporary.open('wb') as raw:
            with gzip.GzipFile(filename='', mode='wb', fileobj=raw, compresslevel=9, mtime=0) as target:
                shutil.copyfileobj(source, target)
        updated = dict(entry, database_bytes=database.stat().st_size,
                       database_sha256=digest(database), compressed_bytes=temporary.stat().st_size,
                       compressed_sha256=digest(temporary))
        java = java.replace(f"{entry['database_bytes']:_}L", f"{updated['database_bytes']:_}L")
        java = java.replace(entry['database_sha256'], updated['database_sha256'])
        java = java.replace(entry['compressed_sha256'], updated['compressed_sha256'])
        temporary.replace(asset)
        manifest['editions'][name] = updated
        print(name, updated['database_bytes'], 'bytes; indexed and integrity verified', flush=True)
    REPOSITORY.write_text(java, encoding='utf-8')
    manifest_path.write_text(json.dumps(manifest, indent=2) + '\n')


if __name__ == '__main__':
    main()
