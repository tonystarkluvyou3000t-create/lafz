package com.lafz.app;

import android.app.Instrumentation;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.SystemClock;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/** Focused regressions; backup tests use their own preferences. */
public final class BugRegressionInstrumentation extends Instrumentation {
    private final List<String> failures = new ArrayList<>();
    private int passed;

    @Override public void onCreate(Bundle arguments) { super.onCreate(arguments); start(); }

    @Override public void onStart() {
        try {
            backupVersions();
            todayState();
            widgetDestination();
            reminderTimes();
        } catch (Exception error) { failures.add(error.toString()); }
        Bundle result = new Bundle();
        result.putString("regressions", passed + " passed; " + failures.size() + " failed");
        result.putString("failures", String.join("\n", failures));
        finish(failures.isEmpty() ? -1 : 0, result);
    }

    private void check(boolean condition, String description) {
        if (condition) passed++; else failures.add(description);
    }

    private void backupVersions() throws Exception {
        Context context = new ContextWrapper(getTargetContext()) {
            @Override public SharedPreferences getSharedPreferences(String name, int mode) {
                return super.getSharedPreferences("bug_regressions_" + name, mode);
            }
        };
        SharedPreferences prefs = LafzPrefs.get(context);
        prefs.edit().clear().putInt("due_w_existing", 12345).commit();
        try {
            for (String version : new String[]{"1.5", "\"1\"", "4294967297"}) {
                String before = CollectionBackup.exportJson(context);
                boolean rejected = false;
                try {
                    CollectionBackup.importJson(context, "{\"format\":\"lafz-collection\",\"version\":" + version +
                            ",\"data\":{\"saved_words\":[\"w_imported\"]}}");
                } catch (Exception expected) { rejected = true; }
                check(rejected && before.equals(CollectionBackup.exportJson(context)),
                        "Malformed version " + version + " must be rejected without changing the collection");
            }
            CollectionBackup.importJson(context,
                    "{\"format\":\"lafz-collection\",\"version\":1,\"data\":{\"saved_words\":[\"w_valid\"]}}");
            check(LafzPrefs.saved(context).contains("w_valid"), "Valid version 1 still imports");
        } finally { prefs.edit().clear().commit(); }
    }

    private MainActivity launch() {
        MainActivity activity = (MainActivity) startActivitySync(new Intent(getTargetContext(), MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        SystemClock.sleep(1800);
        waitForIdleSync();
        return activity;
    }

    private void todayState() throws Exception {
        MainActivity activity = launch();
        Field explore = MainActivity.class.getDeclaredField("exploreMode"); explore.setAccessible(true);
        Method save = MainActivity.class.getDeclaredMethod("onSaveInstanceState", Bundle.class); save.setAccessible(true);
        Method restore = MainActivity.class.getDeclaredMethod("restorePractice", Bundle.class); restore.setAccessible(true);
        try {
            runOnMainSync(() -> {
                try {
                    explore.setBoolean(activity, false);
                    Bundle state = new Bundle(); save.invoke(activity, state);
                    explore.setBoolean(activity, true); restore.invoke(activity, state);
                    check(!explore.getBoolean(activity), "Today mode survives activity saved-state restoration");
                } catch (Exception error) { failures.add(error.toString()); }
            });
        } finally { runOnMainSync(activity::finish); waitForIdleSync(); }
    }

    private void widgetDestination() throws Exception {
        ActivityMonitor monitor = addMonitor(MainActivity.class.getName(), null, false);
        try {
            runOnMainSync(() -> {
                android.view.View widget = WordWidgetProvider.views(getTargetContext(), 9877, null)
                        .apply(getTargetContext(), null);
                widget.findViewById(R.id.widget_root).performClick();
            });
            MainActivity activity = (MainActivity) waitForMonitorWithTimeout(monitor, 8000);
            check(activity != null, "Widget opens Lafz");
            if (activity == null) return;
            SystemClock.sleep(1800); waitForIdleSync();
            Field explore = MainActivity.class.getDeclaredField("exploreMode"); explore.setAccessible(true);
            try {
                check(!explore.getBoolean(activity), "Widget opens Today instead of an unrelated Explore word");
                Field tab = MainActivity.class.getDeclaredField("currentTab"); tab.setAccessible(true);
                Method sheet = MainActivity.class.getDeclaredMethod("showCategoryDialog"); sheet.setAccessible(true);
                runOnMainSync(() -> {
                    try {
                        explore.setBoolean(activity, true);
                        sheet.invoke(activity);
                        Intent daily = MainActivity.todayIntent(getTargetContext());
                        check((daily.getFlags() & (Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP)) ==
                                (Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP), "Daily entry requests reuse of the open activity");
                        callActivityOnNewIntent(activity, daily);
                    } catch (Exception error) { failures.add(error.toString()); }
                });
                SystemClock.sleep(500); waitForIdleSync();
                check(!explore.getBoolean(activity) && "FEED".equals(tab.get(activity).toString()),
                        "Daily entry reuses an open activity and returns it to Today");
                Field activeSheet = MainActivity.class.getDeclaredField("activeSheet"); activeSheet.setAccessible(true);
                check(activeSheet.get(activity) == null, "Daily entry closes an existing sheet");
            }
            finally { runOnMainSync(activity::finish); waitForIdleSync(); }
        } finally { removeMonitor(monitor); }
    }

    private void reminderTimes() {
        for (String[] sample : new String[][] {
                {"2026-10-16T08:00:00+05:30[Asia/Kolkata]", "2026-10-16T09:00:00+05:30[Asia/Kolkata]", "540"},
                {"2026-10-16T09:00:00+05:30[Asia/Kolkata]", "2026-10-17T09:00:00+05:30[Asia/Kolkata]", "540"},
                {"2026-03-07T09:00:00-05:00[America/New_York]", "2026-03-08T09:00:00-04:00[America/New_York]", "540"},
                {"2026-10-31T09:00:00-04:00[America/New_York]", "2026-11-01T09:00:00-05:00[America/New_York]", "540"},
                {"2026-03-08T00:00:00-05:00[America/New_York]", "2026-03-08T03:30:00-04:00[America/New_York]", "150"},
                {"2026-11-01T01:45:00-04:00[America/New_York]", "2026-11-02T01:30:00-05:00[America/New_York]", "90"}
        }) {
            java.time.ZonedDateTime now = java.time.ZonedDateTime.parse(sample[0]);
            long expected = java.time.ZonedDateTime.parse(sample[1]).toInstant().toEpochMilli();
            check(DailyWordReceiver.nextTrigger(now.toInstant().toEpochMilli(), now.getZone(), Integer.parseInt(sample[2])) == expected,
                    "Reminder local-time calculation: " + sample[0]);
        }
    }
}
