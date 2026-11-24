package com.nfclogger;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.core.content.ContextCompat;

/**
 * Settings and live log only. The NFC reader itself runs in {@link ForegroundService}, so
 * closing or never opening this screen does not affect tag reading.
 */
public class MainActivity extends Activity {
    private TextView logOutput;
    private ScrollView logScroll;
    private EditText portNameEdit;
    private EditText baudRateEdit;

    private final LogBus.Listener listener = line -> {
        logOutput.append(line + "\n");
        logScroll.post(() -> logScroll.fullScroll(View.FOCUS_DOWN));
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        LogBus.init(getApplicationContext());

        portNameEdit = findViewById(R.id.portName);
        baudRateEdit = findViewById(R.id.baudRate);
        logOutput = findViewById(R.id.logOutput);
        logScroll = findViewById(R.id.logScrollView);
        Button saveButton = findViewById(R.id.saveButton);

        SharedPreferences prefs = getSharedPreferences(ForegroundService.PREFS_NAME, MODE_PRIVATE);
        portNameEdit.setText(prefs.getString(ForegroundService.PREF_PORT_NAME, ForegroundService.DEFAULT_PORT));
        baudRateEdit.setText(prefs.getString(ForegroundService.PREF_BAUD_RATE, ForegroundService.DEFAULT_BAUD));

        saveButton.setOnClickListener(v -> {
            prefs.edit()
                    .putString(ForegroundService.PREF_PORT_NAME, portNameEdit.getText().toString().trim())
                    .putString(ForegroundService.PREF_BAUD_RATE, baudRateEdit.getText().toString().trim())
                    .apply();
            Intent i = new Intent(this, ForegroundService.class).setAction(ForegroundService.ACTION_RECONNECT);
            ContextCompat.startForegroundService(this, i);
        });

        ForegroundService.start(this);
    }

    @Override
    protected void onStart() {
        super.onStart();
        StringBuilder sb = new StringBuilder();
        for (String line : LogBus.recentLines()) sb.append(line).append('\n');
        logOutput.setText(sb);
        logScroll.post(() -> logScroll.fullScroll(View.FOCUS_DOWN));
        LogBus.setListener(listener);
    }

    @Override
    protected void onStop() {
        LogBus.setListener(null);
        super.onStop();
    }
}
