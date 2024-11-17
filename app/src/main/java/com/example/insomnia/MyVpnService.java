package com.example.insomnia;

import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.VpnService;
import android.os.ParcelFileDescriptor;
import android.util.Log;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;

public class MyVpnService extends VpnService {
    private static final String TAG = "MyVpnService";
    public static final String VPN_STATUS_INTENT = "com.example.insomnia.VPN_STATUS";
    public static final String VPN_STATUS_KEY = "status";
    public static final String VPN_STATUS_SUCCESS = "success";
    public static final String VPN_STATUS_FAILURE = "failure";

    private ParcelFileDescriptor vpnInterface;
    private Thread packetForwardingThread;
    private ConnectivityManager.NetworkCallback networkCallback;
    private ConnectivityManager connectivityManager;

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.d(TAG, "VPN Service starting...");

        if (VpnService.prepare(this) != null) {
            Log.e(TAG, "VPN permission not granted.");
            sendVpnStatusBroadcast(VPN_STATUS_FAILURE);
            stopSelf();
            return START_NOT_STICKY;
        }

        stopCurrentVpnInterface();

        try {
            setupVpn();
            monitorNetworkChanges();
            startPacketForwarding();
        } catch (Exception e) {
            Log.e(TAG, "Unexpected error in VPN setup", e);
            sendVpnStatusBroadcast(VPN_STATUS_FAILURE);
        }

        return START_STICKY;
    }

    private void setupVpn() throws Exception {
        Builder builder = new Builder();

        Log.d(TAG, "Setting session name...");
        builder.setSession("MyVPN");

        Log.d(TAG, "Adding VPN address...");
        builder.addAddress("192.168.200.1", 24);

        Log.d(TAG, "Adding DNS servers...");
        builder.addDnsServer("1.1.1.1"); // Cloudflare
        builder.addDnsServer("8.8.8.8"); // Google
        builder.addDnsServer("9.9.9.9"); // Quad9
        builder.addDnsServer("208.67.222.222"); // OpenDNS

        builder.addRoute("1.1.1.1", 32); // Cloudflare
        builder.addRoute("8.8.8.8", 32); // Google DNS
        builder.addRoute("9.9.9.9", 32); // Quad9
        builder.addRoute("208.67.222.222", 32); // OpenDNS


        Log.d(TAG, "Routing all traffic...");
        builder.addRoute("0.0.0.0", 0); // Route all IPv4 traffic
        builder.addRoute("::", 0);      // Route all IPv6 traffic

        builder.setMtu(1400);

        Log.d(TAG, "Establishing VPN interface...");
        vpnInterface = builder.establish();

        if (vpnInterface != null) {
            Log.d(TAG, "VPN established successfully.");
            sendVpnStatusBroadcast(VPN_STATUS_SUCCESS);
        } else {
            throw new Exception("Failed to establish VPN interface.");
        }
    }

    private void startPacketForwarding() {
        packetForwardingThread = new Thread(() -> {
            try (FileInputStream in = new FileInputStream(vpnInterface.getFileDescriptor());
                 FileOutputStream out = new FileOutputStream(vpnInterface.getFileDescriptor())) {

                ByteBuffer packet = ByteBuffer.allocate(32767);

                while (!Thread.currentThread().isInterrupted()) {
                    int length = in.read(packet.array());
                    if (length > 0) {
                        if (isDnsQuery(packet.array(), length)) {
                            boolean success = forwardDnsQuery(packet.array(), length, out);
                            if (!success) {
                                Log.e(TAG, "Failed to forward DNS query.");
                            }
                        }
                    }
                }
            } catch (Exception e) {
                if (!Thread.currentThread().isInterrupted()) {
                    Log.e(TAG, "Error in packet forwarding", e);
                } else {
                    Log.d(TAG, "Packet forwarding thread interrupted.");
                }
            }
        });

        packetForwardingThread.start();
    }


    private boolean isDnsQuery(byte[] packet, int length) {
        if (length >= 28) { // Minimum UDP header size + IP header
            int destPort = ((packet[22] & 0xFF) << 8) | (packet[23] & 0xFF); // Destination port
            return destPort == 53; // Port 53 is for DNS
        }
        return false;
    }

    private boolean forwardDnsQuery(byte[] packet, int length, FileOutputStream out) {
        String[] dnsServers = {"1.1.1.1", "8.8.8.8", "9.9.9.9", "208.67.222.222"};
        for (String dnsServer : dnsServers) {
            try (DatagramSocket socket = new DatagramSocket()) {
                socket.connect(new InetSocketAddress(dnsServer, 53));
                DatagramPacket dnsRequest = new DatagramPacket(packet, length);
                socket.send(dnsRequest);

                byte[] responseBuffer = new byte[512];
                DatagramPacket dnsResponse = new DatagramPacket(responseBuffer, responseBuffer.length);
                socket.receive(dnsResponse);

                out.write(dnsResponse.getData(), 0, dnsResponse.getLength());
                out.flush();
                Log.d(TAG, "DNS query forwarded to " + dnsServer + " and response received.");
                return true;
            } catch (Exception e) {
                Log.e(TAG, "Error forwarding DNS query to " + dnsServer, e);
            }
        }
        return false; // All DNS servers failed
    }

    private void monitorNetworkChanges() {
        connectivityManager = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);

        networkCallback = new ConnectivityManager.NetworkCallback() {
            @Override
            public void onAvailable(android.net.Network network) {
                Log.d(TAG, "Network available: " + network);
                reconnectVpn();
            }

            @Override
            public void onLost(android.net.Network network) {
                Log.d(TAG, "Network lost: " + network);
            }
        };

        connectivityManager.registerDefaultNetworkCallback(networkCallback);
    }

    private volatile boolean isReconnecting = false;

    private void reconnectVpn() {
        if (isReconnecting) {
            Log.d(TAG, "Reconnection already in progress.");
            return;
        }
        isReconnecting = true;

        Log.d(TAG, "Reconnecting VPN due to network change...");
        stopCurrentVpnInterface();
        try {
            setupVpn();
            startPacketForwarding();
        } catch (Exception e) {
            Log.e(TAG, "Failed to reconnect VPN", e);
        } finally {
            isReconnecting = false;
        }
    }


    private void stopCurrentVpnInterface() {
        if (packetForwardingThread != null && packetForwardingThread.isAlive()) {
            packetForwardingThread.interrupt();
        }

        if (vpnInterface != null) {
            try {
                vpnInterface.close();
            } catch (Exception e) {
                Log.e(TAG, "Error closing VPN interface", e);
            }
            vpnInterface = null;
        }

        if (connectivityManager != null && networkCallback != null) {
            connectivityManager.unregisterNetworkCallback(networkCallback);
            networkCallback = null;
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        stopCurrentVpnInterface();
        sendVpnStatusBroadcast(VPN_STATUS_FAILURE);
        Log.d(TAG, "VPN Service destroyed.");
    }

    private void sendVpnStatusBroadcast(String status) {
        Intent intent = new Intent(VPN_STATUS_INTENT);
        intent.putExtra(VPN_STATUS_KEY, status);
        sendBroadcast(intent);
    }
}
