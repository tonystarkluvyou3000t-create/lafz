package com.lafz.app;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.Context;
import android.content.Intent;
import android.widget.RemoteViews;

public class WordWidgetProvider extends AppWidgetProvider {
    static void refresh(Context context) {
        refresh(context, () -> { });
    }

    static void refresh(Context context, Runnable finished) {
        AppWidgetManager manager = AppWidgetManager.getInstance(context);
        int[] ids = manager.getAppWidgetIds(new android.content.ComponentName(context, WordWidgetProvider.class));
        update(context, manager, ids, finished);
    }

    @Override public void onEnabled(Context context) {
        DailyWordRefreshReceiver.schedule(context);
    }

    @Override public void onDisabled(Context context) {
        DailyWordRefreshReceiver.schedule(context);
    }

    @Override
    public void onUpdate(Context context, AppWidgetManager manager, int[] appWidgetIds) {
        DailyWordRefreshReceiver.schedule(context);
        PendingResult result = goAsync();
        update(context, manager, appWidgetIds, () -> { if (result != null) result.finish(); });
    }

    private static void update(Context context, AppWidgetManager manager, int[] appWidgetIds, Runnable finished) {
        if (appWidgetIds == null || appWidgetIds.length == 0) { finished.run(); return; }
        Context appContext = context.getApplicationContext();
        int[] ids = appWidgetIds.clone();
        LafzRepository.executeBackground(() -> {
            try {
                Word word = LafzRepository.get(appContext).daily(LafzPrefs.categories(appContext), LafzPrefs.today());
                for (int id : ids) manager.updateAppWidget(id, views(appContext, id, word));
            } finally {
                finished.run();
            }
        });
    }

    static RemoteViews views(Context context, int id, Word word) {
        RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.word_widget);
        views.setTextViewText(R.id.widget_word, word == null ? "Open Lafz" : word.spelling);
        views.setTextViewText(R.id.widget_meaning, word == null ? "Loading the free dictionary" : word.meaning);
        Intent open = MainActivity.todayIntent(context);
        PendingIntent pending = PendingIntent.getActivity(context, id, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        views.setOnClickPendingIntent(R.id.widget_root, pending);
        return views;
    }
}
