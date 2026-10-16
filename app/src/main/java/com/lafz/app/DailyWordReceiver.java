package com.lafz.app;

import android.Manifest;
import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;

import java.util.Calendar;
import java.util.Set;

public class DailyWordReceiver extends BroadcastReceiver {
    static final String CHANNEL_ID = "lafz_daily_word";
    private static final int ALARM_ID = 1603;

    @Override
    public void onReceive(Context context, Intent intent) {
        if (!LafzPrefs.get(context).getBoolean("daily_enabled", false)) return;
        if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return;

        Context appContext = context.getApplicationContext();
        BroadcastReceiver.PendingResult pendingResult = goAsync();
        LafzRepository.executeBackground(() -> {
            try {
                Set<String> categories = LafzPrefs.categories(appContext);
                Word word = LafzRepository.get(appContext).daily(categories, LafzPrefs.today());
                if (word == null) return;
                Intent open = new Intent(appContext, MainActivity.class);
                open.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
                PendingIntent content = PendingIntent.getActivity(appContext, 1604, open,
                        PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
                Notification notification = new Notification.Builder(appContext, CHANNEL_ID)
                        .setSmallIcon(android.R.drawable.ic_menu_info_details)
                        .setContentTitle("Your Lafz for today")
                        .setContentText(word.spelling + "  ·  " + word.meaning)
                        .setStyle(new Notification.BigTextStyle().bigText(word.meaning + "  “" + word.example + "”"))
                        .setContentIntent(content)
                        .setAutoCancel(true)
                        .setCategory(Notification.CATEGORY_REMINDER)
                        .build();
                NotificationManager manager = appContext.getSystemService(NotificationManager.class);
                if (manager != null) manager.notify(1605, notification);
            } finally {
                pendingResult.finish();
            }
        });
    }

    static void schedule(Context context) {
        AlarmManager manager = context.getSystemService(AlarmManager.class);
        if (manager == null) return;
        Intent intent = new Intent(context, DailyWordReceiver.class);
        PendingIntent pending = PendingIntent.getBroadcast(context, ALARM_ID, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        if (!LafzPrefs.get(context).getBoolean("daily_enabled", false)) {
            manager.cancel(pending);
            return;
        }
        if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            manager.cancel(pending);
            return;
        }
        int minutes = LafzPrefs.get(context).getInt("daily_time", 9 * 60);
        Calendar trigger = Calendar.getInstance();
        trigger.set(Calendar.HOUR_OF_DAY, minutes / 60);
        trigger.set(Calendar.MINUTE, minutes % 60);
        trigger.set(Calendar.SECOND, 0);
        trigger.set(Calendar.MILLISECOND, 0);
        if (!trigger.after(Calendar.getInstance())) trigger.add(Calendar.DATE, 1);
        manager.setInexactRepeating(AlarmManager.RTC_WAKEUP, trigger.getTimeInMillis(), AlarmManager.INTERVAL_DAY, pending);
    }
}
