package com.lafz.app;

import java.util.Arrays;
import java.util.List;
import java.util.HashSet;

/** Run without Android or network: javac Word + learning classes + this file. */
public final class LearningLogicTest {
    private static Word word(String spelling, String part, String meaning, String synonyms) {
        return new Word(spelling + "_" + part, spelling, "", part, "Everyday", meaning, "", synonyms, "", "");
    }
    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
    public static void main(String[] args) throws Exception {
        Word correct = word("reliable", "adjective", "worthy of trust", "dependable, trustworthy");
        Word synonym = word("dependable", "adjective", "can be trusted", "reliable");
        Word duplicateMeaning = word("steadfast", "adjective", "worthy of trust", "");
        Word sharedSynonym = word("secure", "adjective", "safe", "trustworthy");
        Word wrongPart = word("trust", "noun", "belief in someone", "");
        Word a = word("curious", "adjective", "eager to learn", "");
        Word b = word("gentle", "adjective", "mild in manner", "");
        Word c = word("brave", "adjective", "showing courage", "");
        for (int i = 0; i < 30; i++) {
            List<Word> options = QuizEngine.options(correct, Arrays.asList(synonym, duplicateMeaning, sharedSynonym, wrongPart, a, b, c, a));
            check(options.size() == 4 && options.contains(correct), "exactly one answer and three safe distractors");
            check(!options.contains(synonym) && !options.contains(duplicateMeaning) && !options.contains(sharedSynonym) && !options.contains(wrongPart), "reject ambiguous or mismatched options");
            check(new HashSet<>(options).size() == 4, "no duplicate choices");
        }
        check(QuizEngine.options(correct, Arrays.asList(synonym, wrongPart)).size() == 1, "small pools must allow type fallback");
        check(QuizEngine.normalize("  On   Purpose ").equals("on purpose"), "normalize typed spacing");
        ReviewSession session = new ReviewSession(Arrays.asList(a, b, c));
        check(session.current() == a, "first due word"); session.advance(false);
        check(session.current() == b, "second due word is not skipped"); session.advance(true);
        check(session.current() == c, "third due word is not skipped"); session.advance(true);
        check(session.complete() && session.remembered() == 2 && session.size() == 3, "session completion and summary");
        session.advance(true); check(session.remembered() == 2, "completed sessions do not double count");
        ReviewSession partial = new ReviewSession(Arrays.asList(a, b, c)); partial.advance(true);
        java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
        try (java.io.ObjectOutputStream output = new java.io.ObjectOutputStream(bytes)) { output.writeObject(partial); }
        ReviewSession restored;
        try (java.io.ObjectInputStream input = new java.io.ObjectInputStream(new java.io.ByteArrayInputStream(bytes.toByteArray()))) {
            restored = (ReviewSession) input.readObject();
        }
        check(restored.position() == 1 && restored.remembered() == 1 && restored.current().spelling.equals(b.spelling),
                "serialized review preserves current word, position and score");
        System.out.println("Learning logic checks passed: quiz ambiguity, fallback, review queue and session serialization.");
    }
}
