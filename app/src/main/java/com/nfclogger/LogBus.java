package com.nfclogger;

import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.File;
import java.io.FileWriter;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Sends every log line to Tasker (broadcast com.nfclogger.LOG, extra log_message), keeps the
 * last lines in memory for the UI, and appends to a size-capped file in the background.
 */
final class LogBus {
    static final String ACTION_LOG = "com.nfclogger.LOG";
    static final String EXTRA_MESSAGE = "log_message";

    interface Listener {
        void onLog(String line);
    }

    private static final String TAG = "NFC Logger";
    private static final int MEMORY_LINES = 200;
    private static final long MAX_FILE_BYTES = 256 * 1024;

    private static final ArrayDeque<String> recent = new ArrayDeque<>();
    private static final Handler main = new Handler(Looper.getMainLooper());
    private static final ExecutorService fileWriter = Executors.newSingleThreadExecutor();
    private static final SimpleDateFormat time = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT);

    private static Context app;
    private static Listener listener;

    private LogBus() {
    }

    static synchronized void init(Context context) {
        if (app == null) app = context.getApplicationContext();
    }

    static void log(String message) {
        final String msg = message != null ? message : "Null message received";
        Log.d(TAG, msg);

        Context ctx;
        synchronized (LogBus.class) {
            ctx = app;
        }
        if (ctx != null) {
            // Unchanged contract for Tasker: action com.nfclogger.LOG, extra log_message.
            Intent intent = new Intent(ACTION_LOG);
            intent.putExtra(EXTRA_MESSAGE, msg);
            ctx.sendBroadcast(intent);
        }

        final String line;
        synchronized (time) {
            line = time.format(new Date()) + "  " + msg;
        }
        synchronized (recent) {
            recent.addLast(line);
            while (recent.size() > MEMORY_LINES) recent.removeFirst();
        }
        main.post(() -> {
            Listener l = listener;
            if (l != null) l.onLog(line);
        });
        if (ctx != null) fileWriter.execute(() -> appendToFile(ctx, line));
    }

    static List<String> recentLines() {
        synchronized (recent) {
            return new ArrayList<>(recent);
        }
    }

    static void setListener(Listener l) {
        listener = l;
    }

    private static void appendToFile(Context ctx, String line) {
        try {
            File dir = ctx.getExternalFilesDir(null);
            if (dir == null) dir = ctx.getFilesDir();
            File file = new File(dir, "nfc_log.txt");
            if (file.length() > MAX_FILE_BYTES) {
                File old = new File(dir, "nfc_log.old.txt");
                //noinspection ResultOfMethodCallIgnored
                old.delete();
                //noinspection ResultOfMethodCallIgnored
                file.renameTo(old);
            }
            try (FileWriter w = new FileWriter(file, true)) {
                w.append(line).append('\n');
            }
        } catch (Exception e) {
            Log.w(TAG, "Could not write log file: " + e.getMessage());
        }
    }
}
