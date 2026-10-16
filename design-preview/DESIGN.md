# Lafz reading redesign

The central job is discovering a word with a single vertical swipe. Saved is a personal collection; Practice is the place to recall meanings. Existing word data, topics, reminders, and saved words are retained.

## Direction

- Lilac canvas `#F0EBF8`, near-white surface `#FCFAFF`, plum ink `#302340`, violet action `#654580`, muted text `#786887`, lavender example `#E7DFF0`.
- Night: violet canvas `#201B2D`, surface `#2C253B`, pale ink `#F4EDFF`, lavender action `#C9B4EF`.
- Serif display words and headings; sans-serif definitions and actions; spaced sans-serif topic captions.
- Full viewport reading with a persistent right thumb rail. Normal navigation uses one shared inset container across every tab.
- Signature: large word typography with finger-tracked, layered text motion that settles exactly one word per swipe.

## Layout

    lafz.                              Topics
    [Explore] Today

    • topic
    word
    part of speech
    meaning                            Save
    example                            Listen
    Word notes                         Share
                                       More
    swipe hint
    [Discover  Saved  Practice  Settings]

On first scroll, supporting chrome fades out. Word content and the rail remain, with Normal added to return to the same word. Text and viewport dimensions stay fixed through this transition.

## Critique and refinements

The former inset panel constrained the word and made the page feel like a card inside a screen. Removing it gives the word the entire reading surface. Full-page scaling was removed to keep type crisp, while subtle title/definition/example parallax retains depth. Quizzes moved out of Saved so collection browsing has one clear job. Search covers word, meaning, and topic. The bottom bar now has the same parent geometry and insets on all tabs.

## Verification

`assembleDebug` and `lintDebug` pass. Device checks cover first-swipe focus, Normal restoring the same word, saved-word search, and the Practice quiz entry flow. A short drag retains the current word. Fourteen consecutive swipes keep the feed populated through batch loading and page pruning. Navigation item bounds match exactly across Discover, Saved, Practice, and Settings. Both palettes were visually inspected, and the original dark preference was restored. Screenshots in this folder document the actual Android implementation.
