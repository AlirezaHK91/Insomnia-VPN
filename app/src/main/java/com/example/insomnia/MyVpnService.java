package com.example.insomnia;

import android.content.Intent;
import android.net.VpnService;
import android.os.ParcelFileDescriptor;
import android.util.Log;

public class MyVpnService extends VpnService {
    private static final String TAG = "MyVpnService";
    private ParcelFileDescriptor vpnInterface;

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.d(TAG, "VPN Service starting...");

        if (VpnService.prepare(this) != null) {
            Log.e(TAG, "VPN permission not granted.");
            stopSelf();
            return START_NOT_STICKY;
        }

        if (vpnInterface != null) {
            try {
                vpnInterface.close();
            } catch (Exception e) {
                Log.e(TAG, "Error closing existing VPN interface", e);
            }
        }

        Builder builder = new Builder();

        try {
            builder.setSession("MyVPN")
                    .addAddress("192.168.50.1", 32)
                    .addDnsServer("8.8.8.8")
                    .addRoute("0.0.0.0", 0);

            vpnInterface = builder.establish();

            if (vpnInterface != null) {
                Log.d(TAG, "VPN established successfully.");
            } else {
                Log.e(TAG, "Failed to establish VPN.");
            }
        } catch (Exception e) {
            Log.e(TAG, "Unexpected error while establishing VPN", e);
        }

        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();

        if (vpnInterface != null) {
            try {
                vpnInterface.close();
            } catch (Exception e) {
                Log.e(TAG, "Error closing VPN interface", e);
            }
        }
    }
}
