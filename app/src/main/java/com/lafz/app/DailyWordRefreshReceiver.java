package com.lafz.app;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;

import java.time.Instant;
import java.time.ZoneId;

/** Refreshes widgets at the local day boundary, independently of notifications. */
public final class DailyWordRefreshReceiver extends BroadcastReceiver {
    private static final int ALARM_ID = 1606;

    static long nextMidnight(long now, ZoneId zone) {
        return Instant.ofEpochMilli(now).atZone(zone).toLocalDate().plusDays(1)
                .atStartOfDay(zone).toInstant().toEpochMilli();
    }

    static void schedule(Context context) {
        AlarmManager manager = context.getSystemService(AlarmManager.class);
        if (manager == null) return;
        PendingIntent pending = PendingIntent.getBroadcast(context, ALARM_ID,
                new Intent(context, DailyWordRefreshReceiver.class),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        int[] ids = AppWidgetManager.getInstance(context).getAppWidgetIds(
                new ComponentName(context, WordWidgetProvider.class));
        if (ids.length == 0) {
            manager.cancel(pending);
            return;
        }
        // A new local midnight is calculated after every delivery, including DST changes.
        manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP,
                nextMidnight(System.currentTimeMillis(), ZoneId.systemDefault()), pending);
    }

    @Override public void onReceive(Context context, Intent intent) {
        schedule(context);
        PendingResult result = goAsync();
        WordWidgetProvider.refresh(context, result::finish);
    }
}
