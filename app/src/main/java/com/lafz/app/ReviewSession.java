package com.lafz.app;

import java.util.ArrayList;
import java.util.List;

/** A stable queue prevents a shrinking due list from skipping the next word. */
final class ReviewSession implements java.io.Serializable {
    private static final long serialVersionUID = 1L;
    private final List<Word> words;
    private int index;
    private int remembered;
    ReviewSession(List<Word> due) { words = new ArrayList<>(due); }
    Word current() { return complete() ? null : words.get(index); }
    boolean complete() { return index >= words.size(); }
    int size() { return words.size(); }
    int position() { return index; }
    int remembered() { return remembered; }
    void advance(boolean gotIt) {
        if (complete()) return;
        if (gotIt) remembered++;
        index++;
    }
}
