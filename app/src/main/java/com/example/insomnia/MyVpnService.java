package com.example.insomnia;

import android.content.Intent;
import android.net.VpnService;
import android.os.ParcelFileDescriptor;
import android.util.Log;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.DatagramChannel;

public class MyVpnService extends VpnService {

    private static final String TAG = "MyVpnService";
    public static final String VPN_STATUS_BROADCAST = "com.example.insomnia.VPN_STATUS";
    public static final String VPN_STATUS_KEY = "status";

    private Thread vpnThread;
    private ParcelFileDescriptor vpnInterface;

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startVpn();
        return START_STICKY;
    }

    private void startVpn() {
        if (vpnThread != null && vpnThread.isAlive()) {
            Log.d(TAG, "VPN already running");
            return;
        }

        vpnThread = new Thread(() -> {
            try {
                Builder builder = new Builder();

                // Configure VPN
                Log.d(TAG, "Configuring VPN");
                builder.addAddress("10.0.0.2", 24); // VPN private IP
                builder.addRoute("8.8.8.8", 32);   // Explicit route for Google's DNS
                builder.addRoute("1.1.1.1", 32);   // Explicit route for Cloudflare DNS
                //builder.addRoute("0.0.0.0", 0);     // Route all traffic through VPN

                // Add DNS servers
                Log.d(TAG, "Adding DNS servers");
                builder.addDnsServer("8.8.8.8");
                builder.addDnsServer("8.8.4.4");
                builder.addDnsServer("1.1.1.1");
                builder.addDnsServer("1.0.0.1");

                // Exclude VPN app traffic
                try {
                    builder.addDisallowedApplication(getPackageName());
                    Log.d(TAG, "Excluded VPN app from VPN routing");
                } catch (Exception e) {
                    Log.e(TAG, "Error excluding VPN app", e);
                }

                // Establish VPN interface
                vpnInterface = builder.setSession("Insomnia VPN").establish();
                if (vpnInterface != null) {
                    Log.d(TAG, "VPN connected successfully");
                    sendVpnStatusBroadcast("VPN Connected");
                }

                // Start handling traffic
                handleTraffic();

            } catch (Exception e) {
                Log.e(TAG, "VPN connection error", e);
                sendVpnStatusBroadcast("VPN Connection Failed");
            }
        });
        vpnThread.start();
    }

    private void handleTraffic() {
        FileInputStream inputStream = null;
        FileOutputStream outputStream = null;
        DatagramChannel forwardChannel = null;

        try {
            if (vpnInterface == null) {
                throw new IllegalStateException("VPN interface is not established");
            }

            inputStream = new FileInputStream(vpnInterface.getFileDescriptor());
            outputStream = new FileOutputStream(vpnInterface.getFileDescriptor());
            forwardChannel = DatagramChannel.open();
            forwardChannel.configureBlocking(false);

            byte[] packet = new byte[32767];
            ByteBuffer buffer = ByteBuffer.wrap(packet);

            while (!Thread.interrupted()) {
                int length = inputStream.read(packet);
                if (length > 0) {
                    Log.d(TAG, "Packet read: " + length + " bytes");

                    if (!isIPv4(packet)) {
                        Log.w(TAG, "Non-IPv4 packet received, ignoring...");
                        continue;
                    }

                    InetAddress sourceAddress = getIPv4SourceAddress(packet);
                    InetAddress destAddress = getIPv4DestinationAddress(packet);
                    int destPort = getIPv4DestinationPort(packet);

                    // Log packet details
                    Log.d(TAG, "Packet details: Source=" + sourceAddress.getHostAddress() +
                            ", Destination=" + destAddress.getHostAddress() +
                            ", Port=" + destPort);

                    // Ignore packets from the VPN interface itself unless destined for external networks
                    if (sourceAddress.equals(InetAddress.getByName("10.0.0.2")) && !destAddress.isLoopbackAddress()) {
                        Log.d(TAG, "Packet from VPN interface to external network: Forwarding");
                    } else if (sourceAddress.equals(InetAddress.getByName("10.0.0.2"))) {
                        Log.d(TAG, "Packet from VPN interface ignored");
                        continue;
                    }

                    if (destPort == -1) {
                        Log.e(TAG, "Invalid destination port, dropping packet");
                        continue;
                    }

                    // Forward the packet
                    Log.d(TAG, "Forwarding packet to " + destAddress.getHostAddress() + ":" + destPort);
                    try {
                        buffer.clear();
                        buffer.put(packet, 0, length);
                        buffer.flip();
                        forwardChannel.send(buffer, new InetSocketAddress(destAddress, destPort));
                    } catch (IOException e) {
                        Log.e(TAG, "Error forwarding packet", e);
                    }
                }
            }
        } catch (IOException e) {
            Log.e(TAG, "Error handling VPN traffic", e);
        } finally {
            try {
                if (inputStream != null) inputStream.close();
                if (outputStream != null) outputStream.close();
                if (forwardChannel != null) forwardChannel.close();
            } catch (IOException e) {
                Log.e(TAG, "Error closing resources", e);
            }
        }
    }


    private boolean isIPv4(byte[] packet) {
        return (packet[0] >> 4) == 4; // The first 4 bits of the first byte indicate the IP version
    }

    private boolean isICMP(byte[] packet) {
        return (packet[9] & 0xFF) == 1; // Protocol field in IPv4 header is 1 for ICMP
    }

    private InetAddress getIPv4DestinationAddress(byte[] packet) throws IOException {
        byte[] destAddress = new byte[4];
        System.arraycopy(packet, 16, destAddress, 0, 4); // Bytes 16-19 in IPv4 header are the destination address
        return InetAddress.getByAddress(destAddress);
    }

    private InetAddress getIPv4SourceAddress(byte[] packet) throws IOException {
        byte[] sourceAddress = new byte[4];
        System.arraycopy(packet, 12, sourceAddress, 0, 4); // Bytes 12-15 in IPv4 header are the source address
        return InetAddress.getByAddress(sourceAddress);
    }

    private int getIPv4DestinationPort(byte[] packet) {
        int headerLength = (packet[0] & 0x0F) * 4; // Header length in bytes
        int port = ((packet[headerLength] & 0xFF) << 8) | (packet[headerLength + 1] & 0xFF); // Destination port
        return port > 0 && port <= 65535 ? port : -1; // Return -1 for invalid ports
    }

    private void sendVpnStatusBroadcast(String status) {
        Intent broadcastIntent = new Intent(VPN_STATUS_BROADCAST);
        broadcastIntent.putExtra(VPN_STATUS_KEY, status);
        sendBroadcast(broadcastIntent);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (vpnInterface != null) {
            try {
                vpnInterface.close();
            } catch (IOException e) {
                Log.e(TAG, "Error closing VPN interface", e);
            }
        }
        if (vpnThread != null) {
            vpnThread.interrupt();
        }
        Log.d(TAG, "VPN disconnected");
        sendVpnStatusBroadcast("VPN Disconnected");
    }
}
