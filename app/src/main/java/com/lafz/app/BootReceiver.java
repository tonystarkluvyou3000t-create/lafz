package com.lafz.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        DailyWordReceiver.schedule(context);
        DailyWordRefreshReceiver.schedule(context);
        PendingResult result = goAsync();
        WordWidgetProvider.refresh(context, result::finish);
    }
}
