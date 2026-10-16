# Lafz Lexicon quality and Android readiness

Date: 2026-10-12

## Outcome

The original full build is preserved under `build/full-before-quality/`. The updated Full database was produced from the existing full build and local source archives; a subsequent topic-policy change refreshed only generated topic labels in that database. Core was regenerated as a dependent, learner-focused edition. Neither action modified the production Android app or its on-device database, preferences, or progress.

## Full and Core artifacts

| Measure | Previous Full | Updated Full | Core |
|---|---:|---:|---:|
| Word entries | 115,130 | 115,130 | 2,830 |
| Senses | 208,801 | 207,689 | 26,801 |
| Definitions | 208,954 | 208,952 | 27,022 |
| Sense-associated word-presence checked examples | 70,299 | 70,299 | 25,295 |
| Word-level examples with sense unresolved | 120,080 | 117,407 | 8,490 |
| Words with any difficulty estimate | 2,830 (2.46%) | 115,130 (100%) | 2,830 |
| Words with consensus topic | 21,349 (18.54%) | 23,276 (20.22%) | 22 |
| SQLite file bytes | 260,034,560 | 307,597,312 | 35,454,976 |
| Gzip bytes | 100,064,562 | 107,448,339 | 12,750,518 |

Core selects NGSL 1.2 single-word entries ranked 1–2,809 and keeps all available POS, senses, definitions, source metadata, pronunciations, and examples for the selected spellings. It covers 2.46% of Full entries, 12.90% of senses, and 12.93% of definitions. Its compressed file is below the 30,000,000-byte target. Compression and inclusion do not change the source licenses; both editions keep per-source attribution and license metadata.

The difference in Full sense counts reflects conservative Simple Wiktionary alignment: accepted glosses become alternative definitions on an OEWN sense, while weak, tied, or candidate-free glosses remain independent. Definitions are preserved with source attribution. Updated alignment counts are 1,171 accepted, 25 ambiguous, and 35,689 unmatched, compared with the previous 59 accepted and 36,826 unmatched.

## Quality decisions

- The previous aligner used all-token Jaccard similarity with a 0.78 threshold. This penalized paraphrases with different sentence frames. Candidate generation also excluded 4,563 Simple entries with no same-lemma/POS OEWN candidate.
- The replacement is deterministic and reviewable: same spelling and POS only, one canonical OEWN definition per sense, content-token F1, score at least 0.60, two shared content tokens, and a 0.12 margin over the next candidate. A tie, weak evidence, or no candidate stays separate. A local embedding model was unavailable and no model was downloaded. Semantic relationships are not accepted as proof of sense identity.
- Difficulty coverage is now complete but should not be read as official proficiency coverage: 2,830 NGSL entries receive rank-derived bands; 112,300 entries receive low-confidence Lafz lexical-complexity estimates with per-word evidence (syllable source, length, affix and specialist/usage signals). No CEFR data was available. These estimates are explicitly not CEFR.
- Topic coverage increased from 21,349 to 23,276 consensus-labeled words, while sparse and polysemous entries remain unclassified at word level. The updated policy maps OEWN lexnames at the sense level and uses narrowly scoped definition evidence for broad `noun.phenomenon` entries. Physical-science definitions can receive Science; `serendipity` does not. Word labels require at least 80% of labeled senses and 50% of all senses to agree. The final category counts are Emotions 693, Food 2,346, Nature 12,207, Science 8,030.
- Full has 70,299 sense-associated examples that passed word-presence checks, covering 56,034 senses. Separately, 117,407 Tatoeba examples are retained at word level with `sense_match_status='unverified'`; they are not displayed as authoritative examples of a specific meaning. Core carries 8,490 such word-level examples.

The 30-entry manual review is in `build/full/reports/manual_review.md`. Reproducible before/after alignment and topic measurements are in `alignment_audit.md` and `quality_report.md`. Source archives, pipeline/config files, and final database/gzip artifacts have checksums in their manifests. The original baseline and the latest Full/Core databases are separate artifacts.

## Performance and storage

On the development host, 60-iteration median/p95 timings in the latest Full-vs-Core report were:

| Query | Full median / p95 | Core median / p95 |
|---|---:|---:|
| Word lookup | 0.1573 / 0.3785 ms | 0.1870 / 0.3229 ms |
| Sense lookup | 0.1426 / 0.2037 ms | 0.1619 / 0.3270 ms |
| Topic filter | 0.1620 / 0.2792 ms | 0.1373 / 0.2762 ms |
| Random discovery | 12.4137 / 13.5673 ms | 0.3133 / 0.4600 ms |

These are host measurements, not Android timings. Both files use 4,096-byte pages and report a 2,048,000-byte default SQLite cache setting. The connected device was Android 14 / arm64, with about 5.56 GB total and 1.12 GB available RAM at capture. No app-level RSS or SQLite query benchmark was run, so the actual Android memory requirement remains unmeasured. The raw/gzip byte counts above are the measured storage requirements for the generated artifacts.

## Legacy Android comparison and limitation

A read-only export was captured from `com.lafz.app` user 0. The app's WordNet 3.0 table has 151,560 word rows / lemma-POS pairs and 143,887 unique lemmas in the transferred main database file. Against updated Full, the comparison found 117,292 shared lemma/POS pairs, 109,715 shared lemmas, 34,172 legacy-only lemmas, and 4,764 Full-only lemmas. The generated crosswalk uniquely maps 117,292 old IDs (116,898 exact spelling, 394 normalized unique) and leaves 34,268 without a matching lemma/POS. These are vocabulary and ID correspondences, not proof of sense identity.

The export copied the 86,593,536-byte main database file only. A 524,288-byte `-wal` and 32,768-byte `-shm` sidecar were present on-device but were not copied. The device has since disconnected from ADB, so uncheckpointed catalog records may be absent from the export and all overlap/crosswalk counts are provisional. No saved/progress columns or preferences were read. The temporary raw copy was deleted after writing the report and crosswalk; a new consistent logical SQLite export is required before treating the crosswalk as a final migration map.

Core intentionally has only 2,733 shared lemmas with the old export and uniquely maps 4,332 legacy IDs; 147,228 old IDs remain outside its selected vocabulary. This is expected for a compact Core edition. The migration plan must keep unmatched IDs on the old catalog fallback and must not interpret Core omissions as deleted user data.

## Migration design and remaining release gates

Use a versioned, read-only compatibility adapter that presents a stable app-facing legacy ID while exposing multiple normalized senses and source attribution. Produce a consistent legacy export, refresh the crosswalk, and resolve sense identity only when actual sense/synset evidence supports it. Preserve unmatched legacy IDs and saved/review state in a migration queue with old-catalog fallback. Make any future migration transactional, restartable, count-checked, and versioned. The current old database remains untouched.

Before a production switch, still complete a consistent legacy snapshot, redistribution license review for ShareAlike sources, target-Android SQLite and runtime memory/performance measurements, and a separately reviewed adapter/migration change. The current task did not replace the production database.

## Validation

The updated Full and regenerated Core databases each passed SQLite integrity and foreign-key validation with zero warnings. All 18 Python tests passed, including the added regression for definition-evidence topic rules; Python bytecode compilation and the standalone Java learning-logic checks also succeeded. Android instrumentation and on-device database benchmarks were not run because ADB disconnected. Reports were regenerated from the final databases, and each gzip stream expands to the exact measured database bytes. Artifact manifests record final sizes and checksums.
