# Lafz Lexicon

Standalone, offline Python data pipeline for building `lafz_dictionary.db`. It is intentionally isolated from the Android application: this work does not change `LafzRepository`, the production WordNet catalog, or saved/review state.

The pipeline uses Python 3.10+ standard-library modules only. It supports both checksum-recorded downloads and a strict local-only mode. A small sample build uses a checked-in capture of real Open English WordNet API records; that fixture is only a parser/schema smoke input and is never substituted when a full download fails.

## Windows quick start

Run commands from `tools`:

```powershell
Set-Location 'C:\My work folder\Claude Desktop\myproject\tools'
py -3 -m lafz_lexicon build --sample
py -3 -m unittest discover -s lafz_lexicon\tests -v
```

The sample database, gzip package, and measured reports are written under `tools/lafz_lexicon/build/sample/`. Generated source archives, temporary files, databases, and reports are ignored by the package `.gitignore`.

## Full build

The default build obtains the pinned OEWN 2025 release, current CMUdict snapshot, and official NGSL 1.2 frequency CSV. Each downloaded file gets a SHA-256, byte size, URL, version, and retrieval time in `cache/sources/source_manifest.json`.

```powershell
Set-Location 'C:\My work folder\Claude Desktop\myproject\tools'
py -3 -m lafz_lexicon download
py -3 -m lafz_lexicon build
py -3 -m lafz_lexicon validate lafz_lexicon\build\full\lafz_dictionary.db
py -3 -m lafz_lexicon report lafz_lexicon\build\full\lafz_dictionary.db
```

The full database and `.db.gz` package are under `tools/lafz_lexicon/build/full/`; every non-sample full build also writes a selected Core edition under `tools/lafz_lexicon/build/core/`. Markdown, JSON, and source manifests are in each edition's `reports/` directory. The downloader writes only to the package cache and reports DNS, HTTP, and checksum failures. It never falls back to fixture or generated lexical content.

## Offline build from local archives

Put local sources under `tools/lafz_lexicon/source_archives/` and run:

```powershell
py -3 -m lafz_lexicon build --offline
```

Offline mode never calls the downloader. It recursively identifies local archives by file name and signature, records their actual byte sizes and SHA-256 values, and writes `build/full/reports/source_manifest.json`. OEWN is required as the semantic base. Missing or unreadable optional sources are reported and skipped while independently usable sources continue. Use `--source-dir` to scan another directory or pass explicit `--oewn`, `--cmudict`, `--ngsl`, `--simple-wiktionary`, and `--tatoeba` paths. Gzip, bzip2, and single-data-file ZIP containers are opened by signature.

The offline builder selects the OEWN XML, CMUdict text, NGSL ranked CSV, Simple English Wiktionary JSONL, and Tatoeba English sentence export when present. The general Tatoeba export is CC BY 2.0 France; it is not labeled CC0. Sentence IDs are retained in provenance, near-duplicate and low-quality candidates are filtered, and selected examples are stored as `word_examples` with word presence checked and sense fit unverified. The optional NGSL XLSX workbook is inventoried but not imported when the ranked CSV is available: its word-level definitions lack POS and sense mapping metadata. The all-language Kaikki dump is optional and was not present in the current archive set.

You can provide local source files instead of downloading them:

```powershell
py -3 -m lafz_lexicon build `
  --oewn C:\data\english-wordnet-2025.xml.gz `
  --cmudict C:\data\cmudict.dict `
  --ngsl C:\data\NGSL_12_stats.csv
```

Optional sources are passed explicitly:

```powershell
py -3 -m lafz_lexicon build --kaikki C:\data\raw-wiktextract-data.jsonl.gz
py -3 -m lafz_lexicon build --simple-wiktionary C:\data\simple-extract.jsonl.gz
py -3 -m lafz_lexicon build --tatoeba C:\data\eng_sentences.tsv.bz2
```

Kaikki is streamed line-by-line, restricted to `lang_code == "en"`, existing OEWN headwords, ordinary lexical POS, and senses with usable definitions. The current raw all-language download is large (about 2.8 GB compressed), so it is optional and is not downloaded by default. The all-English postprocessed Kaikki export is larger, is marked deprecated on its own page, and reports that it can include data merged from other sources; this pipeline therefore does not use it. Simple English definitions remain a separate source alternative. Tatoeba rows are retained only with the source attribution and license recorded; target-word presence does not establish sense fit, so examples stay `unverified_sense`.

For research builds, preserve the source manifest, source archives, tool version, config, and generated source registry with the resulting database. Source content remains separately attributable; no single license is asserted for the aggregated database. Review the exact upstream terms before redistributing a database that includes ShareAlike sources.

## Source and aggregation policy

See [SOURCES.md](SOURCES.md) for release, source-format, acquisition, quality, and redistribution findings. The machine-readable [source_registry.json](source_registry.json) is copied into `data_sources` at build time; checksums are attached to the exact artifacts used.

- OEWN is the semantic backbone. It retains synsets, source sense IDs, parts of speech, definitions, examples, lexical files, and semantic links.
- A `word` is a spelling, not a meaning. Source sense identifiers and synset identifiers stay separate. The stable word ID preserves spelling case; lookup uses a separate case-folded index. OEWN 2025 separates proper nouns into Open English Namenet; a further filter excludes capitalized multiword entity-like exceptions while retaining source spelling on accepted entries.
- Definitions are preserved verbatim, attributed to their source, and ranked by source role. No generated replacement definitions are created.
- A Simple English Wiktionary definition is compared only with same-lemma and same-POS OEWN senses. One canonical definition per OEWN sense is scored using deterministic content-token F1; automatic attachment requires a score of at least 0.60, at least two shared content tokens, and a 0.12 margin over the next sense. Weak and tied cases remain independent source senses. Source definitions are retained verbatim and attributed.
- Sense-associated examples must include the displayed word, an explicit source form, or a conservative English inflection as a complete token. Tatoeba examples live in the separate `word_examples` table, with `word_presence_only` and `sense_match_status='unverified'`; they are not rendered beneath a specific definition.
- CMU pronunciations retain ARPABET and stress digits under `phonetic_alphabet='ARPABET'`; OEWN or Wiktionary IPA is stored as IPA. They are not conflated.
- Official NGSL rank is a usefulness/frequency signal. The `Everyday` and `Stretch` bands use published rank thresholds configured in `config.json`. No CEFR source is available: rank-derived bands and the separate 1–5 difficulty estimates are explicitly heuristic, include per-row confidence/evidence, and must not be displayed as official CEFR.
- Topics follow the meaning selected by the Android card query, rather than requiring agreement across unrelated meanings. All 27 browsing topics are configured in `config.json`. Source subject domains and scoped definition rules refine the full OEWN lexname map. Unaligned learner meanings use explicit definition evidence, unambiguous topics for the same spelling/POS, or disclosed broad POS fallbacks. Topic assignment never changes sense alignment, source definitions, or examples. Every sense label and word category retains confidence, and word categories record the selected sense and method. `reward` cannot be forced into Nature. Core copies Full's labels so a reduced domain graph cannot change classifications.
- Quiz distractors are precomputed only when matching part of speech, NGSL band, and rank proximity are available. Synonyms are never used as distractors.

There was no suitable local embedding model in the available cache, and the build is offline. No cloud, runtime AI, SQLite extension, or paid service is used. The lexical matcher is deterministic and preserves unresolved cases; semantic links are not treated as proof that two glosses express the same sense.

## Database and validation

Schema version 2 is in [schema.sql](schema.sql). It includes `words`, `synsets`, `senses`, `definitions`, sense-associated `examples`, separate word-level `word_examples`, `pronunciations`, `word_forms`, confidence-bearing `sense_labels`, `sense_alignments`, `semantic_relations`, `word_relations`, display-meaning `word_categories`, confidence-bearing `frequency_metadata`, `quiz_distractors`, `data_sources`, `source_attributions`, and `build_metadata`. Content tables store a `source_id` on each imported row. `source_attributions` retains source-specific identifiers such as Tatoeba sentence IDs.

SQLite foreign keys are enabled during construction. Indexes support normalized spelling lookup, sense lookup by word/POS, topic filters, rank filters, semantic relations, and quiz candidates. The output uses no extension features and is compatible with standard Android SQLite query primitives. Build-time checks run integrity, foreign key, POS, source reference, example presence, ARPABET, topic regression, and empty database checks.

```powershell
py -3 -m lafz_lexicon validate path\to\lafz_dictionary.db
py -3 -m lafz_lexicon report path\to\lafz_dictionary.db --legacy-db path\to\catalog.db
py -3 -m lafz_lexicon core path\to\lafz_dictionary.db --output path\to\lafz_core.db
```

Reports include actual counts, SQLite and gzip sizes, source checksums, separate sense/word example coverage, estimated-difficulty evidence, sense and displayed-topic coverage, and median/p95 host SQLite query timings. `build` creates both editions; Core includes all available POS/senses for word spellings ranked 1–2,809 in NGSL 1.2. The report measures coverage and compression against Full. It explicitly distinguishes host timings from Android-device benchmarks. `report --legacy-db <path>` also writes a full legacy ID crosswalk with unmatched and ambiguous rows; lemma/POS matches do not establish sense equivalence. `refresh-topics <database>` reapplies configured topic rules in place without rebuilding or reingesting source archives.

## Current measured output

The local archive set has been built with `--offline`; Full and Core databases, gzip packages, source and artifact checksum manifests, measured quality reports, alignment audit, connected-phone legacy comparison, migration crosswalk, and 30-entry review are under `build/`. The legacy comparison is explicitly provisional because its read-only export omitted an active device WAL; see [PROJECT_STATUS.md](PROJECT_STATUS.md). The six-synset fixture remains only for parser/schema smoke checks and is never used as a fallback for full builds. The production Java app, on-device database, preferences, and user progress have not been modified.
