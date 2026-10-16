package com.lafz.app;

import java.util.Arrays;
import java.util.List;

/** Browsing topics for the displayed meaning, including broad lexical fallbacks. */
final class LafzWords {
    static final List<String> CATEGORIES = Arrays.asList(
        "Nature", "Food", "Science", "Health",
        "Technology", "Arts & Culture", "Sports", "Work & Money",
        "People", "Society", "Relationships", "Communication",
        "Learning & Thought", "Travel & Movement", "Places", "Time",
        "Numbers & Shapes", "Objects & Tools", "Actions", "Change",
        "Senses", "Emotions", "Qualities", "Manner",
        "States & Conditions", "Ideas & Concepts", "Grammar"
    );
    private LafzWords() { }
}
