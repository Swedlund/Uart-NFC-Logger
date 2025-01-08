package com.nfclogger;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;
import androidx.core.content.ContextCompat;

public class BootReceiver extends BroadcastReceiver {
    private static final String TAG = "NFC Logger";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) {
            Log.d(TAG, "Boot completed, starting ForegroundService");
            Intent serviceIntent = new Intent(context, ForegroundService.class);
            ContextCompat.startForegroundService(context, serviceIntent);
        }
    }
}