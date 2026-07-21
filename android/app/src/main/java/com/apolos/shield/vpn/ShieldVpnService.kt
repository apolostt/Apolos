package com.apolos.shield.vpn

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.ParcelFileDescriptor
import com.apolos.shield.MainActivity
import com.apolos.shield.R
import com.apolos.shield.alarm.Notifier
import com.apolos.shield.core.EventKind
import com.apolos.shield.core.SecurityState
import com.apolos.shield.core.Severity
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.ExecutorService
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * On-device protective tunnel. Two server-less modes that work without any
 * remote provider:
 *
 *  - DNS filtering: only DNS is routed into the tunnel; tracker/malware
 *    look-ups are answered with NXDOMAIN, everything else is forwarded in
 *    plaintext over UDP/53 to [UPSTREAM_DNS] (queries are not encrypted —
 *    only filtered). Acts as a system-wide ad/tracker blocker for every app.
 *  - Kill-switch: routes *all* traffic into the tunnel and drops it — an
 *    instant "cut the network" panic button (e.g. the moment spyware is found).
 *
 * For an encrypted off-device tunnel the app uses [WireGuardConnection] with a
 * free WireGuard config the user imports (see the VPN screen).
 */
class ShieldVpnService : VpnService() {

    private val running = AtomicBoolean(false)
    private var tun: ParcelFileDescriptor? = null
    private var worker: Thread? = null
    private var dnsPool: ExecutorService? = null
    private var mode = MODE_DNS
    private lateinit var filter: DnsFilter

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> { stopTunnel(); stopSelf(); return START_NOT_STICKY }
            else -> {
                val requestedMode = intent?.getStringExtra(EXTRA_MODE) ?: MODE_DNS
                if (running.get() && requestedMode != mode) {
                    // Switching modes (e.g. DNS filter -> kill-switch) needs a fresh
                    // TUN with different routes, not just a flipped mode flag.
                    stopTunnel()
                }
                mode = requestedMode
            }
        }
        filter = DnsFilter(this)
        startForeground(
            Notifier.VPN_FOREGROUND_ID,
            Notifier.foregroundNotification(this, getString(R.string.vpn_running, mode)),
        )
        startTunnel()
        return START_STICKY
    }

    private fun startTunnel() {
        if (running.getAndSet(true)) return
        val builder = Builder()
            .setSession(getString(R.string.app_name))
            .setMtu(1500)
            .addAddress(VIRTUAL_ADDR, 24)
            .setConfigureIntent(
                PendingIntent.getActivity(
                    this, 0, Intent(this, MainActivity::class.java),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
            )

        when (mode) {
            MODE_KILLSWITCH -> {
                // Route everything into the tunnel and never forward it.
                builder.addRoute("0.0.0.0", 0)
                builder.addRoute("::", 0)
            }
            else -> {
                // DNS-only: capture just look-ups to our virtual resolver.
                builder.addDnsServer(VIRTUAL_DNS)
                builder.addRoute(VIRTUAL_DNS, 32)
            }
        }

        tun = try {
            builder.establish()
        } catch (e: Exception) {
            fail(getString(R.string.vpn_failed, e.message ?: "")); return
        }
        if (tun == null) { fail(getString(R.string.vpn_no_permission)); return }

        SecurityState.updateStatus { it.copy(vpnActive = true, vpnMode = mode) }
        SecurityState.addEvent(
            EventKind.VPN, Severity.INFO,
            getString(R.string.vpn_started_title),
            getString(R.string.vpn_started_detail, mode),
        )

        // Bounded pool for upstream DNS forwarding. CallerRunsPolicy applies
        // back-pressure (the reader runs the task itself) if a flood of look-ups
        // fills the queue, so work never grows unbounded and queries are never
        // dropped.
        dnsPool = ThreadPoolExecutor(
            2, 16, 30L, TimeUnit.SECONDS,
            LinkedBlockingQueue(256),
            ThreadPoolExecutor.CallerRunsPolicy(),
        )

        worker = Thread({ loop() }, "apolos-vpn").also { it.start() }
    }

    private fun loop() {
        val fd = tun ?: return
        val input = FileInputStream(fd.fileDescriptor)
        val output = FileOutputStream(fd.fileDescriptor)
        val writeLock = Any() // packet writes come from the reader + pool threads
        val buffer = ByteArray(32767)
        var blockedCount = 0L

        while (running.get()) {
            val n = try { input.read(buffer) } catch (_: Exception) { break }
            if (n <= 0) continue
            if (mode == MODE_KILLSWITCH) continue // drop — network is cut

            val pkt = buffer.copyOf(n)
            if (PacketUtils.ipVersion(pkt) != 4) continue
            if (PacketUtils.protocol(pkt) != PacketUtils.PROTO_UDP) continue
            val ihl = PacketUtils.ihlBytes(pkt)
            if (PacketUtils.udpDstPort(pkt, ihl) != 53) continue

            val dns = PacketUtils.udpPayload(pkt, ihl, n)
            val host = PacketUtils.dnsQueryName(dns)

            if (filter.isBlocked(host)) {
                val reply = PacketUtils.buildUdpResponse(pkt, ihl, PacketUtils.buildNxDomain(dns))
                synchronized(writeLock) { runCatching { output.write(reply) } }
                blockedCount++
                SecurityState.updateStatus { it.copy(blockedDnsCount = blockedCount) }
            } else {
                // Forward off the reader thread so a slow/unresponsive upstream
                // (up to the 4 s socket timeout) can't stall every other look-up.
                dnsPool?.execute {
                    val answer = forwardDns(dns) ?: return@execute
                    val reply = PacketUtils.buildUdpResponse(pkt, ihl, answer)
                    synchronized(writeLock) { runCatching { output.write(reply) } }
                }
            }
        }
    }

    /** Forwards a single query over its own protected socket (thread-safe). */
    private fun forwardDns(query: ByteArray): ByteArray? = try {
        DatagramSocket().use { socket ->
            protect(socket)
            socket.soTimeout = 4000
            val server = InetAddress.getByName(UPSTREAM_DNS)
            socket.send(DatagramPacket(query, query.size, InetSocketAddress(server, 53)))
            val respBuf = ByteArray(1500)
            val resp = DatagramPacket(respBuf, respBuf.size)
            socket.receive(resp)
            respBuf.copyOf(resp.length)
        }
    } catch (_: Exception) { null }

    private fun stopTunnel() {
        running.set(false)
        worker?.interrupt()
        dnsPool?.shutdownNow()
        dnsPool = null
        runCatching { tun?.close() }
        tun = null
        SecurityState.updateStatus { it.copy(vpnActive = false, vpnMode = MODE_OFF) }
        SecurityState.addEvent(
            EventKind.VPN, Severity.INFO,
            getString(R.string.vpn_stopped_title), getString(R.string.vpn_stopped_detail),
        )
    }

    private fun fail(msg: String) {
        running.set(false)
        SecurityState.updateStatus { it.copy(vpnActive = false, vpnMode = MODE_OFF) }
        SecurityState.addEvent(
            EventKind.VPN, Severity.WARNING, getString(R.string.vpn_error_title), msg,
        )
    }

    override fun onDestroy() {
        stopTunnel()
        super.onDestroy()
    }

    override fun onRevoke() {
        stopTunnel(); stopSelf()
        super.onRevoke()
    }

    companion object {
        const val MODE_OFF = "off"
        const val MODE_DNS = "dns"
        const val MODE_KILLSWITCH = "killswitch"

        const val EXTRA_MODE = "mode"
        const val ACTION_STOP = "com.apolos.shield.vpn.STOP"

        private const val VIRTUAL_ADDR = "10.111.222.1"
        private const val VIRTUAL_DNS = "10.111.222.2"
        // Cloudflare resolver, queried in plaintext over UDP/53 (see forwardDns()).
        // This mode only filters queries, it does not encrypt them; use the
        // WireGuard mode instead if you need an encrypted DNS path too.
        private const val UPSTREAM_DNS = "1.1.1.1"

        fun start(context: Context, mode: String) {
            val i = Intent(context, ShieldVpnService::class.java).putExtra(EXTRA_MODE, mode)
            context.startForegroundService(i)
        }

        fun stop(context: Context) {
            val i = Intent(context, ShieldVpnService::class.java).setAction(ACTION_STOP)
            context.startService(i)
        }
    }
}
