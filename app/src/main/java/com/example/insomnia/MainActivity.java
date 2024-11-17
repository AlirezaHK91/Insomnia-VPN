package com.example.insomnia;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.VpnService;
import android.os.Bundle;
import android.widget.Button;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

public class MainActivity extends AppCompatActivity {
    private static final int VPN_REQUEST_CODE = 100;

    private TextView vpnStatusTextView;
    private TextView dnsResultTextView;
    private Button toggleVpnButton;

    private boolean isVpnConnected = false;

    private BroadcastReceiver vpnStatusReceiver;
    private DnsResolver dnsResolver;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        vpnStatusTextView = findViewById(R.id.vpnStatusTextView);
        dnsResultTextView = findViewById(R.id.dnsResultTextView);
        toggleVpnButton = findViewById(R.id.toggleVpnButton);

        dnsResolver = new DnsResolver();

        toggleVpnButton.setOnClickListener(v -> {
            if (isVpnConnected) {
                stopVpnService();
            } else {
                requestVpnPermission();
            }
        });

        vpnStatusReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                String status = intent.getStringExtra(MyVpnService.VPN_STATUS_KEY);
                if (MyVpnService.VPN_STATUS_SUCCESS.equals(status)) {
                    isVpnConnected = true;
                    updateUi("VPN Connected", "Disconnect VPN");

                    // Test DNS connectivity with a list of domains
                    String[] testDomains = {
                            "1.1.1.1",        // Cloudflare
                            "8.8.8.8",        // Google
                            "9.9.9.9",        // Quad9
                            "opendns.com",    // OpenDNS
                            "cloudflare-dns.com"
                    };

                    for (String domain : testDomains) {
                        resolveDomain(domain);
                    }
                } else if (MyVpnService.VPN_STATUS_FAILURE.equals(status)) {
                    isVpnConnected = false;
                    updateUi("VPN Disconnected", "Connect VPN");
                    dnsResultTextView.setText(""); // Clear DNS results on disconnection
                }
            }
        };

        IntentFilter filter = new IntentFilter(MyVpnService.VPN_STATUS_INTENT);
        ContextCompat.registerReceiver(this, vpnStatusReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED);

        Intent vpnIntent = new Intent(this, MyVpnService.class);
        startService(vpnIntent);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (vpnStatusReceiver != null) {
            unregisterReceiver(vpnStatusReceiver);
        }
    }

    private void requestVpnPermission() {
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
                updateUi("VPN Permission Denied", "Connect VPN");
            }
        }
    }

    private void startVpnService() {
        Intent vpnIntent = new Intent(this, MyVpnService.class);
        startService(vpnIntent);
        updateUi("Connecting to VPN...", "Disconnect VPN");
    }

    private void stopVpnService() {
        Intent vpnIntent = new Intent(this, MyVpnService.class);
        stopService(vpnIntent);
        isVpnConnected = false;
        updateUi("VPN Disconnected", "Connect VPN");
    }

    private void updateUi(String status, String buttonText) {
        vpnStatusTextView.setText(status);
        toggleVpnButton.setText(buttonText);
    }

    private void resolveDomain(String domain) {
        dnsResultTextView.setText("Resolving " + domain + "...");

        dnsResolver.resolveDomain(domain, new DnsResolver.DnsCallback() {
            @Override
            public void onSuccess(String response) {
                runOnUiThread(() -> dnsResultTextView.setText("Resolved IP: " + response));
            }

            @Override
            public void onFailure(String error) {
                runOnUiThread(() -> dnsResultTextView.setText("DNS Error: " + error));
            }
        });
    }
}
