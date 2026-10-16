from pathlib import Path
import re
import sqlite3

source = Path('app/src/main/java/com/lafz/app/LearningContent.java').read_text()
with sqlite3.connect('design-preview/improvements/catalog.db') as db:
    for level in ['EVERYDAY', 'STRETCH']:
        block = source.split(f'{level}_WORDS = words(', 1)[1].split(');', 1)[0]
        words = ' '.join(re.findall(r'"([^"]*)"', block)).split()
        rows = db.execute('select id,meaning from words where lower(spelling) in (' + ','.join('?' for _ in words) + ') order by spelling,part', words).fetchall()
        print(level, len(rows))
        for word, meaning in rows:
            print(word + ': ' + meaning)
