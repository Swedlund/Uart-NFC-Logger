package com.nfclogger;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.util.Log;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import com.dk.uartnfc.DeviceManager.UartNfcDevice;
import com.dk.uartnfc.DeviceManager.DeviceManagerCallback;
import com.dk.uartnfc.DKCloudID.IDCardData;

public class MainActivity extends AppCompatActivity {
    private static final int PERMISSION_REQUEST_CODE = 100;
    private UartNfcDevice uartNfcDevice;
    private static final String TAG = "NFC Logger";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main); // Ensure this line references the correct layout file

        if (checkPermissions()) {
            initializeNfcDevice();
        } else {
            requestPermissions();
        }

        // Start the ForegroundService
        Intent serviceIntent = new Intent(this, ForegroundService.class);
        ContextCompat.startForegroundService(this, serviceIntent);

        // Move the app to the background
        moveTaskToBack(true);
    }

    private boolean checkPermissions() {
        int internetPermission = ContextCompat.checkSelfPermission(this, Manifest.permission.INTERNET);
        boolean permissionsGranted = internetPermission == PackageManager.PERMISSION_GRANTED;
        Log.d(TAG, "Permissions granted: " + permissionsGranted);
        return permissionsGranted;
    }

    private void requestPermissions() {
        Log.d(TAG, "Requesting permissions");
        ActivityCompat.requestPermissions(this, new String[]{
                Manifest.permission.INTERNET
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
                Log.d(TAG, "All permissions granted");
                initializeNfcDevice();
                // Start the ForegroundService after permissions are granted
                Intent serviceIntent = new Intent(this, ForegroundService.class);
                ContextCompat.startForegroundService(this, serviceIntent);
            } else {
                Log.d(TAG, "Permissions denied");
                // Handle the case where permissions are denied
            }
        }
    }

    private void initializeNfcDevice() {
        uartNfcDevice = new UartNfcDevice();
        uartNfcDevice.setCallBack(deviceManagerCallback);

        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    Thread.sleep(3000);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
                uartNfcDevice.serialManager.open("/dev/ttyS9", "115200");
            }
        }).start();
    }

    private DeviceManagerCallback deviceManagerCallback = new DeviceManagerCallback() {
        @Override
        public void onReceiveRfnSearchCard(boolean blnIsSus, int cardType, byte[] bytCardSn, byte[] bytCarATS) {
            super.onReceiveRfnSearchCard(blnIsSus, cardType, bytCardSn, bytCarATS);
            Log.d(TAG, "Card detected: " + bytesToHex(bytCardSn));
        }

        @Override
        public void onReceiveSamVIdStart(byte[] initData) {
            super.onReceiveSamVIdStart(initData);
            Log.d(TAG, "Start parsing");
        }

        @Override
        public void onReceiveSamVIdSchedule(int rate) {
            super.onReceiveSamVIdSchedule(rate);
        }

        @Override
        public void onReceiveSamVIdException(String msg) {
            super.onReceiveSamVIdException(msg);
            Log.d(TAG, "Exception: " + msg);
        }

        @Override
        public void onReceiveIDCardData(IDCardData idCardData) {
            super.onReceiveIDCardData(idCardData);
            Log.d(TAG, "ID Card Data: " + idCardData.toString());
        }

        @Override
        public void onReceiveCardLeave() {
            super.onReceiveCardLeave();
            Log.d(TAG, "Card left");
        }
    };

    private String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02X", b));
        }
        return sb.toString();
    }
}