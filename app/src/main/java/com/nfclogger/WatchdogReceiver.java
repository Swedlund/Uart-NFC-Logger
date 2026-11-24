package com.nfclogger;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.SystemClock;

/**
 * Safety net: a repeating alarm (every 15 min) and the restart alarm both land here and simply
 * (re)start the service. Starting an already running service is a no-op.
 */
public class WatchdogReceiver extends BroadcastReceiver {
    private static final long INTERVAL_MS = AlarmManager.INTERVAL_FIFTEEN_MINUTES;

    @Override
    public void onReceive(Context context, Intent intent) {
        ForegroundService.start(context);
    }

    static PendingIntent pendingIntent(Context context) {
        Intent i = new Intent(context, WatchdogReceiver.class);
        return PendingIntent.getBroadcast(context, 0, i,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    static void schedule(Context context) {
        AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        am.setInexactRepeating(AlarmManager.ELAPSED_REALTIME_WAKEUP,
                SystemClock.elapsedRealtime() + INTERVAL_MS, INTERVAL_MS, pendingIntent(context));
    }
}
