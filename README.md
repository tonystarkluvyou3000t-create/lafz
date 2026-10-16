# Lafz

Lafz is a free, offline-first Android vocabulary app. Explore opens as a vertical snap feed with one word at a time and a thumb-friendly action rail. Words can be saved, reviewed, shared, spoken aloud, or filtered by a multi-select topic mix.

## Word source

Lafz bundles Open English WordNet 2025, learner definitions from Simple English Wiktionary, CMUdict pronunciations, and NGSL usefulness ranks. The Core dictionary contains 2,830 entries; Full contains 115,130. Both unpack locally with integrity checks and work offline. Source attribution and license terms are available in Settings → Sources & licenses.

The 27 browsing topics include Nature, Food, Science, Health, Technology, Arts & Culture, Sports, Work & Money, People, Society, Relationships, Communication, Learning & Thought, Travel & Movement, Places, Time, Numbers & Shapes, Objects & Tools, Actions, Change, Senses, Emotions, Qualities, Manner, States & Conditions, Ideas & Concepts, and Grammar.

Each word's topic follows the meaning shown on its card. Topic labels use source subject domains, explicit definition rules, and lexical domains. Broad labels such as Qualities or Ideas & Concepts cover meanings without a narrower subject; confidence and the classification method remain in the data. Different meanings keep separate sense labels. Existing installations automatically refresh the bundled catalogs without changing word IDs, saved words, or review/exposure history.

To regenerate only topic data, run `python tools/refresh_packaged_topics.py`. It verifies that all non-topic content is unchanged, copies Full labels into Core, and updates both compressed assets, the manifest, and Android integrity constants. Coverage is recorded in `build/topic-refresh/report.json`.

## Build and install

Open this folder in Android Studio, or run:

```powershell
.\gradlew.bat assembleDebug
.\gradlew.bat installDebug
```

The app uses Android SDK 36 and supports Android 8.0 (API 26) and newer.

## Included

- Daily and Explore feeds with handpicked Everyday vocabulary by default, Stretch vocabulary and Full dictionary levels, and independent multi-select topic filters.
- Vertical snap navigation with save, listen, share, details, “more like this,” and “not for me” actions.
- Searchable saved words with Recent, A–Z, due-review and topic views, plus optional compact rows. New saves record their time; older saves without timestamps sort alphabetically within Recent.
- A Practice hub that leads with due reviews, focused review sessions with progress and completion summaries, and meaning-to-word quizzes for saved or general vocabulary (typed or multiple-choice). Quiz distractors prefer similar word levels and topics, match part of speech, and exclude duplicate meanings and known synonyms. Missed first-pass questions return once at the end of the round, with separate first-try and retry scores.
- Opt-in daily notifications with a chosen time, a home-screen widget, and shareable word cards.
- Light and dark reading palettes.

## Reading experience

The redesigned UI uses a full-screen lilac reading canvas, plum accents, expressive serif words, and a matching dark violet palette. Explore tracks vertical drags directly, then settles one word at a time with a soft deceleration and layered title, definition, and example parallax. Short drags return to the current word; fast flicks advance one word. At the feed edges the page resists gently. Adjacent pages are preloaded and older pages are pruned after settling.

Android's animation setting is respected: turning animations off removes the settle animation and parallax. Accessibility scroll actions move between words. Longer entries can be read in full with Word notes or More → Word details. Saved supports searching by spelling, meaning, and topic. Practice gathers both quiz modes and scheduled recall. Today, saved-word entries, practice, settings, the widget, and share cards use the same visual language.

Words become seen when displayed, rather than when prefetched. Word levels and topic changes preserve exposure history. Small topic pools release old pages when starting another round.

The same topic list is used by Explore and Saved. Topic filters match the card's displayed meaning, so another meaning of a word cannot give its card an unrelated topic.

Source glosses retain all definition clauses separated by semicolons. Only original quoted examples containing the displayed spelling are shown; synonyms are never substituted into source sentences. Version 4 refreshes older catalogs once from the official source, preserving word IDs, saved words, hidden words and review history. Existing entries remain readable during this refresh; source content updates transactionally. Until the refresh succeeds, old rewritten examples are omitted. Subsequent use works offline.

The app opens with a 1.45-second sequence: the logo ribbon draws itself, the speech mark settles into place, the wordmark arrives letter by letter, and a circular reveal opens into Explore. The first Explore batch loads during this animation. Dictionary connections are reused, pronunciation lookups use a word index, and discovery selects eligible IDs before loading only the requested word details. Bundled dictionaries are unpacked once (including once when an updated bundle is installed); subsequent launches reuse the on-device files. Saved words and review/exposure history remain in their separate progress stores. The adaptive Lafz icon combines a curved L with a small speech leaf, and supports Android themed icons.

A first vertical drag in Explore enters reading mode and explains how to restore the controls. Tap Show controls in the thumb rail, or press Back, to restore the full view without changing the word. Settings → Keep navigation visible disables automatic hiding. Read full meaning stays available in reading mode. Neither the paging viewport nor the text layout changes size during this transition. Compact layouts use flexible spacing and reserve room for full-meaning access at larger text sizes. Every normal screen uses the same bottom navigation component, width, and 16dp side insets.

Category selection, word actions, word details, saved-word details, source/licensing, and notification time use a shared rounded bottom-sheet surface with palette-aware colors and motion. Action icons are drawn at explicit geometric centers rather than aligned through text metrics. Single words shrink to fit rather than splitting across lines.

Word details lead with the current meaning and one example, with Save and Listen side by side. Additional meanings open one at a time with Previous/Next navigation; extra examples, related words, difficulty estimates, and source credits are available on demand. Repeated source credits are consolidated. Dictionary search uses a fixed 54dp input with a clear action above compact result rows. Opening a result retains the query and results for returning, while underlying sheets are hidden to keep the active popup visually clean. Sheet content respects the space available above the keyboard.

Word cards demote explicitly labeled slang, dated and specialist meanings, use curated everyday anchors for head, bank and run, and prefer learner definitions among remaining senses. This is a heuristic rather than a measured frequency ranking. Topics are regenerated from the same selection policy. The part of speech, definition, source credit, and example come from that selected meaning; examples must also come from the definition's own source. Other definitions, labels and meanings remain accessible in details.

Practice preserves unfinished quizzes and reviews when switching tabs and through Android saved-state restoration, including typed drafts, reveal state, shuffled options, retry queues and scores. Exit or Back explicitly ends the active session. Larger due queues offer Review 5 words, and Got it displays the actual next interval. Progress shows unique words reviewed in the last seven days, successful recall events and a gentle five-word daily target; these counts start with this version.

Settings → Your collection exports/imports versioned JSON containing saved word IDs, save timestamps, review schedules and recorded progress. Imports merge word lists and preserve existing local schedules; malformed backups are rejected before changes are applied. Hidden words can be restored. Removing a saved word offers Undo and retains its review schedule for restoration.
