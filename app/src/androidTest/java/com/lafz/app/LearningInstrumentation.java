package com.lafz.app;

import android.app.Instrumentation;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Debug;
import android.os.SystemClock;

import java.io.File;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Device validation against the packaged Core and Full lexicon databases. */
public final class LearningInstrumentation extends Instrumentation {
    @Override public void onCreate(Bundle arguments) { super.onCreate(arguments); start(); }

    @Override public void onStart() {
        Bundle results = new Bundle();
        LafzRepository repository = null;
        Context context = null;
        String testProgress = "lafz_progress_instrumentation.db";
        try {
            Context base = getTargetContext();
            context = new ContextWrapper(base) {
                @Override public SharedPreferences getSharedPreferences(String name, int mode) {
                    return super.getSharedPreferences("lexicon_tests_" + name, mode);
                }
            };
            context.deleteDatabase(testProgress);
            repository = new LafzRepository(context, testProgress);
            long coreStart = SystemClock.elapsedRealtime();
            awaitCore(repository);
            long coreMillis = SystemClock.elapsedRealtime() - coreStart;
            check(repository.count() == 2_830, "the installed Core database has the manifest entry count");

            long fullStart = SystemClock.elapsedRealtime();
            awaitFull(repository);
            long fullMillis = SystemClock.elapsedRealtime() - fullStart;
            long firstFeedStart = SystemClock.elapsedRealtime();
            repeatedLaunch(repository, context);
            long firstFeedMillis = SystemClock.elapsedRealtime() - firstFeedStart;

            long searchStart = SystemClock.elapsedRealtimeNanos();
            List<Word> found = repository.search("apple", 20);
            long searchMicros = (SystemClock.elapsedRealtimeNanos() - searchStart) / 1_000;
            Word apple = null;
            for (Word word : found) if ("apple".equals(word.spelling)) { apple = word; break; }
            check(apple != null, "Full dictionary search returns the real apple entry");
            check(apple.id.startsWith("w_"), "new lexical identifiers are stable word IDs");
            check(!repository.senses(apple.id).isEmpty(), "apple has source-attributed sense records");
            check(repository.senses(apple.id).get(0).definitions.get(0).source.length() > 0,
                    "source definitions retain dataset attribution");

            List<Word> photoMatches = repository.search("photosynthesis", 20);
            check(!photoMatches.isEmpty() && "photosynthesis".equals(photoMatches.get(0).spelling),
                    "Full dictionary search finds a specialist entry");
            Word photo = repository.byId(photoMatches.get(0).id);
            check("Science".equals(photo.category), "photosynthesis retains its Science topic");
            check(repository.countFull() == 115_130, "Full database remains complete and independently available");
            sensePairing(repository);

            dailyRefresh(repository, context);

            List<Word> everyday = repository.nextWords(Collections.emptySet(), 12, Collections.emptySet(), LearningContent.EVERYDAY);
            check(!everyday.isEmpty(), "Everyday discovery uses ranked Core vocabulary");
            for (Word word : everyday) check(word.id.startsWith("w_"), "Core discovery uses the new stable identifiers");
            check(LafzWords.CATEGORIES.size() == 27, "the expanded topic catalog is available");
            for (String topic : LafzWords.CATEGORIES) {
                List<Word> themed = repository.nextWords(Collections.singleton(topic), 3,
                        Collections.emptySet(), LearningContent.ALL);
                check(!themed.isEmpty(), topic + " contains words in Full dictionary");
                for (Word word : themed) check(topic.equals(word.category),
                        "topic filtering matches the displayed meaning for " + topic);
            }
            for (Word word : everyday) check(!"Unclassified".equals(word.category),
                    "Everyday words have a topic");

            Word exposed = everyday.get(0);
            repository.markSeen(exposed.id);
            Set<String> excluded = new HashSet<>(); excluded.add(exposed.id);
            List<Word> afterSeen = repository.nextWords(Collections.emptySet(), 40, excluded, LearningContent.EVERYDAY);
            for (Word word : afterSeen) check(!word.id.equals(exposed.id), "exposure state is stored separately from dictionary data");
            if (everyday.size() > 1) {
                Word hidden = everyday.get(1);
                repository.hide(hidden.id);
                List<Word> afterHide = repository.nextWords(Collections.emptySet(), 80, excluded, LearningContent.EVERYDAY);
                for (Word word : afterHide) check(!word.id.equals(hidden.id), "hidden progress state excludes entries from discovery");
            }

            preferences(context);
            activityDayRefresh();
            File coreFile = base.getDatabasePath("lafz_lexicon_core.db");
            File fullFile = base.getDatabasePath("lafz_lexicon_full.db");
            results.putString("stream", "PASS: practice tab and Android saved-state restoration, validated backup merge, Undo schedule preservation, 200% text at 360x640, daily rollover on resume and while open, widget content, local midnight/DST scheduling, packaged Core/Full SQLite integrity, real entry lookup, source definitions, themes, ranked discovery, and independent progress.\n" +
                    "DEVICE_METRICS: core_install_ms=" + coreMillis + ", full_install_ms=" + fullMillis +
                    ", repeated_launch_and_feed_ms=" + firstFeedMillis +
                    ", full_apple_search_us=" + searchMicros + ", pss_kb=" + Debug.getPss() +
                    ", core_database_bytes=" + coreFile.length() + ", full_database_bytes=" + fullFile.length() + "\n");
            finish(-1, results);
        } catch (Throwable failure) {
            results.putString("stream", "FAIL: " + android.util.Log.getStackTraceString(failure));
            finish(0, results);
        } finally {
            if (repository != null) repository.closeProgressForTests();
            if (context != null) context.deleteDatabase(testProgress);
        }
    }

    private void sensePairing(LafzRepository repository) {
        Word member = null;
        for (Word word : repository.search("member", 12))
            if (word.spelling.equals("member")) member = word;
        check(member != null, "member is still accessible");
        check(member.meaning.equals("A member is someone belonging to a group or organization."),
                "member opens with its learner meaning");
        check(member.example.equals("I am a member of Simple English Wikipedia."),
                "member pairs the group meaning with the group example");
        check(member.pronunciationDisplay().startsWith("/") && !member.pronunciationDisplay().startsWith("//"),
                "IPA slashes appear once");
        check(repository.senses(member.id).stream().anyMatch(sense -> sense.definitions.stream()
                .anyMatch(definition -> definition.text.equals("the male sex organ"))),
                "other meanings remain available");
        for (String spelling : Arrays.asList("member", "bank", "lead", "wait", "run", "apple")) {
            for (Word word : repository.search(spelling, 12)) {
                if (!word.spelling.equals(spelling)) continue;
                List<LexiconSense> senses = repository.senses(word.id);
                check(senses.stream().anyMatch(sense -> sense.partOfSpeech.equals(word.partOfSpeech) &&
                        sense.definitions.stream().anyMatch(definition -> definition.text.equals(word.meaning) &&
                                definition.source.equals(word.source)) &&
                        (word.example.isEmpty() || sense.examples.stream().anyMatch(example ->
                                example.text.equals(word.example) && example.source.equals(word.source)))),
                        spelling + " uses one meaning and source for definition, part of speech, and example");
            }
        }
    }

    private void repeatedLaunch(LafzRepository repository, Context context) throws Exception {
        java.lang.reflect.Field core = LafzRepository.class.getDeclaredField("coreDatabase");
        java.lang.reflect.Field full = LafzRepository.class.getDeclaredField("fullDatabase");
        core.setAccessible(true); full.setAccessible(true);
        Object coreConnection = core.get(repository);
        Object fullConnection = full.get(repository);
        File coreFile = context.getDatabasePath("lafz_lexicon_core.db");
        File fullFile = context.getDatabasePath("lafz_lexicon_full.db");
        long coreModified = coreFile.lastModified(), fullModified = fullFile.lastModified();
        for (String level : Arrays.asList(LearningContent.EVERYDAY, LearningContent.ALL)) {
            long started = SystemClock.elapsedRealtime();
            List<Word> batch = repository.nextWords(Collections.emptySet(), 7, Collections.emptySet(), level);
            long elapsed = SystemClock.elapsedRealtime() - started;
            check(elapsed < 1450, "first " + level + " feed batch fits within the launch animation (" + elapsed + " ms)");
            check(batch.size() == 7, "startup hydrates a complete first feed batch");
            Set<String> ids = new HashSet<>();
            for (Word word : batch) check(ids.add(word.id), "first feed has no duplicate entries");
        }
        check(coreConnection == core.get(repository) && fullConnection == full.get(repository),
                "queries reuse open dictionary connections");
        LafzRepository reopened = new LafzRepository(context, "lafz_restart_instrumentation.db");
        try {
            awaitCore(reopened);
            awaitFull(reopened);
            check(reopened.nextWords(Collections.emptySet(), 7, Collections.emptySet(),
                    LearningContent.EVERYDAY).size() == 7, "a fresh repository can read the installed dictionary");
            check(coreModified == coreFile.lastModified() && fullModified == fullFile.lastModified(),
                    "repeated launch does not reinstall either dictionary");
        } finally {
            reopened.closeProgressForTests();
            context.deleteDatabase("lafz_restart_instrumentation.db");
        }
    }

    private void dailyRefresh(LafzRepository repository, Context context) {
        int today = LafzPrefs.today();
        Word current = repository.daily(Collections.emptySet(), today);
        Word tomorrow = repository.daily(Collections.emptySet(), today + 1);
        check(current != null && tomorrow != null && !current.id.equals(tomorrow.id),
                "daily selection advances on consecutive dates");
        check(current.id.equals(repository.daily(Collections.emptySet(), today).id),
                "daily selection is stable within a date");
        for (String[] boundary : new String[][] {
                {"2026-10-14T12:00:00+05:30[Asia/Kolkata]", "2026-10-15T00:00:00+05:30[Asia/Kolkata]"},
                {"2026-03-08T00:00:00-05:00[America/New_York]", "2026-03-09T00:00:00-04:00[America/New_York]"},
                {"2026-11-01T00:00:00-04:00[America/New_York]", "2026-11-02T00:00:00-05:00[America/New_York]"}
        }) {
            java.time.ZonedDateTime now = java.time.ZonedDateTime.parse(boundary[0]);
            long expected = java.time.ZonedDateTime.parse(boundary[1]).toInstant().toEpochMilli();
            check(DailyWordRefreshReceiver.nextMidnight(now.toInstant().toEpochMilli(), now.getZone()) == expected,
                    "refresh uses local midnight across daylight saving transitions");
        }
        runOnMainSync(() -> {
            android.view.View widget = WordWidgetProvider.views(context, 9876, current).apply(context, null);
            WordWidgetProvider.views(context, 9876, tomorrow).reapply(context, widget);
            check(((android.widget.TextView) widget.findViewById(R.id.widget_word)).getText().toString().equals(tomorrow.spelling),
                    "widget replaces yesterday's word");
            check(((android.widget.TextView) widget.findViewById(R.id.widget_meaning)).getText().toString().equals(tomorrow.meaning),
                    "widget replaces yesterday's meaning");
        });
    }

    private void activityDayRefresh() throws Exception {
        MainActivity activity = (MainActivity) startActivitySync(new android.content.Intent(getTargetContext(), MainActivity.class)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK));
        try {
            awaitCore(LafzRepository.get(activity));
            long launchDeadline = SystemClock.elapsedRealtime() + 8000;
            boolean[] hasWord = {false};
            do {
                runOnMainSync(() -> hasWord[0] = findText(activity.getWindow().getDecorView(), "Save") != null &&
                        findText(activity.getWindow().getDecorView(), "Opening the word library") == null);
                if (!hasWord[0]) SystemClock.sleep(50);
            } while (!hasWord[0] && SystemClock.elapsedRealtime() < launchDeadline);
            check(hasWord[0], "launch displays a readable word without remaining on the library preparation screen");
            java.lang.reflect.Field launching = MainActivity.class.getDeclaredField("launching");
            java.lang.reflect.Field explore = MainActivity.class.getDeclaredField("exploreMode");
            java.lang.reflect.Field day = MainActivity.class.getDeclaredField("displayedDay");
            java.lang.reflect.Field boundary = MainActivity.class.getDeclaredField("dayBoundary");
            java.lang.reflect.Method render = MainActivity.class.getDeclaredMethod("render");
            for (java.lang.reflect.Field field : new java.lang.reflect.Field[]{launching, explore, day, boundary}) field.setAccessible(true);
            render.setAccessible(true);
            runOnMainSync(() -> {
                try {
                    launching.setBoolean(activity, false);
                    explore.setBoolean(activity, false);
                    render.invoke(activity);
                    Word word = LafzRepository.get(activity).daily(LafzPrefs.categories(activity), LafzPrefs.today());
                    check(word != null, "Today has a word for the user's current mix");
                    android.widget.TextView title = findText(activity.getWindow().getDecorView(), word.spelling);
                    check(title != null, "Today renders the selected daily word");
                    title.setText("STALE DAILY WORD");
                    day.setInt(activity, LafzPrefs.today() - 1);
                    callActivityOnPause(activity);
                    callActivityOnResume(activity);
                    check(findText(activity.getWindow().getDecorView(), word.spelling) != null,
                            "resuming after a date change replaces stale Today content");
                    title = findText(activity.getWindow().getDecorView(), word.spelling);
                    title.setText("STALE DAILY WORD");
                    day.setInt(activity, LafzPrefs.today() - 1);
                    ((Runnable) boundary.get(activity)).run();
                    check(findText(activity.getWindow().getDecorView(), word.spelling) != null,
                            "the foreground midnight callback replaces stale Today content");
                    practiceState(activity, render);
                    compactReading(activity);
                } catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
            });
        } finally {
            runOnMainSync(activity::finish);
            waitForIdleSync();
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void practiceState(MainActivity activity, java.lang.reflect.Method render) throws ReflectiveOperationException {
        java.lang.reflect.Field tab = MainActivity.class.getDeclaredField("currentTab"); tab.setAccessible(true);
        Object reviewTab = Enum.valueOf((Class<Enum>) tab.getType(), "REVIEW");
        java.lang.reflect.Method startQuiz = MainActivity.class.getDeclaredMethod("startQuiz", classForQuiz(), boolean.class);
        startQuiz.setAccessible(true); tab.set(activity, reviewTab);
        startQuiz.invoke(activity, Enum.valueOf((Class<Enum>) classForQuiz(), "GENERAL"), false);
        java.lang.reflect.Field inputField = MainActivity.class.getDeclaredField("quizInput"); inputField.setAccessible(true);
        android.widget.EditText typed = (android.widget.EditText) inputField.get(activity);
        check(typed != null, "typed quiz opens"); typed.setText("unfinished answer");
        findText(activity.getWindow().getDecorView(), "Saved").performClick();
        findText(activity.getWindow().getDecorView(), "Practice").performClick();
        check(((android.widget.EditText) inputField.get(activity)).getText().toString().equals("unfinished answer"),
                "switching tabs preserves typed answer");
        Bundle saved = new Bundle();
        java.lang.reflect.Method save = MainActivity.class.getDeclaredMethod("onSaveInstanceState", Bundle.class); save.setAccessible(true);
        save.invoke(activity, saved);
        android.os.Parcel parcel = android.os.Parcel.obtain(); parcel.writeBundle(saved); parcel.setDataPosition(0);
        Bundle restored = parcel.readBundle(MainActivity.class.getClassLoader()); parcel.recycle();
        java.lang.reflect.Method reset = MainActivity.class.getDeclaredMethod("resetQuiz"); reset.setAccessible(true); reset.invoke(activity);
        java.lang.reflect.Method restore = MainActivity.class.getDeclaredMethod("restorePractice", Bundle.class); restore.setAccessible(true);
        restore.invoke(activity, restored); render.invoke(activity);
        check(((android.widget.EditText) inputField.get(activity)).getText().toString().equals("unfinished answer"),
                "serialized Android saved state restores the draft");
        java.lang.reflect.Field words = MainActivity.class.getDeclaredField("quizWords"); words.setAccessible(true);
        Word first = ((List<Word>) words.get(activity)).get(0);
        java.lang.reflect.Method submit = MainActivity.class.getDeclaredMethod("submitQuizAnswer", String.class); submit.setAccessible(true);
        submit.invoke(activity, "definitely wrong");
        save.invoke(activity, saved); restore.invoke(activity, saved); render.invoke(activity);
        check(findText(activity.getWindow().getDecorView(), "The word was " + first.spelling + ".") != null,
                "answer feedback and retry queue survive saved state");
        reset.invoke(activity);
        java.lang.reflect.Method startReview = MainActivity.class.getDeclaredMethod("startReview", List.class); startReview.setAccessible(true);
        startReview.invoke(activity, Arrays.asList(first, first));
        findText(activity.getWindow().getDecorView(), "Reveal meaning").performClick();
        findText(activity.getWindow().getDecorView(), "Saved").performClick();
        findText(activity.getWindow().getDecorView(), "Practice").performClick();
        check(findText(activity.getWindow().getDecorView(), "Again tomorrow") != null, "review reveal survives tab switching");
        save.invoke(activity, saved); restore.invoke(activity, saved); render.invoke(activity);
        check(findText(activity.getWindow().getDecorView(), "Again tomorrow") != null, "review queue and reveal survive saved state");
    }

    private Class<?> classForQuiz() {
        for (Class<?> type : MainActivity.class.getDeclaredClasses()) if (type.getSimpleName().equals("QuizSet")) return type;
        throw new AssertionError("QuizSet missing");
    }

    private void compactReading(MainActivity activity) throws ReflectiveOperationException {
        android.content.res.Resources resources = activity.getResources();
        android.content.res.Configuration original = new android.content.res.Configuration(resources.getConfiguration());
        try {
            android.content.res.Configuration large = new android.content.res.Configuration(original); large.fontScale = 2f;
            resources.updateConfiguration(large, resources.getDisplayMetrics());
            Word longWord = new Word("w_layout_test", "understanding", "", "noun", "Learning & Thought",
                    "A long meaning that needs several lines on a small screen. This explanation continues with more context, so the full meaning must always remain reachable.",
                    "Understanding grows through practice and patient reading.", "", "", "");
            java.lang.reflect.Method make = MainActivity.class.getDeclaredMethod("makeSnapWordPage", Word.class); make.setAccessible(true);
            android.view.View page = (android.view.View) make.invoke(activity, longWord);
            float density = resources.getDisplayMetrics().density;
            int width = (int) (360 * density), height = (int) (640 * density);
            for (int i = 0; i < 3; i++) {
                page.measure(android.view.View.MeasureSpec.makeMeasureSpec(width, android.view.View.MeasureSpec.EXACTLY),
                        android.view.View.MeasureSpec.makeMeasureSpec(height, android.view.View.MeasureSpec.EXACTLY));
                page.layout(0, 0, width, height);
            }
            android.widget.TextView full = findText(page, "Read full meaning  ↗");
            check(full != null && full.getVisibility() == android.view.View.VISIBLE, "small screen at 200% text retains full meaning action");
            int[] position = new int[2]; full.getLocationOnScreen(position);
            check(position[1] >= 0 && position[1] + full.getHeight() <= height, "full meaning action stays inside small reading viewport");
            check(full.getHeight() >= 48 * density - 1, "full meaning action keeps a 48dp touch target");
        } finally { resources.updateConfiguration(original, resources.getDisplayMetrics()); }
    }

    private android.widget.TextView findText(android.view.View view, String text) {
        if (view instanceof android.widget.TextView && text.contentEquals(((android.widget.TextView) view).getText()))
            return (android.widget.TextView) view;
        if (view instanceof android.view.ViewGroup) {
            android.view.ViewGroup group = (android.view.ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                android.widget.TextView found = findText(group.getChildAt(i), text);
                if (found != null) return found;
            }
        }
        return null;
    }

    private void awaitCore(LafzRepository repository) throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        final String[] failure = {null};
        repository.ensureLoaded(new LafzRepository.Listener() {
            @Override public void onProgress(String message) { }
            @Override public void onReady(int wordCount) { latch.countDown(); }
            @Override public void onError(String message) { failure[0] = message; latch.countDown(); }
        });
        check(latch.await(180, TimeUnit.SECONDS), "Core database installs within three minutes");
        if (failure[0] != null) throw new AssertionError(failure[0]);
    }

    private void awaitFull(LafzRepository repository) throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        final String[] failure = {null};
        repository.ensureFull(new LafzRepository.Listener() {
            @Override public void onProgress(String message) { }
            @Override public void onReady(int wordCount) { latch.countDown(); }
            @Override public void onError(String message) { failure[0] = message; latch.countDown(); }
        });
        check(latch.await(300, TimeUnit.SECONDS), "Full database installs within five minutes");
        if (failure[0] != null) throw new AssertionError(failure[0]);
    }

    private void preferences(Context context) {
        LafzPrefs.get(context).edit().clear().commit();
        Word word = new Word("w_instrumented_stable_id", "garden", "", "noun", "Nature", "a place", "", "", "", "");
        LafzPrefs.saveWord(context, word, true);
        check(LafzPrefs.saved(context).contains(word.id), "save keeps the new stable word ID");
        check(LafzPrefs.get(context).getLong("saved_at_" + word.id, 0) > 0, "save timestamps remain available");
        check(ReviewScheduler.due(context, Arrays.asList(word)).size() == 1, "new saves enter the review queue");
        ReviewScheduler.answer(context, word, true);
        check(ReviewScheduler.due(context, Arrays.asList(word)).isEmpty(), "review scheduling moves a remembered entry forward");
        ReviewScheduler.answer(context, word, true);
        check(LafzPrefs.get(context).getInt("due_" + word.id, 0) == LafzPrefs.today() + 3, "review spacing progresses");
        ReviewScheduler.answer(context, word, false);
        check(LafzPrefs.get(context).getInt("due_" + word.id, 0) == LafzPrefs.today() + 1, "missed words return tomorrow");
        LafzPrefs.saveWord(context, word, false);
        check(!LafzPrefs.saved(context).contains(word.id), "unsaving removes the stable ID");
        LafzPrefs.saveWord(context, word, true);
        check(LafzPrefs.get(context).getInt("due_" + word.id, 0) == LafzPrefs.today() + 1, "undo keeps review schedule");
        check(ReviewScheduler.nextInterval(context, word) == 1, "interval preview matches a reset stage");
        try {
            String backup = CollectionBackup.exportJson(context);
            LafzPrefs.get(context).edit().clear().commit();
            CollectionBackup.importJson(context, backup);
            check(LafzPrefs.saved(context).contains(word.id), "backup restores saved words");
            check(LafzPrefs.get(context).getInt("due_" + word.id, 0) == LafzPrefs.today() + 1, "backup restores schedule");
            check(LafzPrefs.get(context).getStringSet("reviewed_day_" + LafzPrefs.today(), Collections.emptySet()).contains(word.id), "backup restores progress");
            LafzPrefs.get(context).edit().putInt("due_" + word.id, LafzPrefs.today() + 30).commit();
            CollectionBackup.importJson(context, backup);
            check(LafzPrefs.get(context).getInt("due_" + word.id, 0) == LafzPrefs.today() + 30, "import preserves existing schedules");
            String before = CollectionBackup.exportJson(context);
            boolean rejected = false;
            try { CollectionBackup.importJson(context, "{\"format\":\"lafz-collection\",\"version\":1,\"data\":{\"saved_words\":[\"w_valid\"],\"stage_w_valid\":99}}"); }
            catch (Exception expected) { rejected = true; }
            check(rejected && before.equals(CollectionBackup.exportJson(context)), "malformed import changes nothing");
        } catch (Exception failure) { throw new AssertionError(failure); }
        LafzPrefs.get(context).edit().clear().commit();
    }

    private static void check(boolean condition, String description) {
        if (!condition) throw new AssertionError(description);
    }
}
