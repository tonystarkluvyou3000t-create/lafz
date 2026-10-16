from pathlib import Path
import sqlite3
import xml.etree.ElementTree as ET
import json

base = Path('design-preview/improvements')
with sqlite3.connect(base / 'catalog.db') as db:
    assert db.execute('pragma integrity_check').fetchone()[0] == 'ok'
    total, awaiting, seen, hidden = db.execute('select count(*),sum(length(raw_gloss)=0),sum(seen),sum(hidden) from words').fetchone()
    assert awaiting == 0, f'{awaiting} rows still await source refresh'
    prefs = ET.parse(base / 'prefs-before-tests.xml').getroot()
    saved = [s.text for s in prefs.find("set[@name='saved_words']")]
    for word in saved:
        assert db.execute('select 1 from words where id=?', (word,)).fetchone(), word
    for word in prefs.find("set[@name='hidden_words']"):
        assert db.execute('select hidden from words where id=?', (word.text,)).fetchone()[0] == 1
    print(f'Catalog integrity passed: {total} entries refreshed, {len(saved)} saved IDs preserved, {hidden} hidden entries preserved, {seen} seen entries.')
    rows = db.execute("select spelling,part,category,meaning from words where lower(spelling) in ('balance','fair','steady','patient','curious','purpose','resilient','garden','shelter','gravity') order by spelling,part").fetchall()
    print(json.dumps(rows, indent=2))
