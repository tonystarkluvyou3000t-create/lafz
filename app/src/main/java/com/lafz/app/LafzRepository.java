package com.lafz.app;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.os.Handler;
import android.os.Looper;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.DigestInputStream;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.zip.GZIPInputStream;

/** Read-only Lafz Lexicon catalog with an independent mutable progress store. */
final class LafzRepository {
    static final String SOURCE_URL = "https://github.com/globalwordnet/english-wordnet";
    static final String LICENSE_URL = "https://creativecommons.org/licenses/by/4.0/";

    private static final int SCHEMA_VERSION = 2;
    private static final String TOPIC_REVISION = "4";
    private static final int SQLITE_BATCH = 850;
    private static final Edition CORE = new Edition("core", "lafz_lexicon_core.db",
            "lexicon/core.dbz", 40_861_696L, 2_830,
            "23e85e65d514209a94ceb0cdc7001e95a40377f75af871f999ce5e29b0ee38a2",
            "ba68af91a5ef56aa02a4ad705486450a6b613ecd494864542bc4ebaa47e1dbaa");
    private static final Edition FULL = new Edition("full", "lafz_lexicon_full.db",
            "lexicon/full.dbz", 361_332_736L, 115_130,
            "b0a1089333435af0ba567facb5e595af728f54429c9e745e030241830b3d4a9d",
            "b4e4d6fb6da22e46eee314e945362aeaae288f8950318984834e052847b4271b");

    private static final ExecutorService BACKGROUND = Executors.newFixedThreadPool(3);
    private static LafzRepository instance;
    private static final String WORD_SELECT = buildWordSelect();
    private static final String WORD_FROM = buildWordFrom();

    interface Listener {
        void onProgress(String message);
        void onReady(int wordCount);
        void onError(String message);
    }

    private final Context appContext;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final LafzProgressStore progressStore;
    private final List<Listener> coreListeners = new ArrayList<>();
    private final List<Listener> fullListeners = new ArrayList<>();
    private volatile SQLiteDatabase coreDatabase;
    private volatile SQLiteDatabase fullDatabase;
    private volatile int coreCount = -1;
    private volatile boolean coreInstalling;
    private volatile boolean fullInstalling;
    private volatile String fullFailure;

    static synchronized LafzRepository get(Context context) {
        if (instance == null) instance = new LafzRepository(context.getApplicationContext());
        return instance;
    }

    private LafzRepository(Context context) {
        this(context, "lafz_progress_v2.db");
    }

    LafzRepository(Context context, String progressDatabaseName) {
        appContext = context;
        progressStore = new LafzProgressStore(context, progressDatabaseName);
    }

    void closeProgressForTests() { progressStore.close(); }

    static void executeBackground(Runnable task) { BACKGROUND.execute(task); }

    synchronized void ensureLoaded(Listener listener) {
        if (coreDatabase != null) {
            int count = count();
            if (listener != null) mainHandler.post(() -> listener.onReady(count));
            ensureFull(null);
            return;
        }
        if (listener != null && !coreListeners.contains(listener)) coreListeners.add(listener);
        if (coreInstalling) return;
        coreInstalling = true;
        executeBackground(() -> {
            try {
                SQLiteDatabase db = database(CORE);
                int total = count(db);
                synchronized (LafzRepository.this) {
                    coreDatabase = db;
                    coreCount = total;
                    coreInstalling = false;
                }
                notifyReady(coreListeners, total);
                ensureFull(null);
            } catch (Exception error) {
                synchronized (LafzRepository.this) { coreInstalling = false; }
                notifyError(coreListeners, message(error));
            }
        });
    }

    synchronized void ensureFull(Listener listener) {
        if (fullDatabase != null) {
            int total = count(fullDatabase);
            if (listener != null) mainHandler.post(() -> listener.onReady(total));
            return;
        }
        if (listener != null && !fullListeners.contains(listener)) fullListeners.add(listener);
        if (fullInstalling) return;
        fullInstalling = true;
        fullFailure = null;
        executeBackground(() -> {
            try {
                SQLiteDatabase db = database(FULL);
                synchronized (LafzRepository.this) {
                    fullDatabase = db;
                    fullInstalling = false;
                    fullFailure = null;
                }
                notifyReady(fullListeners, count(db));
            } catch (Exception error) {
                synchronized (LafzRepository.this) {
                    fullInstalling = false;
                    fullFailure = message(error);
                }
                notifyError(fullListeners, message(error));
            }
        });
    }

    synchronized void removeListener(Listener listener) {
        coreListeners.remove(listener);
        fullListeners.remove(listener);
    }

    private void notifyProgress(List<Listener> listeners, String message) {
        mainHandler.post(() -> {
            List<Listener> copy;
            synchronized (LafzRepository.this) { copy = new ArrayList<>(listeners); }
            for (Listener listener : copy) listener.onProgress(message);
        });
    }

    private void notifyReady(List<Listener> listeners, int total) {
        mainHandler.post(() -> {
            List<Listener> copy;
            synchronized (LafzRepository.this) { copy = new ArrayList<>(listeners); listeners.clear(); }
            for (Listener listener : copy) listener.onReady(total);
        });
    }

    private void notifyError(List<Listener> listeners, String error) {
        mainHandler.post(() -> {
            List<Listener> copy;
            synchronized (LafzRepository.this) { copy = new ArrayList<>(listeners); listeners.clear(); }
            for (Listener listener : copy) listener.onError(error);
        });
    }

    boolean isReady() { return coreDatabase != null; }
    boolean isFullReady() { return fullDatabase != null; }
    String fullFailure() { return fullFailure; }
    int count() { return coreCount >= 0 ? coreCount : (coreDatabase == null ? 0 : count(coreDatabase)); }
    int countFull() { return count(fullDatabase == null ? database(FULL) : fullDatabase); }

    private int count(SQLiteDatabase db) {
        try (Cursor cursor = db.rawQuery("SELECT COUNT(*) FROM words", null)) {
            return cursor.moveToFirst() ? cursor.getInt(0) : 0;
        }
    }

    private SQLiteDatabase database(Edition edition) {
        try { return openDatabase(edition); }
        catch (IOException error) { throw new IllegalStateException(message(error), error); }
    }

    private SQLiteDatabase openDatabase(Edition edition) throws IOException {
        synchronized (edition) {
            SQLiteDatabase existing = edition == CORE ? coreDatabase : fullDatabase;
            if (existing != null && existing.isOpen()) return existing;
            File target = appContext.getDatabasePath(edition.databaseName);
            File parent = target.getParentFile();
            if (parent != null && !parent.exists() && !parent.mkdirs()) throw new IOException("Could not prepare offline dictionary storage.");
            if (!isInstalledDatabaseValid(target, edition)) install(edition, target);
            SQLiteDatabase db;
            try {
                db = SQLiteDatabase.openDatabase(target.getAbsolutePath(), null,
                        SQLiteDatabase.OPEN_READONLY | SQLiteDatabase.NO_LOCALIZED_COLLATORS);
            } catch (RuntimeException error) {
                target.delete();
                throw new IOException("The offline dictionary could not be opened.", error);
            }
            db.setForeignKeyConstraintsEnabled(true);
            if (edition == CORE) coreDatabase = db; else fullDatabase = db;
            return db;
        }
    }

    private boolean isInstalledDatabaseValid(File file, Edition edition) {
        if (!file.isFile() || file.length() != edition.databaseBytes) return false;
        SQLiteDatabase db = null;
        try {
            db = SQLiteDatabase.openDatabase(file.getAbsolutePath(), null,
                    SQLiteDatabase.OPEN_READONLY | SQLiteDatabase.NO_LOCALIZED_COLLATORS);
            try (Cursor cursor = db.rawQuery("SELECT value FROM build_metadata WHERE key='schema_version'", null)) {
                if (!cursor.moveToFirst() || !String.valueOf(SCHEMA_VERSION).equals(cursor.getString(0))) return false;
            }
            try (Cursor cursor = db.rawQuery("SELECT value FROM build_metadata WHERE key='topic_revision'", null)) {
                if (!cursor.moveToFirst() || !TOPIC_REVISION.equals(cursor.getString(0))) return false;
            }
            return count(db) == edition.wordCount;
        } catch (RuntimeException ignored) {
            return false;
        } finally {
            if (db != null) db.close();
        }
    }

    private void install(Edition edition, File target) throws IOException {
        File partial = new File(target.getParentFile(), target.getName() + ".part");
        target.delete();
        partial.delete();
        MessageDigest compressedDigest = sha256();
        MessageDigest databaseDigest = sha256();
        long written = 0;
        byte[] buffer = new byte[64 * 1024];
        notifyProgress(edition == CORE ? coreListeners : fullListeners,
                edition == CORE ? "Preparing the learner dictionary offline" : "Expanding the full dictionary offline");
        try (InputStream asset = new BufferedInputStream(appContext.getAssets().open(edition.assetName), 64 * 1024);
             DigestInputStream verifiedAsset = new DigestInputStream(asset, compressedDigest);
             GZIPInputStream gzip = new GZIPInputStream(verifiedAsset, 64 * 1024);
             DigestOutputStream verifiedFile = new DigestOutputStream(
                     new BufferedOutputStream(new FileOutputStream(partial), 64 * 1024), databaseDigest)) {
            int amount;
            while ((amount = gzip.read(buffer)) != -1) {
                verifiedFile.write(buffer, 0, amount);
                written += amount;
            }
            verifiedFile.flush();
            if (written != edition.databaseBytes || !hex(databaseDigest.digest()).equals(edition.databaseSha256) ||
                    !hex(compressedDigest.digest()).equals(edition.assetSha256)) {
                throw new IOException("The bundled " + edition.id + " dictionary failed its integrity check.");
            }
        } catch (IOException error) {
            partial.delete();
            throw error;
        }
        try (FileOutputStream sync = new FileOutputStream(partial, true)) { sync.getFD().sync(); }
        if (!partial.renameTo(target)) {
            partial.delete();
            throw new IOException("Could not finish installing the offline dictionary.");
        }
        target.setReadable(true, true);
        target.setWritable(false, false);
        SQLiteDatabase check = null;
        try {
            check = SQLiteDatabase.openDatabase(target.getAbsolutePath(), null,
                    SQLiteDatabase.OPEN_READONLY | SQLiteDatabase.NO_LOCALIZED_COLLATORS);
            try (Cursor cursor = check.rawQuery("PRAGMA quick_check", null)) {
                if (!cursor.moveToFirst() || !"ok".equalsIgnoreCase(cursor.getString(0))) {
                    throw new IOException("The installed dictionary did not pass SQLite integrity validation.");
                }
            }
            try (Cursor cursor = check.rawQuery("SELECT COUNT(*) FROM words", null)) {
                if (!cursor.moveToFirst() || cursor.getInt(0) != edition.wordCount) {
                    throw new IOException("The installed dictionary entry count did not match its manifest.");
                }
            }
        } catch (RuntimeException error) {
            target.delete();
            throw new IOException("The installed dictionary failed SQLite validation.", error);
        } finally {
            if (check != null) check.close();
        }
    }

    private static MessageDigest sha256() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    private static String hex(byte[] value) {
        StringBuilder text = new StringBuilder(value.length * 2);
        for (byte item : value) text.append(String.format(Locale.ROOT, "%02x", item & 0xff));
        return text.toString();
    }

    private static String message(Exception error) {
        String value = error.getMessage();
        return value == null || value.isEmpty() ? "The offline dictionary could not be prepared." : value;
    }

    private SQLiteDatabase databaseForLevel(String level) {
        return LearningContent.ALL.equals(level) ? database(FULL) : database(CORE);
    }

    private SQLiteDatabase databaseForId(String id) {
        SQLiteDatabase core = coreDatabase;
        if (core == null) core = database(CORE);
        if (containsId(core, id)) return core;
        SQLiteDatabase full = fullDatabase;
        if (full == null) full = database(FULL);
        return full;
    }

    private boolean containsId(SQLiteDatabase db, String id) {
        try (Cursor cursor = db.rawQuery("SELECT 1 FROM words WHERE word_id=? LIMIT 1", new String[]{id})) {
            return cursor.moveToFirst();
        }
    }

    List<Word> nextWords(Set<String> categories, int limit) {
        return nextWords(categories, limit, Collections.emptySet(), LafzPrefs.level(appContext));
    }

    List<Word> nextWords(Set<String> categories, int limit, Set<String> pending, String level) {
        if (limit <= 0) return new ArrayList<>();
        SQLiteDatabase db = databaseForLevel(level);
        StringBuilder where = new StringBuilder("1=1");
        List<String> args = new ArrayList<>();
        appendLevel(where, args, level);
        appendCategories(where, args, categories);
        appendPending(where, args, pending);
        Set<String> hidden = progressStore.idsWithState("hidden");
        Set<String> seen = progressStore.idsWithState("seen");
        // Select cheap IDs first. Hydrate only the words that will be displayed,
        // even when most of a large dictionary has already been seen.
        List<String> values = new ArrayList<>(args);
        values.add(String.valueOf(Math.max(160, limit * 12)));
        List<String> eligible = new ArrayList<>();
        try (Cursor cursor = db.rawQuery("SELECT w.word_id FROM words w WHERE " + where +
                " ORDER BY RANDOM() LIMIT ?", values.toArray(new String[0]))) {
            while (cursor.moveToNext()) eligible.add(cursor.getString(0));
        }
        List<String> selected = new ArrayList<>();
        for (String id : eligible) {
            if (hidden.contains(id) || seen.contains(id)) continue;
            selected.add(id);
            if (selected.size() == limit) break;
        }
        if (selected.size() == limit) return wordsInOrder(db, selected);
        eligible = new ArrayList<>(queryIds(db, where.toString(), args));
        Collections.shuffle(eligible);
        selected.clear();
        for (String id : eligible) {
            if (hidden.contains(id) || seen.contains(id)) continue;
            selected.add(id);
            if (selected.size() == limit) break;
        }
        if (!selected.isEmpty()) return wordsInOrder(db, selected);
        Set<String> eligibleSeen = new HashSet<>(eligible);
        eligibleSeen.removeAll(hidden);
        eligibleSeen.retainAll(seen);
        progressStore.clearSeen(eligibleSeen);
        for (String id : eligible) {
            if (hidden.contains(id)) continue;
            selected.add(id);
            if (selected.size() == limit) break;
        }
        return wordsInOrder(db, selected);
    }

    private List<Word> queryCandidates(SQLiteDatabase db, String where, List<String> args, int limit) {
        List<String> values = new ArrayList<>(args);
        values.add(String.valueOf(limit));
        // Keep the expensive correlated detail queries outside the random selection.
        try (Cursor cursor = db.rawQuery(WORD_SELECT + WORD_FROM + " WHERE w.word_id IN (" +
                "SELECT w.word_id FROM words w WHERE " + where + " ORDER BY RANDOM() LIMIT ?)",
                values.toArray(new String[0]))) {
            return readWords(cursor);
        }
    }

    private Set<String> queryIds(SQLiteDatabase db, String where, List<String> args) {
        Set<String> result = new HashSet<>();
        try (Cursor cursor = db.rawQuery("SELECT w.word_id FROM words w WHERE " + where, args.toArray(new String[0]))) {
            while (cursor.moveToNext()) result.add(cursor.getString(0));
        }
        return result;
    }

    void markSeen(String id) { progressStore.markSeen(id); }

    Word daily(Set<String> categories, int daySeed) {
        String level = LafzPrefs.level(appContext);
        SQLiteDatabase db = databaseForLevel(level);
        StringBuilder where = new StringBuilder("1=1");
        List<String> args = new ArrayList<>();
        appendLevel(where, args, level);
        appendCategories(where, args, categories);
        Set<String> hidden = progressStore.idsWithState("hidden");
        int total;
        try (Cursor cursor = db.rawQuery("SELECT COUNT(*) FROM words w WHERE " + where, args.toArray(new String[0]))) {
            total = cursor.moveToFirst() ? cursor.getInt(0) : 0;
        }
        if (total <= 0) return null;
        int start = Math.floorMod(daySeed, total);
        for (int offset = 0; offset < total; offset++) {
            List<String> values = new ArrayList<>(args);
            values.add(String.valueOf((start + offset) % total));
            try (Cursor cursor = db.rawQuery(WORD_SELECT + WORD_FROM + " WHERE " + where +
                    " ORDER BY w.lookup_key LIMIT 1 OFFSET ?", values.toArray(new String[0]))) {
                if (cursor.moveToFirst()) {
                    Word word = readWord(cursor);
                    if (!hidden.contains(word.id)) return word;
                }
            }
        }
        return null;
    }

    Word byId(String id) {
        SQLiteDatabase db = databaseForId(id);
        try (Cursor cursor = db.rawQuery(WORD_SELECT + WORD_FROM + " WHERE w.word_id=? LIMIT 1", new String[]{id})) {
            return cursor.moveToFirst() ? readWord(cursor) : null;
        }
    }

    List<Word> byIds(Set<String> ids) {
        if (ids == null || ids.isEmpty()) return new ArrayList<>();
        List<Word> result = new ArrayList<>();
        Set<String> missing = new HashSet<>(ids);
        SQLiteDatabase core = coreDatabase == null ? database(CORE) : coreDatabase;
        for (Word word : queryByIds(core, ids)) { result.add(word); missing.remove(word.id); }
        if (!missing.isEmpty()) {
            SQLiteDatabase full = fullDatabase == null ? database(FULL) : fullDatabase;
            result.addAll(queryByIds(full, missing));
        }
        return result;
    }

    private List<Word> queryByIds(SQLiteDatabase db, Set<String> ids) {
        List<Word> result = new ArrayList<>();
        if (ids == null || ids.isEmpty()) return result;
        List<String> values = new ArrayList<>(ids);
        for (int start = 0; start < values.size(); start += SQLITE_BATCH) {
            int end = Math.min(values.size(), start + SQLITE_BATCH);
            StringBuilder placeholders = new StringBuilder();
            String[] args = new String[end - start];
            for (int i = start; i < end; i++) {
                if (i > start) placeholders.append(',');
                placeholders.append('?'); args[i - start] = values.get(i);
            }
            try (Cursor cursor = db.rawQuery(WORD_SELECT + WORD_FROM + " WHERE w.word_id IN (" + placeholders + ")", args)) {
                result.addAll(readWords(cursor));
            }
        }
        return result;
    }

    List<Word> randomWords(int limit) {
        if (limit <= 0) return new ArrayList<>();
        String level = LafzPrefs.level(appContext);
        SQLiteDatabase db = databaseForLevel(level);
        StringBuilder where = new StringBuilder("1=1");
        List<String> args = new ArrayList<>();
        appendLevel(where, args, level);
        Set<String> hidden = progressStore.idsWithState("hidden");
        List<Word> result = new ArrayList<>();
        for (Word word : queryCandidates(db, where.toString(), args, Math.max(limit * 5, limit + 50))) {
            if (!hidden.contains(word.id)) result.add(word);
            if (result.size() >= limit) break;
        }
        return result;
    }

    List<Word> search(String input, int limit) {
        String query = input == null ? "" : input.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
        if (query.length() < 2 || limit <= 0) return new ArrayList<>();
        SQLiteDatabase db = database(FULL);
        String escaped = escapeLike(query);
        String normalized = query.replace(' ', '_');
        List<String> orderedIds = new ArrayList<>();
        Set<String> used = new HashSet<>();
        collectPrefixIds(db, query, limit, orderedIds, used);
        if (!normalized.equals(query) && orderedIds.size() < limit) collectPrefixIds(db, normalized, limit, orderedIds, used);
        collectFormIds(db, query, limit, orderedIds, used);
        if (!normalized.equals(query)) collectFormIds(db, normalized, limit, orderedIds, used);

        List<Word> result = wordsInOrder(db, orderedIds);
        if (result.size() >= limit || query.length() < 3) return result.subList(0, Math.min(limit, result.size()));

        // Search definition text in one table pass. Avoid a correlated definition scan
        // for every dictionary word; that made broad definition search too expensive on device.
        List<String> definitionIds = new ArrayList<>();
        String pattern = "%" + escaped + "%";
        try (Cursor cursor = db.rawQuery("SELECT s.word_id FROM definitions d JOIN senses s ON s.sense_id=d.sense_id " +
                "WHERE d.definition_text LIKE ? ESCAPE '\\' GROUP BY s.word_id " +
                "ORDER BY MAX(d.quality_score) DESC, MIN(s.sense_order), s.word_id LIMIT ?",
                new String[]{pattern, String.valueOf(Math.max(limit * 4, limit))})) {
            while (cursor.moveToNext()) {
                String id = cursor.getString(0);
                if (used.add(id)) definitionIds.add(id);
                if (orderedIds.size() + definitionIds.size() >= limit) break;
            }
        }
        List<Word> definitionWords = wordsInOrder(db, definitionIds);
        result.addAll(definitionWords);
        return result.size() > limit ? result.subList(0, limit) : result;
    }

    private void collectPrefixIds(SQLiteDatabase db, String key, int limit, List<String> ids, Set<String> used) {
        if (ids.size() >= limit) return;
        int room = limit - ids.size();
        String upper = key + "\uffff";
        try (Cursor cursor = db.rawQuery("SELECT word_id FROM words WHERE lookup_key>=? AND lookup_key<? " +
                "ORDER BY CASE WHEN lookup_key=? THEN 0 ELSE 1 END, " +
                "COALESCE((SELECT rank FROM frequency_metadata fm WHERE fm.word_id=words.word_id AND fm.ranking_scheme='NGSL 1.2'),2147483647), lookup_key LIMIT ?",
                new String[]{key, upper, key, String.valueOf(room)})) {
            while (cursor.moveToNext()) {
                String id = cursor.getString(0);
                if (used.add(id)) ids.add(id);
            }
        }
    }

    private void collectFormIds(SQLiteDatabase db, String key, int limit, List<String> ids, Set<String> used) {
        if (ids.size() >= limit) return;
        int room = limit - ids.size();
        try (Cursor cursor = db.rawQuery("SELECT word_id FROM word_forms WHERE normalized_form>=? AND normalized_form<? " +
                "ORDER BY normalized_form LIMIT ?", new String[]{key, key + "\uffff", String.valueOf(room)})) {
            while (cursor.moveToNext()) {
                String id = cursor.getString(0);
                if (used.add(id)) ids.add(id);
            }
        }
    }

    private List<Word> wordsInOrder(SQLiteDatabase db, List<String> orderedIds) {
        if (orderedIds.isEmpty()) return new ArrayList<>();
        Map<String, Word> byId = new HashMap<>();
        for (Word word : queryByIds(db, new HashSet<>(orderedIds))) byId.put(word.id, word);
        List<Word> result = new ArrayList<>();
        for (String id : orderedIds) if (byId.containsKey(id)) result.add(byId.get(id));
        return result;
    }

    List<LexiconSense> senses(String wordId) {
        SQLiteDatabase db = databaseForId(wordId);
        Map<String, LexiconSense> grouped = new LinkedHashMap<>();
        String definitionsSql = "SELECT s.sense_id,s.part_of_speech,s.sense_order,d.definition_text,ds.name,ds.attribution,ds.license,ds.license_url,d.definition_kind " +
                "FROM senses s JOIN definitions d ON d.sense_id=s.sense_id JOIN data_sources ds ON ds.source_id=d.source_id " +
                "WHERE s.word_id=? ORDER BY s.sense_order,s.part_of_speech,d.is_preferred DESC,d.display_rank,d.quality_score DESC";
        try (Cursor cursor = db.rawQuery(definitionsSql, new String[]{wordId})) {
            while (cursor.moveToNext()) {
                String id = cursor.getString(0);
                LexiconSense sense = grouped.get(id);
                if (sense == null) {
                    sense = new LexiconSense(id, cursor.getString(1), cursor.getInt(2));
                    grouped.put(id, sense);
                }
                sense.definitions.add(new LexiconDefinition(cursor.getString(3), cursor.getString(4), cursor.getString(5),
                        cursor.getString(6), cursor.getString(7), cursor.getString(8)));
            }
        }
        String examplesSql = "SELECT e.sense_id,e.example_text,ds.name,ds.license,ds.license_url,e.validation_status,e.sense_match_confidence " +
                "FROM examples e JOIN senses s ON s.sense_id=e.sense_id JOIN data_sources ds ON ds.source_id=e.source_id " +
                "WHERE s.word_id=? AND e.validation_status IN ('verified_word','verified_inflection') " +
                "ORDER BY s.sense_order,e.quality_score DESC,e.example_id";
        Map<String, Integer> exampleCounts = new HashMap<>();
        try (Cursor cursor = db.rawQuery(examplesSql, new String[]{wordId})) {
            while (cursor.moveToNext()) {
                LexiconSense sense = grouped.get(cursor.getString(0));
                if (sense == null) continue;
                String exampleGroup = sense.id + ":" + cursor.getString(2);
                int count = exampleCounts.getOrDefault(exampleGroup, 0);
                if (count >= 2) continue;
                Double confidence = cursor.isNull(6) ? null : cursor.getDouble(6);
                sense.examples.add(new LexiconExample(cursor.getString(1), cursor.getString(2), cursor.getString(3),
                        cursor.getString(4), cursor.getString(5), confidence, true));
                exampleCounts.put(exampleGroup, count + 1);
            }
        }
        try (Cursor cursor = db.rawQuery("SELECT sl.sense_id,sl.label FROM sense_labels sl JOIN senses s ON s.sense_id=sl.sense_id WHERE s.word_id=? ORDER BY sl.confidence DESC", new String[]{wordId})) {
            while (cursor.moveToNext()) {
                LexiconSense sense = grouped.get(cursor.getString(0));
                if (sense != null && !sense.labels.contains(cursor.getString(1))) sense.labels.add(cursor.getString(1));
            }
        }
        return new ArrayList<>(grouped.values());
    }

    List<LexiconExample> wordExamples(String wordId, int limit) {
        SQLiteDatabase db = databaseForId(wordId);
        List<LexiconExample> result = new ArrayList<>();
        try (Cursor cursor = db.rawQuery("SELECT we.example_text,ds.name,ds.license,ds.license_url,we.validation_status,we.sense_match_status " +
                "FROM word_examples we JOIN data_sources ds ON ds.source_id=we.source_id WHERE we.word_id=? " +
                "ORDER BY we.quality_score DESC LIMIT ?", new String[]{wordId, String.valueOf(Math.max(0, limit))})) {
            while (cursor.moveToNext()) result.add(new LexiconExample(cursor.getString(0), cursor.getString(1),
                    cursor.getString(2), cursor.getString(3), cursor.getString(4), null, false));
        }
        return result;
    }

    List<Word> relatedWords(String wordId, int limit) {
        SQLiteDatabase db = databaseForId(wordId);
        List<Word> result = new ArrayList<>();
        try (Cursor cursor = db.rawQuery(WORD_SELECT + WORD_FROM + " WHERE w.word_id IN " +
                "(SELECT target_word_id FROM word_relations WHERE source_word_id=? AND relation_type='synonym' ORDER BY confidence DESC LIMIT ?)",
                new String[]{wordId, String.valueOf(Math.max(0, limit))})) {
            result.addAll(readWords(cursor));
        }
        return result;
    }

    List<Word> quizDistractors(String wordId, int limit) {
        SQLiteDatabase db = databaseForId(wordId);
        List<String> ids = new ArrayList<>();
        try (Cursor cursor = db.rawQuery("SELECT distractor_word_id FROM quiz_distractors WHERE word_id=? ORDER BY rank_order LIMIT ?",
                new String[]{wordId, String.valueOf(Math.max(0, limit))})) {
            while (cursor.moveToNext()) ids.add(cursor.getString(0));
        }
        Map<String, Word> words = new HashMap<>();
        for (Word word : queryByIds(db, new HashSet<>(ids))) words.put(word.id, word);
        List<Word> result = new ArrayList<>();
        for (String id : ids) if (words.containsKey(id)) result.add(words.get(id));
        return result;
    }

    List<LexiconSource> sources() {
        SQLiteDatabase db = fullDatabase == null ? (coreDatabase == null ? database(CORE) : coreDatabase) : fullDatabase;
        List<LexiconSource> result = new ArrayList<>();
        try (Cursor cursor = db.rawQuery("SELECT name,publisher,version,license,license_url,attribution,redistribution FROM data_sources " +
                "WHERE source_id IN (SELECT source_id FROM senses UNION SELECT source_id FROM word_examples UNION " +
                "SELECT source_id FROM pronunciations UNION SELECT source_id FROM frequency_metadata UNION " +
                "SELECT source_id FROM word_categories) ORDER BY name", null)) {
            while (cursor.moveToNext()) result.add(new LexiconSource(cursor.getString(0), cursor.getString(1),
                    cursor.getString(2), cursor.getString(3), cursor.getString(4), cursor.getString(5), cursor.getString(6)));
        }
        return result;
    }

    // Commit before callers select another batch; concurrent worker tasks can run out of order.
    void hide(String id) { progressStore.hide(id); }

    Set<String> hiddenIds() { return progressStore.idsWithState("hidden"); }
    void restoreHidden(String id) { progressStore.restoreHidden(id); }

    private List<Word> readWords(Cursor cursor) {
        List<Word> words = new ArrayList<>();
        while (cursor.moveToNext()) words.add(readWord(cursor));
        return words;
    }

    private Word readWord(Cursor cursor) {
        String source = empty(cursor.getString(9), "Lafz Lexicon");
        String publisher = empty(cursor.getString(12), source);
        String version = empty(cursor.getString(13), "");
        int difficulty = cursor.getInt(14);
        String evidence = empty(cursor.getString(16), "");
        String note = evidence.startsWith("NGSL rank") ? "NGSL usefulness evidence; not an official CEFR label." :
                "Estimated lexical complexity; heuristic and not an official CEFR label.";
        return new Word(cursor.getString(0), cursor.getString(1), empty(cursor.getString(2), ""),
                empty(cursor.getString(3), ""), empty(cursor.getString(4), "word"),
                empty(cursor.getString(5), "Unclassified"), empty(cursor.getString(6), ""),
                empty(cursor.getString(7), ""), empty(cursor.getString(8), ""),
                publisher + (version.isEmpty() ? "" : " · " + version), note, source,
                empty(cursor.getString(10), ""), empty(cursor.getString(11), ""), difficulty,
                cursor.getDouble(15), evidence);
    }

    private static String empty(String value, String fallback) { return value == null || value.isEmpty() ? fallback : value; }

    private static String buildWordSelect() {
        return "SELECT w.word_id,w.spelling," +
                "(SELECT p.transcription FROM pronunciations p WHERE p.word_id=w.word_id ORDER BY CASE WHEN p.phonetic_alphabet='IPA' THEN 0 ELSE 1 END,p.variant_order LIMIT 1)," +
                "(SELECT p.phonetic_alphabet FROM pronunciations p WHERE p.word_id=w.word_id ORDER BY CASE WHEN p.phonetic_alphabet='IPA' THEN 0 ELSE 1 END,p.variant_order LIMIT 1)," +
                "primary_sense.part_of_speech," +
                "COALESCE((SELECT wc.category FROM word_categories wc WHERE wc.word_id=w.word_id ORDER BY wc.confidence DESC,wc.category LIMIT 1),'Unclassified')," +
                "primary_definition.definition_text," +
                "(SELECT e.example_text FROM examples e WHERE e.sense_id=primary_definition.sense_id AND e.source_id=primary_definition.source_id AND e.validation_status IN ('verified_word','verified_inflection') ORDER BY e.quality_score DESC,e.example_id LIMIT 1)," +
                "(SELECT target.spelling FROM word_relations r JOIN words target ON target.word_id=r.target_word_id WHERE r.source_word_id=w.word_id AND r.sense_id=primary_sense.sense_id AND r.relation_type='synonym' ORDER BY r.confidence DESC,r.relation_id LIMIT 1)," +
                "primary_source.name,primary_source.download_url,primary_source.license_url," +
                "primary_source.publisher,primary_source.version," +
                "COALESCE((SELECT fm.difficulty_estimate FROM frequency_metadata fm WHERE fm.word_id=w.word_id ORDER BY CASE WHEN fm.ranking_scheme='NGSL 1.2' THEN 0 ELSE 1 END,fm.confidence DESC LIMIT 1),3)," +
                "COALESCE((SELECT fm.confidence FROM frequency_metadata fm WHERE fm.word_id=w.word_id ORDER BY CASE WHEN fm.ranking_scheme='NGSL 1.2' THEN 0 ELSE 1 END,fm.confidence DESC LIMIT 1),0.0)," +
                "(SELECT fm.evidence FROM frequency_metadata fm WHERE fm.word_id=w.word_id ORDER BY CASE WHEN fm.ranking_scheme='NGSL 1.2' THEN 0 ELSE 1 END,fm.confidence DESC LIMIT 1)";
    }

    private static String buildWordFrom() {
        // Demote explicitly restricted usage, then apply curated everyday anchors.
        // Learner glosses break remaining ties. All card fields follow the same sense.
        // Stable anchors below: head (body), bank (money), run (movement).
        // Keep this order in sync with topics.py; verify_display_content.py audits both bundles.
        return " FROM words w LEFT JOIN definitions primary_definition ON primary_definition.definition_id=(" +
                "SELECT d.definition_id FROM senses s JOIN definitions d ON d.sense_id=s.sense_id WHERE s.word_id=w.word_id " +
                "ORDER BY CASE WHEN EXISTS(SELECT 1 FROM sense_labels usage WHERE usage.sense_id=s.sense_id AND usage.label_type<>'topic' AND lower(usage.label) IN ('slang','vulgar','offensive','archaic','obsolete','dated','rare','historical','nautical','grammar','linguistics','computing','mathematics','medicine','anatomy','chemistry','physics','baseball','cricket','theatre')) THEN 1 ELSE 0 END,CASE WHEN s.sense_id IN ('s_70957dc2eabb9ef082c16017','s_73b5e9d6b14bcaf5a0181df9','s_5ec200a996fe9865e9e22a3d') THEN 0 ELSE 1 END," +
                "CASE WHEN d.definition_kind='learner' THEN 0 ELSE 1 END,s.sense_order,s.part_of_speech," +
                "d.is_preferred DESC,d.display_rank,d.quality_score DESC,d.definition_id LIMIT 1) " +
                "LEFT JOIN senses primary_sense ON primary_sense.sense_id=primary_definition.sense_id " +
                "LEFT JOIN data_sources primary_source ON primary_source.source_id=primary_definition.source_id";
    }

    private static void appendLevel(StringBuilder where, List<String> args, String level) {
        if (LearningContent.ALL.equals(level)) return;
        // Scan the ranking once instead of repeating it for every dictionary word.
        where.append(" AND w.word_id IN (SELECT fm.word_id FROM frequency_metadata fm WHERE fm.ranking_scheme='NGSL 1.2' AND fm.usefulness_tier=?)");
        args.add(LearningContent.STRETCH.equals(level) ? "Stretch" : "Everyday");
    }

    private static void appendCategories(StringBuilder where, List<String> args, Set<String> categories) {
        if (categories == null || categories.isEmpty()) return;
        Set<String> assigned = new HashSet<>(categories);
        boolean unclassified = assigned.remove("Unclassified");
        List<String> labels = new ArrayList<>(assigned);
        if (labels.isEmpty() && !unclassified) return;
        where.append(" AND (");
        boolean hasTerm = false;
        if (!labels.isEmpty()) {
            where.append("EXISTS(SELECT 1 FROM word_categories wc WHERE wc.word_id=w.word_id AND wc.category IN (");
            for (int i = 0; i < labels.size(); i++) {
                if (i > 0) where.append(',');
                where.append('?'); args.add(labels.get(i));
            }
            where.append("))"); hasTerm = true;
        }
        if (unclassified) {
            if (hasTerm) where.append(" OR ");
            where.append("NOT EXISTS(SELECT 1 FROM word_categories wc WHERE wc.word_id=w.word_id)");
        }
        where.append(')');
    }

    private static void appendPending(StringBuilder where, List<String> args, Set<String> pending) {
        if (pending == null || pending.isEmpty()) return;
        where.append(" AND w.word_id NOT IN (");
        int i = 0;
        for (String id : pending) {
            if (i++ > 0) where.append(',');
            where.append('?'); args.add(id);
        }
        where.append(')');
    }

    private static String escapeLike(String input) {
        return input.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    private static final class Edition {
        final String id, databaseName, assetName, databaseSha256, assetSha256;
        final long databaseBytes;
        final int wordCount;
        Edition(String id, String databaseName, String assetName, long databaseBytes, int wordCount,
                String databaseSha256, String assetSha256) {
            this.id = id; this.databaseName = databaseName; this.assetName = assetName;
            this.databaseBytes = databaseBytes; this.wordCount = wordCount;
            this.databaseSha256 = databaseSha256; this.assetSha256 = assetSha256;
        }
    }
}
