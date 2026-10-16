package com.lafz.app;

import android.content.Context;
import android.content.SharedPreferences;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

final class ReviewScheduler {
    private static final int[] INTERVALS = {1, 3, 7, 14, 30, 60};
    private ReviewScheduler() { }

    static List<Word> due(Context context, List<Word> saved) {
        SharedPreferences prefs = LafzPrefs.get(context);
        int today = LafzPrefs.today();
        List<Word> result = new ArrayList<>();
        for (Word word : saved) if (prefs.getInt("due_" + word.id, today) <= today) result.add(word);
        result.sort(Comparator.comparingInt((Word word) -> prefs.getInt("due_" + word.id, today))
                .thenComparing(word -> word.spelling));
        return result;
    }

    private static java.util.Set<String> withWord(java.util.Set<String> ids, String id) {
        java.util.Set<String> copy = new java.util.HashSet<>(ids); copy.add(id); return copy;
    }

    static int nextInterval(Context context, Word word) {
        int stage = Math.max(0, Math.min(INTERVALS.length - 1, LafzPrefs.get(context).getInt("stage_" + word.id, 0)));
        return INTERVALS[stage];
    }

    static void answer(Context context, Word word, boolean remembered) {
        SharedPreferences prefs = LafzPrefs.get(context);
        int stage = Math.max(0, Math.min(INTERVALS.length - 1, prefs.getInt("stage_" + word.id, 0)));
        String day = "reviewed_day_" + LafzPrefs.today();
        prefs.edit().putStringSet(day, withWord(prefs.getStringSet(day, new java.util.HashSet<>()), word.id))
                .putInt("remembered_day_" + LafzPrefs.today(), prefs.getInt("remembered_day_" + LafzPrefs.today(), 0) + (remembered ? 1 : 0)).apply();
        prefs.edit().putInt("due_" + word.id, LafzPrefs.today() + (remembered ? INTERVALS[stage] : 1))
                .putInt("stage_" + word.id, remembered ? Math.min(INTERVALS.length - 1, stage + 1) : 0).apply();
    }
}
