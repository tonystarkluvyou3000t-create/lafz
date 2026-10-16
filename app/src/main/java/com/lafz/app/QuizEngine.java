package com.lafz.app;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Quiz selection stays independent of screen rendering. */
final class QuizEngine {
    private QuizEngine() { }

    static List<Word> uniqueSpellings(List<Word> words) {
        List<Word> result = new ArrayList<>();
        Set<String> used = new HashSet<>();
        for (Word word : words) if (used.add(normalize(word.spelling))) result.add(word);
        return result;
    }

    static List<Word> options(Word answer, List<Word> pool) {
        List<Word> candidates = uniqueSpellings(pool);
        candidates.removeIf(word -> !word.partOfSpeech.equals(answer.partOfSpeech) || ambiguous(answer, word));
        Collections.shuffle(candidates);
        candidates.sort(Comparator.comparingInt(word ->
                4 * Math.abs(LearningContent.difficulty(word) - LearningContent.difficulty(answer)) +
                        (word.category.equals(answer.category) ? 0 : 1)));
        List<Word> result = new ArrayList<>(candidates.subList(0, Math.min(3, candidates.size())));
        result.add(answer);
        Collections.shuffle(result);
        return result;
    }

    static boolean ambiguous(Word answer, Word candidate) {
        Set<String> shared = synonyms(answer);
        shared.retainAll(synonyms(candidate));
        shared.remove("");
        return normalize(answer.spelling).equals(normalize(candidate.spelling)) ||
                normalize(answer.meaning).equals(normalize(candidate.meaning)) ||
                synonyms(answer).contains(normalize(candidate.spelling)) ||
                synonyms(candidate).contains(normalize(answer.spelling)) || !shared.isEmpty();
    }

    private static Set<String> synonyms(Word word) {
        Set<String> result = new HashSet<>();
        for (String term : word.synonyms.split(",")) result.add(normalize(term));
        return result;
    }

    static String normalize(String text) {
        return text.trim().toLowerCase(Locale.ROOT).replace('_', ' ').replaceAll("\\s+", " ");
    }
}
