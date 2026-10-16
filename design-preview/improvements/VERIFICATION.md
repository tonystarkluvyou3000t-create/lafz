# Lafz learning improvements — October 11, 2026

The final debug build is installed on the connected Android device. The first-scroll transition into reading mode is preserved. Its return action is now Navigation, with Show navigation as the accessible name.

## Changes

- Handpicked Everyday and Stretch word lists, with a reviewed set of specialist/archaic senses reserved for Full dictionary. Word levels and topic choices are independent. Eleven explicit nature/science topic corrections fix broad WordNet grouping mismatches, including species under People and biosphere under Places & time; existing catalogs receive these corrections in schema version 5.
- Due reviews lead Practice. A stable session queue shows progress and a completion summary without skipping entries as due dates change. Review intervals progress through 1, 3, 7, 14, 30 and 60 days; Again tomorrow resets the stage.
- Quiz choices match part of speech, favor similar levels/topics, and exclude known synonyms, shared synonyms and duplicate definitions. Small pools fall back to typed answers. Missed questions return once, with separate first-try and retry results.
- Definitions retain their semicolon-separated clauses. Source examples remain unmodified and must contain the displayed spelling. Quoted terms inside definitions are retained, while source quotation attribution is excluded from meanings.
- Saved supports Recent, A–Z, due-review and topic views, plus compact rows. New saves receive timestamps; older saves retain alphabetical ordering when no historic save time is available.
- Displayed words become seen; prefetched words remain unseen. Background source refresh preserves IDs and hidden/exposure state. WAL lets Saved, Today and widgets read existing entries during source updates.
- SavedWordsScreen, QuizEngine, ReviewScheduler, ReviewSession and LearningContent separate collection rendering and learning logic from activity navigation.

## Verification

- `assembleDebug`, `assembleDebugAndroidTest` and `lintDebug` succeed. Lint reports warnings, including target SDK/deprecation, English text and programmatically constructed views; no lint errors.
- `tools/LearningLogicTest.java` passes: source parsing, quoted definition terms, word levels, quiz ambiguity, small-pool fallback, typed whitespace normalization and stable review ordering.
- `LearningInstrumentation` passes on Android using isolated test databases and a separate preference file: level/topic/hidden exclusions, prefetch versus exposure, eligible-round resets, schema migration, save metadata and review scheduling.
- The refreshed real catalog passes SQLite integrity checking: 151,560 entries have raw source glosses; all 12 saved IDs and the existing hidden-word state survive.
- A full ten-word review session completes without skipping words and reaches the caught-up state.
- A complete quiz with one deliberate mistake finishes at 9/10 on the first try, with that missed word recalled on its later retry.
- New saves appear first in Recent; saved search and compact/topic/due views work.
- A short first drag enters reading mode without changing the word. Navigation restores controls on the same word. Fourteen consecutive swipes keep the feed populated.
- Light and dark Saved/Practice screens have been visually inspected.
- Device test saves, due dates, stages, topics, notification settings and theme are restored from the pre-test preferences backup. The temporary test APK is removed after verification.

## Captures

- [Explore](final-explore.png)
- [Practice](final-practice.png), [dark Practice](final-practice-dark.png)
- [Saved](final-saved.png), [dark Saved](final-saved-dark.png)
- [Reading mode](final-focus.png)
- [Word levels](levels.png)
- [New saves first](saved-recent.png), [search](saved-search.png)
- [Quiz explanation](quiz-mistake.png), [quiz completion](quiz-complete.png)
- [Review completion](review-complete.png)

Older catalog upgrades require one refresh from the official WordNet source. Until it succeeds, legacy examples are omitted because previous versions rewrote them. The updated catalog works offline afterward.
