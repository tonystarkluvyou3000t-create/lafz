# Android compatibility and migration assessment

## Existing application and database

The Java app uses `LafzRepository` and a denormalized Princeton WordNet 3.0 `words` table. Rows use app-facing keys such as `lemma_noun`; the importer currently keeps one first-listed sense for each lemma/POS. Saved IDs and review metadata also involve preferences. The production schema expects a `Word` row rather than the new database's normalized many-senses-per-word model.

The connected phone was inspected read-only. It reported package `com.lafz.app`, Android 14, and arm64. The transferred SQLite main file was 86,593,536 bytes; the active `-wal` and `-shm` sidecars were 524,288 and 32,768 bytes. The comparison export contains only the main file, so it may omit uncheckpointed catalog rows. ADB is no longer connected. Counts in `legacy_comparison.md` and `legacy_id_crosswalk.csv` are therefore provisional pending a consistent logical export. The temporary raw copy was deleted after the comparison artifacts were generated.

No progress fields, saved-word preferences, review history, or other mutable user state were read. No on-device database, sidecar, app file, preference, or progress state was written.

## Measured storage and host query performance

| Edition | Entries | Raw SQLite | Gzip |
|---|---:|---:|---:|
| Updated Full | 115,130 | 307,597,312 bytes | 107,448,339 bytes |
| Learner Core | 2,830 | 35,454,976 bytes | 12,750,518 bytes |
| Legacy main DB snapshot | 151,560 rows | 86,593,536 bytes | not measured |

Core includes all available POS, meanings, and enrichments for NGSL 1.2 ranks 1–2,809. It is below the requested 30 MB compressed target. Both generated editions use 4 KiB SQLite pages and report a default SQLite cache setting of 2,048,000 bytes.

Measured on the development host over 60 iterations, Full median/p95 were 0.1573/0.3785 ms for word lookup, 0.1426/0.2037 ms for sense lookup, 0.1620/0.2792 ms for topic filtering, and 12.4137/13.5673 ms for random discovery. Core measured 0.1870/0.3229, 0.1619/0.3270, 0.1373/0.2762, and 0.3133/0.4600 ms respectively. These are not Android measurements. The phone reported about 5.56 GB total memory and 1.12 GB available at capture, but no app-level RSS or on-device SQLite benchmark was run; actual Android runtime memory is unmeasured.

## Legacy comparison

Against the transferred legacy main file, Full has 117,292 shared lemma/POS pairs and 109,715 shared lemmas. There are 34,172 legacy-only and 4,764 Full-only lemmas. The generated crosswalk uniquely maps 117,292 of 151,560 legacy IDs: 116,898 exact spelling/POS matches and 394 unique normalized matches; 34,268 have no Full lemma/POS target. The crosswalk deliberately contains only ID, spelling, POS, unique new word ID where available, and mapping status. It does not use mutable progress data.

Core shares 2,733 lemmas with the legacy snapshot and has unique targets for 4,332 legacy IDs; 147,228 legacy IDs are outside Core scope. Core is a selected learner edition, so its omissions must remain recoverable through the full/legacy fallback.

Lemma/POS overlap is vocabulary matching, not sense identity. A legacy row can point to only the first-listed WordNet sense, whereas a Full entry can have several source-attributed senses. The report's token similarity against OEWN glosses is only a review cue. A proper migration needs the complete legacy database state and sense/synset identity evidence where available.

## Stable adapter and migration design

1. Keep an app-facing compatibility ID independent of new SHA-based internal IDs. Preserve exact old IDs when possible.
2. Build and version a deterministic crosswalk from a consistent logical legacy export. Match spelling plus POS first; resolve specific meanings only with supported sense/synset evidence. Keep normalized spelling matches marked as such, not as sense identity.
3. Store unmatched legacy IDs and spellings in a migration queue. Keep those entries available from the old catalog until they are reviewed or explicitly retired.
4. Keep dictionary content separate from mutable progress. Perform a future migration transactionally and make it restartable. Before and after, compare saved-word, due-review, seen/hidden, and preference counts without losing unmatched identifiers.
5. Keep the old catalog as a fallback until database integrity, user-visible behavior, migration counts, and representative device benchmarks pass.

The current work only designed this approach and generated provisional comparison artifacts. It did not modify the Java app or replace the production catalog.

## Remaining release gates

- Capture a consistent, logical SQLite export from the phone that includes the active WAL state, then regenerate and review the overlap and migration crosswalk.
- Review redistribution obligations for NGSL and Simple English Wiktionary ShareAlike material before public release.
- Benchmark Full and Core with the target Android SQLite version and measure app-level memory and startup/decompression behavior on representative hardware.
- Implement the adapter and versioned migration in a separate reviewable change; preserve old-catalog fallback and validate saved, hidden, seen, due-review, widget, notification, and learning-level behavior.
