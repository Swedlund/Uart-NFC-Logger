package com.nfclogger;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ScrollView;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.dk.log.DKLog;
import com.dk.log.DKLogCallback;
import com.dk.uartnfc.DeviceManager.UartNfcDevice;
import com.dk.uartnfc.DeviceManager.DeviceManagerCallback;
import com.dk.uartnfc.DKCloudID.IDCardData;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;

public class MainActivity extends AppCompatActivity {
    private static final int PERMISSION_REQUEST_CODE = 100;
    private static final String PREFS_NAME = "NfcLoggerPrefs";
    private static final String PREF_PORT_NAME = "portName";
    private static final String PREF_BAUD_RATE = "baudRate";
    private UartNfcDevice uartNfcDevice;
    private static final String TAG = "NFC Logger";
    private TextView logOutputTextView;
    private EditText portNameEditText;
    private EditText baudRateEditText;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main); // Ensure this line references the correct layout file

        portNameEditText = findViewById(R.id.portName);
        baudRateEditText = findViewById(R.id.baudRate);
        Button saveButton = findViewById(R.id.saveButton);
        logOutputTextView = findViewById(R.id.logOutput);

        // Initialize uartNfcDevice
        uartNfcDevice = new UartNfcDevice();
        uartNfcDevice.setCallBack(deviceManagerCallback);

        // Set DKLog callback
        DKLog.setLogCallback(logCallback);

        // Load saved values
        loadPreferences();

        saveButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                try {
                    String portName = portNameEditText.getText().toString();
                    String baudRate = baudRateEditText.getText().toString();

                    // Log the input values
                    logMessage("Port Name: " + portName);
                    logMessage("Baud Rate: " + baudRate);

                    // Save values to SharedPreferences
                    savePreferences(portName, baudRate);

                    // Disconnect the old NFC device
                    disconnectNfcDevice(portName);

                    // Re-initialize the NFC device with new values
                    initializeNfcDevice(portName, baudRate);
                } catch (Exception e) {
                    logMessage("Error in saveButton onClick: " + e.getMessage());
                }
            }
        });

        // Check permissions and initialize NFC device
        if (checkPermissions()) {
            String portName = portNameEditText.getText().toString();
            String baudRate = baudRateEditText.getText().toString();
            initializeNfcDevice(portName, baudRate);
        } else {
            requestPermissions();
        }

        // Start the ForegroundService
        Intent serviceIntent = new Intent(this, ForegroundService.class);
        ContextCompat.startForegroundService(this, serviceIntent);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (uartNfcDevice != null) {
            uartNfcDevice.destroy();
        }
    }

    private boolean checkPermissions() {
        int internetPermission = ContextCompat.checkSelfPermission(this, Manifest.permission.INTERNET);
        int writePermission = ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE);
        boolean permissionsGranted = internetPermission == PackageManager.PERMISSION_GRANTED && writePermission == PackageManager.PERMISSION_GRANTED;
        logMessage("Permissions granted: " + permissionsGranted);
        return permissionsGranted;
    }

    private void requestPermissions() {
        logMessage("Requesting permissions");
        ActivityCompat.requestPermissions(this, new String[]{
                Manifest.permission.INTERNET,
                Manifest.permission.WRITE_EXTERNAL_STORAGE
        }, PERMISSION_REQUEST_CODE);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERMISSION_REQUEST_CODE) {
            boolean allPermissionsGranted = true;
            for (int result : grantResults) {
                if (result != PackageManager.PERMISSION_GRANTED) {
                    allPermissionsGranted = false;
                    break;
                }
            }
            if (allPermissionsGranted) {
                logMessage("All permissions granted");
                String portName = portNameEditText.getText().toString();
                String baudRate = baudRateEditText.getText().toString();
                initializeNfcDevice(portName, baudRate);
                // Start the ForegroundService after permissions are granted
                Intent serviceIntent = new Intent(this, ForegroundService.class);
                ContextCompat.startForegroundService(this, serviceIntent);
            } else {
                logMessage("Permissions denied");
                // Handle the case where permissions are denied
            }
        }
    }

    private void initializeNfcDevice(String portName, String baudRate) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    Thread.sleep(3000); // Consider reducing or removing this delay if not necessary
                    if (uartNfcDevice != null) {
                        if (uartNfcDevice.serialManager != null) {
                            uartNfcDevice.serialManager.open(portName, baudRate);
                            logMessage("NFC device initialized with port: " + portName + " and baud rate: " + baudRate);
                        } else {
                            logMessage("serialManager is null");
                        }
                    } else {
                        logMessage("uartNfcDevice is null");
                    }
                } catch (InterruptedException e) {
                    logMessage("Thread interrupted: " + e.getMessage());
                } catch (Exception e) {
                    logMessage("Error opening NFC device: " + e.getMessage());
                }
            }
        }).start();
    }

    private void disconnectNfcDevice(String currentPortName) {
        if (uartNfcDevice != null) {
            try {
                if (uartNfcDevice.serialManager != null) {
                    uartNfcDevice.serialManager.close();
                    logMessage("NFC device disconnected from port: " + currentPortName);
                }
            } catch (Exception e) {
                logMessage("Error disconnecting NFC device: " + e.getMessage());
            }
        } else {
            logMessage("uartNfcDevice is already null");
        }
    }

    private DeviceManagerCallback deviceManagerCallback = new DeviceManagerCallback() {
        @Override
        public void onReceiveRfnSearchCard(boolean blnIsSus, int cardType, byte[] bytCardSn, byte[] bytCarATS) {
            super.onReceiveRfnSearchCard(blnIsSus, cardType, bytCardSn, bytCarATS);
            logMessage("Card detected: " + bytesToHex(bytCardSn));
        }

        @Override
        public void onReceiveSamVIdStart(byte[] initData) {
            super.onReceiveSamVIdStart(initData);
            logMessage("Start parsing");
        }

        @Override
        public void onReceiveSamVIdSchedule(int rate) {
            super.onReceiveSamVIdSchedule(rate);
        }

        @Override
        public void onReceiveSamVIdException(String msg) {
            super.onReceiveSamVIdException(msg);
            logMessage("Exception: " + (msg != null ? msg : "Unknown error"));
        }

        @Override
        public void onReceiveIDCardData(IDCardData idCardData) {
            super.onReceiveIDCardData(idCardData);
            logMessage("ID Card Data: " + (idCardData != null ? idCardData.toString() : "No data"));
        }

        @Override
        public void onReceiveCardLeave() {
            super.onReceiveCardLeave();
            logMessage("Card left");
        }
    };

    private DKLogCallback logCallback = new DKLogCallback() {
        @Override
        public void onReceiveLogI(String tag, String msg) {
            super.onReceiveLogI(tag, msg);
            if (msg != null) {
                Log.i(tag, msg);
            } else {
                Log.i(tag, "Null message received");
            }
        }

        @Override
        public void onReceiveLogD(String tag, String msg) {
            super.onReceiveLogD(tag, msg);
            if (msg != null) {
                Log.d(tag, msg);
            } else {
                Log.d(tag, "Null message received");
            }
        }

        @Override
        public void onReceiveLogE(String tag, String msg) {
            super.onReceiveLogE(tag, msg);
            if (msg != null) {
                Log.e(tag, msg);
            } else {
                Log.e(tag, "Null message received");
            }
        }
    };

    private String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02X", b));
        }
        return sb.toString();
    }

    private void logMessage(String message) {
        try {
            if (message == null) {
                message = "Null message received";
            }
            final String logMessage = message; // Create a final copy of the message
            Log.d(TAG, logMessage);
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    logOutputTextView.append(logMessage + "\n");
                    // Scroll to the bottom
                    final ScrollView scrollView = findViewById(R.id.logScrollView);
                    scrollView.post(new Runnable() {
                        @Override
                        public void run() {
                            scrollView.fullScroll(View.FOCUS_DOWN);
                        }
                    });
                }
            });
            writeLogToFile(logMessage);
            sendLogBroadcast(logMessage);
        } catch (Exception e) {
            Log.e(TAG, "Error in logMessage: " + e.getMessage());
        }
    }

    private void writeLogToFile(String message) {
        File logFile;
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            logFile = new File(Environment.getExternalStorageDirectory(), "nfc_log.txt");
        } else {
            logFile = new File(getExternalFilesDir(null), "nfc_log.txt");
        }
        try (FileWriter writer = new FileWriter(logFile, true)) {
            writer.append(message).append("\n");
        } catch (IOException e) {
            logMessage("Error writing log to file: " + e.getMessage());
        }
    }

    private void sendLogBroadcast(String message) {
        Intent intent = new Intent("com.nfclogger.LOG");
        intent.putExtra("log_message", message);
        sendBroadcast(intent);
    }

    private void savePreferences(String portName, String baudRate) {
        SharedPreferences sharedPreferences = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        SharedPreferences.Editor editor = sharedPreferences.edit();
        editor.putString(PREF_PORT_NAME, portName);
        editor.putString(PREF_BAUD_RATE, baudRate);
        editor.apply();
    }

    private void loadPreferences() {
        SharedPreferences sharedPreferences = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        String portName = sharedPreferences.getString(PREF_PORT_NAME, "/dev/ttyS9");
        String baudRate = sharedPreferences.getString(PREF_BAUD_RATE, "115200");
        portNameEditText.setText(portName);
        baudRateEditText.setText(baudRate);
    }
}