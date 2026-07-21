package com.apolos.shieldlite;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.net.VpnService;
import android.os.ParcelFileDescriptor;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Local protective tunnel: DNS filtering (block tracker/malware domains with
 * NXDOMAIN, forward everything else in plaintext UDP to a public resolver) or
 * kill-switch (route all traffic into the tunnel and drop it).
 */
public class ShieldVpnService extends VpnService {

    static final String MODE_OFF = "off";
    static final String MODE_DNS = "dns";
    static final String MODE_KILLSWITCH = "killswitch";
    static final String EXTRA_MODE = "mode";
    static final String ACTION_STOP = "com.apolos.shieldlite.vpn.STOP";

    private static final String VIRTUAL_ADDR = "10.111.222.1";
    private static final String VIRTUAL_DNS = "10.111.222.2";
    private static final String UPSTREAM_DNS = "1.1.1.1"; // plaintext UDP/53, not encrypted

    private final AtomicBoolean running = new AtomicBoolean(false);
    private ParcelFileDescriptor tun;
    private Thread worker;
    private String mode = MODE_DNS;
    private DnsFilter filter;

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopTunnel();
            stopSelf();
            return START_NOT_STICKY;
        }
        String requestedMode = intent != null && intent.getStringExtra(EXTRA_MODE) != null
                ? intent.getStringExtra(EXTRA_MODE) : MODE_DNS;
        if (running.get() && !requestedMode.equals(mode)) {
            // Switching modes needs a fresh TUN with different routes.
            stopTunnel();
        }
        mode = requestedMode;

        filter = new DnsFilter(this);
        startForeground(Notifier.VPN_FOREGROUND_ID,
                Notifier.foregroundNotification(this, "Protective VPN running (" + mode + ")"));
        startTunnel();
        return START_STICKY;
    }

    private void startTunnel() {
        if (running.getAndSet(true)) return;

        Builder builder = new Builder()
                .setSession(getString(R.string.app_name))
                .setMtu(1500)
                .addAddress(VIRTUAL_ADDR, 24)
                .setConfigureIntent(PendingIntent.getActivity(
                        this, 0, new Intent(this, MainActivity.class),
                        PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE));

        if (MODE_KILLSWITCH.equals(mode)) {
            builder.addRoute("0.0.0.0", 0);
            builder.addRoute("::", 0);
        } else {
            builder.addDnsServer(VIRTUAL_DNS);
            builder.addRoute(VIRTUAL_DNS, 32);
        }

        try {
            tun = builder.establish();
        } catch (Exception e) {
            fail("VPN could not start: " + e.getMessage());
            return;
        }
        if (tun == null) {
            fail("VPN permission was not granted.");
            return;
        }

        SecurityState.setVpn(true, mode);
        SecurityState.addEvent(SecurityState.SEV_INFO, "VPN started", "Mode: " + mode);

        worker = new Thread(this::loop, "apolos-vpn");
        worker.start();
    }

    private void loop() {
        ParcelFileDescriptor fd = tun;
        if (fd == null) return;
        FileInputStream input = new FileInputStream(fd.getFileDescriptor());
        FileOutputStream output = new FileOutputStream(fd.getFileDescriptor());
        byte[] buffer = new byte[32767];

        try (DatagramSocket upstream = new DatagramSocket()) {
            protect(upstream);
            upstream.setSoTimeout(4000);
            while (running.get()) {
                int n;
                try {
                    n = input.read(buffer);
                } catch (Exception e) {
                    break;
                }
                if (n <= 0) continue;
                if (MODE_KILLSWITCH.equals(mode)) continue; // drop — network is cut

                byte[] pkt = new byte[n];
                System.arraycopy(buffer, 0, pkt, 0, n);
                if (PacketUtils.ipVersion(pkt) != 4) continue;
                if (PacketUtils.protocol(pkt) != PacketUtils.PROTO_UDP) continue;
                int ihl = PacketUtils.ihlBytes(pkt);
                if (PacketUtils.udpDstPort(pkt, ihl) != 53) continue;

                byte[] dns = PacketUtils.udpPayload(pkt, ihl, n);
                String host = PacketUtils.dnsQueryName(dns);

                if (filter.isBlocked(host)) {
                    byte[] reply = PacketUtils.buildUdpResponse(pkt, ihl, PacketUtils.buildNxDomain(dns));
                    try { output.write(reply); } catch (Exception ignored) { }
                    SecurityState.incrementBlockedDns();
                } else {
                    byte[] answer = forwardDns(dns, upstream);
                    if (answer != null) {
                        byte[] reply = PacketUtils.buildUdpResponse(pkt, ihl, answer);
                        try { output.write(reply); } catch (Exception ignored) { }
                    }
                }
            }
        } catch (Exception ignored) {
        }
    }

    private byte[] forwardDns(byte[] query, DatagramSocket socket) {
        try {
            InetAddress server = InetAddress.getByName(UPSTREAM_DNS);
            socket.send(new DatagramPacket(query, query.length, new InetSocketAddress(server, 53)));
            byte[] respBuf = new byte[1500];
            DatagramPacket resp = new DatagramPacket(respBuf, respBuf.length);
            socket.receive(resp);
            byte[] out = new byte[resp.getLength()];
            System.arraycopy(respBuf, 0, out, 0, out.length);
            return out;
        } catch (Exception e) {
            return null;
        }
    }

    private void stopTunnel() {
        running.set(false);
        if (worker != null) worker.interrupt();
        try { if (tun != null) tun.close(); } catch (Exception ignored) { }
        tun = null;
        SecurityState.setVpn(false, MODE_OFF);
        SecurityState.addEvent(SecurityState.SEV_INFO, "VPN stopped", "The protective tunnel is off.");
    }

    private void fail(String msg) {
        running.set(false);
        SecurityState.setVpn(false, MODE_OFF);
        SecurityState.addEvent(SecurityState.SEV_WARNING, "VPN error", msg);
    }

    @Override
    public void onDestroy() {
        stopTunnel();
        super.onDestroy();
    }

    @Override
    public void onRevoke() {
        stopTunnel();
        stopSelf();
        super.onRevoke();
    }

    static void start(Context ctx, String mode) {
        Intent i = new Intent(ctx, ShieldVpnService.class).putExtra(EXTRA_MODE, mode);
        ctx.startForegroundService(i);
    }

    static void stop(Context ctx) {
        Intent i = new Intent(ctx, ShieldVpnService.class).setAction(ACTION_STOP);
        ctx.startService(i);
    }
}
