# Lafz review — future improvements

Reviewed on 2026-10-15 using the project source and saved screen previews. This was not a live device walkthrough.

## Status and intent

Implemented on 2026-10-16 at the user's request. The original findings below remain as review context. This implementation includes:

- Meaning selection demotes explicitly labeled slang, dated and specialist senses, applies everyday anchors for head/body, bank/money and run/movement, then prefers learner definitions. The bundled catalogs have matching topic assignments. This is a transparent ranking heuristic; the sources do not provide comprehensive sense-frequency measurements.
- Reading mode explains itself once, offers Show controls, and respects Settings → Keep navigation visible. Read full meaning remains accessible in reading mode.
- Practice retains question order, choices, draft answer, scores, retries, current review word and reveal state across tab changes and Android saved-state restoration. Explicit Exit/Back ends a session.
- Explore uses flexible compact spacing, shorter previews at large text sizes, and a permanent full-meaning action. Examples and topic decoration yield space at large text sizes; full content remains in details.
- Due reviews still lead Practice, with Review 5 words for larger queues and the actual next interval on Got it.
- Learner copy uses Example and Meanings & examples, with source attribution in details.
- Practice shows unique words reviewed over the last seven days, successful recall events, and a gentle five-word daily target. Tracking starts with this version; earlier activity cannot be reconstructed.
- Settings exports/imports saved IDs, review schedules and recorded progress as versioned JSON. Imports validate before writing and merge saved words while preserving existing local schedules. Hidden words can be restored, and removals offer Undo while retaining the word's schedule.

Validation: Android build and lint, packaged Core/Full content and topic audits, JVM learning tests, and device instrumentation for backup validation and practice saved-state round trips. See README for current behavior.

The following sections preserve the original backlog and its rationale. They should be read alongside the implementation status above, and current behavior should be checked before any further work.

## What to preserve

Keep the app's lilac/plum palette, large serif words, and calm reading layout. They give Lafz a strong visual identity suited to vocabulary learning.

## Suggestions, in priority order

### 1. Show the most useful meaning first

The latest saved search preview shows “head” opening with a slang meaning. The code prefers learner definitions but still relies on imported sense order afterward. Rank common, everyday meanings first. Keep specialist, slang, and dated senses accessible under other meanings, with clear labels. This is the highest priority for learner trust.

### 2. Make reading mode more predictable

Swiping automatically hides navigation, topics, and Word notes. Add a one-time explanation and a preference to keep navigation visible. Consider renaming the action from “Navigation” to “Show controls.” Users should understand how to restore controls after their first swipe.

### 3. Preserve unfinished sessions

Switching away from Practice currently clears the review or quiz. Preserve the question, typed answer, and progress so users can check a saved word and return. Restore this state after Android recreates the screen as well.

### 4. Improve the balance on smaller screens

Explore reserves substantial space above and below the word, while the action rail narrows the reading area. Use more flexible spacing and verify long definitions at large text sizes. When text is shortened, provide an obvious “Read full meaning” action.

### 5. Make practice feel manageable

Due reviews already lead the current Practice screen; preserve that hierarchy. Consider a quick “Review 5 words” option. Show the next review interval alongside “Got it,” for example “Got it · back in 7 days,” using the actual scheduled interval.

### 6. Simplify learner-facing wording

Consider replacing “Source example for this sense” with “Example,” while keeping attribution in details. Consider replacing “Word notes” with “Meanings & examples” so the destination is clearer.

### 7. Add a small sense of progress

Consider showing words reviewed this week and words remembered successfully. A gentle daily target could give browsing a purpose while preserving the calm design.

### 8. Give users control over their collection

Consider export/import for saved words and review history, a way to restore words marked “Not for me,” and an Undo action after accidental removal.

## Recommended first implementation batch

When the user requests future implementation, start by discussing the scope of meaning ranking, session preservation, and reading-mode clarity. These are the recommended first priorities, not authorization to begin them.
