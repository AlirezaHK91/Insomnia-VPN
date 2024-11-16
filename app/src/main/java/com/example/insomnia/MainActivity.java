package com.example.insomnia;

import android.content.Intent;
import android.net.VpnService;
import android.os.Bundle;
import android.widget.Button;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

public class MainActivity extends AppCompatActivity {
    private static final int VPN_REQUEST_CODE = 100;
    private TextView vpnStatusTextView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        vpnStatusTextView = findViewById(R.id.vpnStatusTextView);
        Button startVpnButton = findViewById(R.id.startVpnButton);

        startVpnButton.setOnClickListener(v -> startVpn());
    }

    private void startVpn() {
        Intent intent = VpnService.prepare(this);
        if (intent != null) {
            startActivityForResult(intent, VPN_REQUEST_CODE);
        } else {
            startVpnService();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == VPN_REQUEST_CODE) {
            if (resultCode == RESULT_OK) {
                startVpnService();
            } else {
                vpnStatusTextView.setText("VPN permission denied.");
            }
        }
    }

    private void startVpnService() {
        Intent vpnIntent = new Intent(this, MyVpnService.class);
        startService(vpnIntent);
        vpnStatusTextView.setText("Starting VPN...");
    }
}
