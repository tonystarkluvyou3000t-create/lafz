package com.lafz.app;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.HashSet;
import java.util.Set;

/** Versioned, validated merge backup. Only collection and review keys are accepted. */
final class CollectionBackup {
    static String exportJson(Context context) throws Exception {
        SharedPreferences prefs = LafzPrefs.get(context);
        JSONObject entries = new JSONObject();
        for (java.util.Map.Entry<String, ?> entry : prefs.getAll().entrySet()) {
            String key = entry.getKey(); Object value = entry.getValue();
            if (allowed(key)) entries.put(key, value instanceof Set ? new JSONArray((Set<?>) value) : value);
        }
        return new JSONObject().put("format", "lafz-collection").put("version", 1).put("data", entries).toString(2);
    }

    static void importJson(Context context, String text) throws Exception {
        JSONObject root = new JSONObject(text);
        if (!"lafz-collection".equals(root.getString("format")) || root.getInt("version") != 1)
            throw new IllegalArgumentException("Unsupported Lafz backup");
        JSONObject data = root.getJSONObject("data");
        // Validate the entire document before applying any changes.
        java.util.Map<String, Object> values = new java.util.HashMap<>();
        for (java.util.Iterator<String> keys = data.keys(); keys.hasNext();) {
            String key = keys.next(); Object value = data.get(key);
            if (!allowed(key)) throw new IllegalArgumentException("Unexpected backup field");
            if (key.equals(LafzPrefs.SAVED) || key.startsWith("reviewed_day_")) {
                if (!(value instanceof JSONArray)) throw new IllegalArgumentException("Invalid word list");
                Set<String> ids = new HashSet<>(); JSONArray list = (JSONArray) value;
                for (int i = 0; i < list.length(); i++) {
                    Object id = list.get(i);
                    if (!(id instanceof String) || !((String) id).matches("w_[A-Za-z0-9_]+"))
                        throw new IllegalArgumentException("Invalid word ID");
                    ids.add((String) id);
                }
                values.put(key, ids);
            } else {
                if (!(value instanceof Number)) throw new IllegalArgumentException("Invalid review number");
                long number = ((Number) value).longValue();
                if (number < 0 || ((Number) value).doubleValue() != number || (!key.startsWith("saved_at_") && number > Integer.MAX_VALUE)
                        || (key.startsWith("stage_") && number > 5)) throw new IllegalArgumentException("Invalid review range");
                values.put(key, number);
            }
        }
        SharedPreferences prefs = LafzPrefs.get(context); SharedPreferences.Editor edit = prefs.edit();
        for (java.util.Map.Entry<String, Object> entry : values.entrySet()) {
            String key = entry.getKey(); Object value = entry.getValue();
            // Existing local schedules win; saved IDs and day word sets are merged.
            if (value instanceof Set) {
                Set<String> merged = new HashSet<>(prefs.getStringSet(key, new HashSet<>()));
                for (Object id : (Set<?>) value) merged.add((String) id);
                edit.putStringSet(key, merged);
            } else if (!prefs.contains(key)) {
                if (key.startsWith("saved_at_")) edit.putLong(key, (Long) value);
                else edit.putInt(key, ((Long) value).intValue());
            }
        }
        if (!edit.commit()) throw new java.io.IOException("Could not save backup");
    }

    private static boolean allowed(String key) {
        return key.equals(LafzPrefs.SAVED) || key.matches("(saved_at_|due_|stage_)w_[A-Za-z0-9_]+")
                || key.matches("(reviewed_day_|remembered_day_)[0-9]+$");
    }
}
