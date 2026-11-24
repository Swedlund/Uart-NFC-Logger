package com.nfclogger;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/** Starts the reader service after boot and after the app has been updated. */
public class BootReceiver extends BroadcastReceiver {
    private static final String TAG = "NFC Logger";

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent != null ? intent.getAction() : null;
        Log.d(TAG, "Start trigger: " + action);
        ForegroundService.start(context);
    }
}
