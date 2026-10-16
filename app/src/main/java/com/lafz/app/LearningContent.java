package com.lafz.app;

import java.util.Locale;

/** Difficulty is sourced from NGSL rank when available, otherwise a disclosed lexical estimate. */
final class LearningContent {
    static final String EVERYDAY = "Everyday vocabulary";
    static final String STRETCH = "Stretch vocabulary";
    static final String ALL = "Full dictionary";
    static final String[] LEVELS = {EVERYDAY, STRETCH, ALL};

    private LearningContent() { }

    static int difficulty(Word word) {
        return Math.max(0, Math.min(4, word.difficultyEstimate - 1));
    }

    static boolean isValidLevel(String level) {
        for (String valid : LEVELS) if (valid.equals(level)) return true;
        return false;
    }

    static String displayDifficulty(Word word) {
        String[] labels = {"Very familiar", "Familiar", "Intermediate", "Advanced", "Specialist"};
        return labels[Math.max(0, Math.min(labels.length - 1, word.difficultyEstimate - 1))];
    }
}
