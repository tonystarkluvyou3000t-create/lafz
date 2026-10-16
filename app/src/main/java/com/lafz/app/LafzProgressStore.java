package com.lafz.app;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.util.HashSet;
import java.util.Set;

/** Mutable exposure state is kept apart from the bundled, read-only lexicon. */
final class LafzProgressStore extends SQLiteOpenHelper {
    private static final String NAME = "lafz_progress_v2.db";
    private static final int VERSION = 1;

    LafzProgressStore(Context context) {
        this(context, NAME);
    }

    LafzProgressStore(Context context, String databaseName) {
        super(context.getApplicationContext(), databaseName, null, VERSION);
        setWriteAheadLoggingEnabled(true);
    }

    @Override public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE word_state (word_id TEXT PRIMARY KEY NOT NULL, seen INTEGER NOT NULL DEFAULT 0, hidden INTEGER NOT NULL DEFAULT 0)");
        db.execSQL("CREATE INDEX word_state_seen ON word_state(seen)");
        db.execSQL("CREATE INDEX word_state_hidden ON word_state(hidden)");
    }

    @Override public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) { }

    synchronized Set<String> idsWithState(String column) {
        if (!"seen".equals(column) && !"hidden".equals(column)) throw new IllegalArgumentException("Unknown progress field.");
        Set<String> ids = new HashSet<>();
        try (Cursor cursor = getReadableDatabase().rawQuery("SELECT word_id FROM word_state WHERE " + column + "=1", null)) {
            while (cursor.moveToNext()) ids.add(cursor.getString(0));
        }
        return ids;
    }

    synchronized void markSeen(String id) { setFlag(id, "seen"); }
    synchronized void hide(String id) { setFlag(id, "hidden"); }

    synchronized void restoreHidden(String id) {
        ContentValues value = new ContentValues(); value.put("hidden", 0); value.put("seen", 0);
        getWritableDatabase().update("word_state", value, "word_id=?", new String[]{id});
    }

    private void setFlag(String id, String flag) {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            ContentValues empty = new ContentValues(); empty.put("word_id", id);
            db.insertWithOnConflict("word_state", null, empty, SQLiteDatabase.CONFLICT_IGNORE);
            ContentValues value = new ContentValues(); value.put(flag, 1);
            db.update("word_state", value, "word_id=?", new String[]{id});
            db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
    }

    synchronized void clearSeen(Set<String> ids) {
        if (ids == null || ids.isEmpty()) return;
        SQLiteDatabase db = getWritableDatabase();
        java.util.List<String> values = new java.util.ArrayList<>(ids);
        db.beginTransaction();
        try {
            for (int start = 0; start < values.size(); start += 850) {
                int end = Math.min(values.size(), start + 850);
                StringBuilder sql = new StringBuilder("UPDATE word_state SET seen=0 WHERE seen=1 AND word_id IN (");
                String[] args = new String[end - start];
                for (int i = start; i < end; i++) {
                    if (i > start) sql.append(',');
                    sql.append('?'); args[i - start] = values.get(i);
                }
                sql.append(')');
                db.execSQL(sql.toString(), args);
            }
            db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
    }
}
