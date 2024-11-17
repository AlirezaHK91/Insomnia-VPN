package com.example.insomnia;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.VpnService;
import android.os.Bundle;
import android.util.Log;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;
import androidx.core.content.ContextCompat;

import androidx.appcompat.app.AppCompatActivity;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "MainActivity";
    private static final int VPN_REQUEST_CODE = 100;

    private TextView statusTextView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        Button startVpnButton = findViewById(R.id.startVpnButton);
        Button stopVpnButton = findViewById(R.id.stopVpnButton);
        statusTextView = findViewById(R.id.statusTextView);

        // Start VPN
        startVpnButton.setOnClickListener(v -> startVpn());

        // Stop VPN
        stopVpnButton.setOnClickListener(v -> stopVpn());
    }

    private void startVpn() {
        Intent intent = VpnService.prepare(this);
        if (intent != null) {
            Log.d(TAG, "Requesting VPN permission");
            startActivityForResult(intent, VPN_REQUEST_CODE);
        } else {
            Log.d(TAG, "VPN permission already granted");
            onActivityResult(VPN_REQUEST_CODE, RESULT_OK, null);
        }
    }

    private void stopVpn() {
        Log.d(TAG, "Stopping VPN service");
        stopService(new Intent(this, MyVpnService.class));
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == VPN_REQUEST_CODE && resultCode == RESULT_OK) {
            Log.d(TAG, "Starting VPN service");
            Intent intent = new Intent(this, MyVpnService.class);
            startService(intent);
        } else {
            Log.e(TAG, "VPN permission denied");
            Toast.makeText(this, "VPN permission denied", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        IntentFilter filter = new IntentFilter(MyVpnService.VPN_STATUS_BROADCAST);
        ContextCompat.registerReceiver(
                this,
                vpnStatusReceiver,
                filter,
                ContextCompat.RECEIVER_NOT_EXPORTED
        );
    }

    @Override
    protected void onPause() {
        super.onPause();
        unregisterReceiver(vpnStatusReceiver);
    }

    private final BroadcastReceiver vpnStatusReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (MyVpnService.VPN_STATUS_BROADCAST.equals(intent.getAction())) {
                String status = intent.getStringExtra(MyVpnService.VPN_STATUS_KEY);
                Log.d(TAG, "VPN Status: " + status);
                Toast.makeText(context, status, Toast.LENGTH_SHORT).show();
                statusTextView.setText(status);
            }
        }
    };
}
