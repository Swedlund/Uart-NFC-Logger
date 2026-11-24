package com.nfclogger;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;

import com.dk.log.DKLog;
import com.dk.log.DKLogCallback;
import com.dk.uartnfc.DKCloudID.IDCardData;
import com.dk.uartnfc.DeviceManager.DeviceManagerCallback;
import com.dk.uartnfc.DeviceManager.UartNfcDevice;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Owns the UART NFC reader. The reader lives here (not in the activity), so tags are
 * read from boot without the UI ever being opened, and the service restarts itself if
 * Android kills it.
 */
public class ForegroundService extends Service {
    public static final String ACTION_RECONNECT = "com.nfclogger.RECONNECT";

    static final String PREFS_NAME = "NfcLoggerPrefs";
    static final String PREF_PORT_NAME = "portName";
    static final String PREF_BAUD_RATE = "baudRate";
    static final String DEFAULT_PORT = "/dev/ttyS9";
    static final String DEFAULT_BAUD = "115200";

    private static final String TAG = "NFC Logger";
    private static final String CHANNEL_ID = "nfc_logger_service";
    private static final int NOTIFICATION_ID = 1;

    /** How often the port is checked and reopened if needed. */
    private static final long WATCHDOG_INTERVAL_MS = 30_000;
    /** How often the reader is asked for its firmware version to prove it still answers. */
    private static final long PING_INTERVAL_MS = 5 * 60_000;
    /** Restart alarm used if the process dies (task removed, low memory). */
    private static final long RESTART_DELAY_MS = 5_000;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();

    private UartNfcDevice device;
    private String portName = DEFAULT_PORT;
    private String baudRate = DEFAULT_BAUD;
    private long openRetryDelayMs = 2_000;
    private long lastPingMs = 0;
    private int failedPings = 0;
    private boolean opening = false;
    private PowerManager.WakeLock cpuLock;

    static void start(Context context) {
        try {
            ContextCompat.startForegroundService(context, new Intent(context, ForegroundService.class));
        } catch (Exception e) {
            Log.e(TAG, "Could not start service: " + e.getMessage(), e);
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        startForeground(NOTIFICATION_ID, buildNotification("Starting…"));
        LogBus.init(getApplicationContext());

        // A partial wake lock keeps the CPU awake so the UART read thread keeps running when the
        // screen is off. It costs almost nothing on a mains-powered wall tablet.
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        if (pm != null) {
            cpuLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "NfcLogger:uart");
            cpuLock.setReferenceCounted(false);
            cpuLock.acquire();
        }

        DKLog.setLogCallback(dkLogCallback);
        device = new UartNfcDevice();
        device.setCallBack(deviceCallback);

        loadPreferences();
        openPort();
        handler.postDelayed(watchdog, WATCHDOG_INTERVAL_MS);
        WatchdogReceiver.schedule(this);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_RECONNECT.equals(intent.getAction())) {
            loadPreferences();
            LogBus.log("Reconnecting to " + portName + " @ " + baudRate);
            closePort();
            openRetryDelayMs = 2_000;
            openPort();
        }
        // Ask Android to recreate the service if it ever has to kill it.
        return START_STICKY;
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        super.onTaskRemoved(rootIntent);
        scheduleRestart();
    }

    @Override
    public void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        closePort();
        if (device != null) {
            try {
                device.destroy();
            } catch (Exception ignored) {
            }
        }
        worker.shutdownNow();
        if (cpuLock != null && cpuLock.isHeld()) cpuLock.release();
        LogBus.log("Service stopped, scheduling restart");
        scheduleRestart();
        super.onDestroy();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    // ---- Port handling -------------------------------------------------------------------

    private void loadPreferences() {
        SharedPreferences p = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        portName = p.getString(PREF_PORT_NAME, DEFAULT_PORT);
        baudRate = p.getString(PREF_BAUD_RATE, DEFAULT_BAUD);
    }

    private boolean isPortOpen() {
        try {
            return device != null && device.serialManager != null && device.serialManager.isOpen();
        } catch (Exception e) {
            return false;
        }
    }

    private void openPort() {
        if (opening || device == null) return;
        if (isPortOpen()) return;
        opening = true;
        boolean ok = false;
        try {
            ok = device.serialManager != null && device.serialManager.open(portName, baudRate);
        } catch (Exception e) {
            LogBus.log("Error opening " + portName + ": " + e.getMessage());
        }
        opening = false;
        if (ok) {
            openRetryDelayMs = 2_000;
            failedPings = 0;
            lastPingMs = SystemClock.elapsedRealtime();
            LogBus.log("NFC device initialized with port: " + portName + " and baud rate: " + baudRate);
            updateNotification("Reader connected (" + portName + ")");
        } else {
            LogBus.log("Could not open " + portName + ", retrying in " + (openRetryDelayMs / 1000) + " s");
            updateNotification("Waiting for reader…");
            handler.removeCallbacks(retryOpen);
            handler.postDelayed(retryOpen, openRetryDelayMs);
            openRetryDelayMs = Math.min(openRetryDelayMs * 2, 60_000);
        }
    }

    private void closePort() {
        handler.removeCallbacks(retryOpen);
        try {
            if (device != null && device.serialManager != null) device.serialManager.close();
        } catch (Exception ignored) {
        }
    }

    private final Runnable retryOpen = this::openPort;

    private final Runnable watchdog = new Runnable() {
        @Override
        public void run() {
            if (!isPortOpen()) {
                openPort();
            } else if (SystemClock.elapsedRealtime() - lastPingMs > PING_INTERVAL_MS) {
                lastPingMs = SystemClock.elapsedRealtime();
                pingReader();
            }
            handler.postDelayed(this, WATCHDOG_INTERVAL_MS);
        }
    };

    /** Ask the reader for its firmware version off the main thread; reopen after two misses. */
    private void pingReader() {
        worker.execute(() -> {
            boolean answered;
            try {
                String fw = device.getFirmwareVersion();
                answered = fw != null;
            } catch (Exception e) {
                answered = false;
            }
            final boolean ok = answered;
            handler.post(() -> {
                if (ok) {
                    failedPings = 0;
                } else if (++failedPings >= 2) {
                    LogBus.log("Reader not responding, reopening port");
                    failedPings = 0;
                    closePort();
                    openPort();
                }
            });
        });
    }

    // ---- Restart safety net --------------------------------------------------------------

    private void scheduleRestart() {
        AlarmManager am = (AlarmManager) getSystemService(ALARM_SERVICE);
        if (am == null) return;
        PendingIntent pi = WatchdogReceiver.pendingIntent(this);
        am.set(AlarmManager.ELAPSED_REALTIME_WAKEUP, SystemClock.elapsedRealtime() + RESTART_DELAY_MS, pi);
    }

    // ---- Notification ---------------------------------------------------------------------

    private Notification buildNotification(String text) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null && nm.getNotificationChannel(CHANNEL_ID) == null) {
                NotificationChannel ch = new NotificationChannel(CHANNEL_ID, "NFC reader",
                        NotificationManager.IMPORTANCE_MIN);
                ch.setShowBadge(false);
                ch.setSound(null, null);
                nm.createNotificationChannel(ch);
            }
        }
        PendingIntent open = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("NFC Logger")
                .setContentText(text)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentIntent(open)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setSilent(true)
                .setPriority(NotificationCompat.PRIORITY_MIN)
                .build();
    }

    private void updateNotification(String text) {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm != null) nm.notify(NOTIFICATION_ID, buildNotification(text));
    }

    // ---- Reader callbacks -----------------------------------------------------------------

    private final DeviceManagerCallback deviceCallback = new DeviceManagerCallback() {
        @Override
        public void onReceiveRfnSearchCard(boolean blnIsSus, int cardType, byte[] bytCardSn, byte[] bytCarATS) {
            super.onReceiveRfnSearchCard(blnIsSus, cardType, bytCardSn, bytCarATS);
            if (bytCardSn != null) LogBus.log("Card detected: " + bytesToHex(bytCardSn));
        }

        @Override
        public void onReceiveSamVIdStart(byte[] initData) {
            super.onReceiveSamVIdStart(initData);
            LogBus.log("Start parsing");
        }

        @Override
        public void onReceiveSamVIdException(String msg) {
            super.onReceiveSamVIdException(msg);
            LogBus.log("Exception: " + (msg != null ? msg : "Unknown error"));
        }

        @Override
        public void onReceiveIDCardData(IDCardData idCardData) {
            super.onReceiveIDCardData(idCardData);
            LogBus.log("ID Card Data: " + (idCardData != null ? idCardData.toString() : "No data"));
        }

        @Override
        public void onReceiveCardLeave() {
            super.onReceiveCardLeave();
            LogBus.log("Card left");
        }
    };

    /** The SDK is chatty at debug level; only keep warnings and errors in logcat. */
    private final DKLogCallback dkLogCallback = new DKLogCallback() {
        @Override
        public void onReceiveLogI(String tag, String msg) {
        }

        @Override
        public void onReceiveLogD(String tag, String msg) {
        }

        @Override
        public void onReceiveLogE(String tag, String msg) {
            Log.e(tag != null ? tag : TAG, msg != null ? msg : "null");
        }
    };

    static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) sb.append(String.format("%02X", b));
        return sb.toString();
    }
}
