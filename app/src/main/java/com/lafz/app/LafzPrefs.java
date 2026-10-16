package com.lafz.app;

import android.content.Context;
import android.content.SharedPreferences;

import java.time.LocalDate;
import java.util.HashSet;
import java.util.Set;

final class LafzPrefs {
    static final String FILE = "lafz_lexicon_prefs_v2";
    static final String CATEGORIES = "selected_categories";
    static final String SAVED = "saved_words";

    private LafzPrefs() { }

    static SharedPreferences get(Context context) {
        return context.getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    static Set<String> categories(Context context) {
        Set<String> stored = new HashSet<>(get(context).getStringSet(CATEGORIES, new HashSet<>()));
        Set<String> valid = new HashSet<>(stored);
        valid.retainAll(LafzWords.CATEGORIES);
        if (!valid.equals(stored)) get(context).edit().putStringSet(CATEGORIES, new HashSet<>(valid)).apply();
        return valid;
    }

    static Set<String> saved(Context context) {
        return new HashSet<>(get(context).getStringSet(SAVED, new HashSet<>()));
    }

    static String level(Context context) {
        String level = get(context).getString("word_level", LearningContent.EVERYDAY);
        if (LearningContent.isValidLevel(level)) return level;
        return LearningContent.EVERYDAY;
    }

    static void saveWord(Context context, Word word, boolean keep) {
        Set<String> saved = saved(context);
        SharedPreferences.Editor edit = get(context).edit();
        if (keep) {
            saved.add(word.id);
            if (!get(context).contains("saved_at_" + word.id)) edit.putLong("saved_at_" + word.id, System.currentTimeMillis());
            if (!get(context).contains("due_" + word.id)) edit.putInt("due_" + word.id, today()).putInt("stage_" + word.id, 0);
        } else {
            saved.remove(word.id);

        }
        edit.putStringSet(SAVED, saved).apply();
    }

    static int today() {
        return (int) LocalDate.now().toEpochDay();
    }
}
