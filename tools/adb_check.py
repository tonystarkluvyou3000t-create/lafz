"""Small ADB verification helper; all captures stay in the workspace."""
from pathlib import Path
import subprocess
import sys
import time
import re
import xml.etree.ElementTree as ET
sys.stdout.reconfigure(encoding='utf-8')

out = Path('design-preview/improvements')
out.mkdir(parents=True, exist_ok=True)

def adb(*args):
    return subprocess.check_output(['adb', *args])

def hierarchy():
    for attempt in range(5):
        try:
            result = adb('shell', 'uiautomator', 'dump', '/sdcard/lafz-check.xml')
        except subprocess.CalledProcessError:
            time.sleep(.5)
            continue
        if b'dumped to' in result:
            return adb('shell', 'cat', '/sdcard/lafz-check.xml')
        time.sleep(.5)
    raise SystemExit('ADB did not return a current UI hierarchy')

def snapshot(name):
    xml = hierarchy()
    (out / (name + '.xml')).write_bytes(xml)
    (out / (name + '.png')).write_bytes(adb('exec-out', 'screencap', '-p'))
    for node in ET.fromstring(xml).iter('node'):
        value = node.get('text') or node.get('content-desc')
        if value:
            print(value, node.get('bounds'), 'click' if node.get('clickable') == 'true' else '')

def tap(value, scroll=False):
    root = ET.fromstring(hierarchy())
    candidates = [n for n in root.iter('node') if n.get('text') == value or n.get('content-desc') == value]
    if not candidates:
        if scroll:
            adb('shell', 'input', 'swipe', '450', '1850', '450', '900', '400')
            return tap(value, scroll=False)
        raise SystemExit('No visible node: ' + value)
    clickable = [n for n in candidates if n.get('clickable') == 'true']
    if value in ['Discover', 'Saved', 'Practice', 'Settings'] and clickable:
        node = max(clickable, key=lambda n: int(re.findall(r'\d+', n.get('bounds'))[1]))
    else:
        node = clickable[0] if clickable else candidates[0]
    x1, y1, x2, y2 = map(int, re.findall(r'\d+', node.get('bounds')))
    adb('shell', 'input', 'tap', str((x1 + x2) // 2), str((y1 + y2) // 2))

if sys.argv[1] == 'snapshot':
    snapshot(sys.argv[2])
elif sys.argv[1] == 'tap':
    tap(sys.argv[2])
elif sys.argv[1] == 'backup':
    (out / 'prefs-before-tests.xml').write_bytes(adb('exec-out', 'run-as', 'com.lafz.app', 'cat', 'shared_prefs/lafz_prefs.xml'))
elif sys.argv[1] == 'database':
    adb('shell', 'am', 'force-stop', 'com.lafz.app')
    (out / 'catalog.db').write_bytes(adb('exec-out', 'run-as', 'com.lafz.app', 'cat', 'databases/lafz_wordnet.db'))
    for suffix in ['-wal', '-shm', '-journal']:
        remote = 'databases/lafz_wordnet.db' + suffix
        exists = subprocess.run(['adb', 'shell', 'run-as', 'com.lafz.app', 'test', '-f', remote], capture_output=True).returncode == 0
        local = out / ('catalog.db' + suffix)
        if exists:
            local.write_bytes(adb('exec-out', 'run-as', 'com.lafz.app', 'cat', remote))
        elif local.exists():
            local.unlink()
    adb('shell', 'am', 'start', '-n', 'com.lafz.app/.MainActivity')
elif sys.argv[1] == 'review-round':
    for index in range(int(sys.argv[2])):
        tap('Reveal meaning')
        tap('Got it' if index % 2 else 'Again tomorrow')
    snapshot(sys.argv[3])
elif sys.argv[1] == 'quiz-round':
    import sqlite3
    db = sqlite3.connect(out / 'catalog.db')
    for index in range(21):
        tree = ET.fromstring(hierarchy())
        texts = {n.get('text') for n in tree.iter('node')}
        if 'Round complete' in texts:
            snapshot('quiz-complete'); break
        choices = [n.get('text') for n in tree.iter('node') if n.get('content-desc', '').startswith('Answer ')]
        correct = []
        for choice in choices:
            for spelling, raw in db.execute('select spelling,raw_gloss from words where lower(spelling)=lower(?)', (choice,)):
                meaning = re.sub(r'(;\s*)+$', '', raw.split('"', 1)[0]).replace('_', ' ')
                meaning = re.sub(r'\s*;\s*', '; ', re.sub(r'\s+', ' ', meaning)).strip()
                if meaning in texts:
                    correct.append(spelling)
        if len(correct) != 1:
            raise SystemExit('Cannot determine unique visible answer: ' + str(correct))
        chosen = next(c for c in choices if c != correct[0]) if index == 0 else correct[0]
        tap('Answer ' + chosen)
        if index == 0: snapshot('quiz-mistake')
        tree = ET.fromstring(hierarchy())
        score = any(n.get('text') == 'See your score' for n in tree.iter('node'))
        tap('See your score' if score else 'Next meaning', scroll=True)
    else:
        raise SystemExit('Quiz did not finish in expected attempts')
elif sys.argv[1] == 'stack':
    pid = adb('shell', 'pidof', 'com.lafz.app').decode().strip()
    adb('shell', 'run-as', 'com.lafz.app', 'kill', '-3', pid)
    time.sleep(1)
    log = adb('logcat', '-d', '--pid=' + pid, '-t', '1800').decode(errors='replace')
    marker = log.rfind('"main" prio=')
    print(log[marker:marker + 5500] if marker >= 0 else log[-5500:])
elif sys.argv[1] == 'restore':
    adb('shell', 'am', 'force-stop', 'com.lafz.app')
    adb('push', str(out / 'prefs-before-tests.xml'), '/data/local/tmp/lafz-test-prefs.xml')
    adb('shell', 'run-as', 'com.lafz.app', 'cp', '/data/local/tmp/lafz-test-prefs.xml', 'shared_prefs/lafz_prefs.xml')
    adb('shell', 'am', 'start', '-n', 'com.lafz.app/.MainActivity')
elif sys.argv[1] == 'verify-restored':
    original = ET.parse(out / 'prefs-before-tests.xml').getroot()
    current = ET.fromstring(adb('exec-out', 'run-as', 'com.lafz.app', 'cat', 'shared_prefs/lafz_prefs.xml'))
    def values(root):
        return {n.get('name'): (n.tag, n.get('value'), tuple(sorted(c.text for c in n)), n.text.strip() if n.text and n.text.strip() else '') for n in root}
    assert values(original) == values(current), 'Preferences differ from the pre-test backup'
    print('Original preferences restored: saved collection, due dates, review stages, topics, theme and notifications.')
elif sys.argv[1] == 'swipes':
    for index in range(int(sys.argv[2])):
        adb('shell', 'input', 'swipe', '450', '1670', '450', '920', '220')
        time.sleep(.45)
    snapshot(sys.argv[3])
