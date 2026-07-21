package com.apolos.shield.vpn

import android.content.Context
import com.apolos.shield.core.EventKind
import com.apolos.shield.core.SecurityState
import com.apolos.shield.core.Severity
import com.wireguard.android.backend.Backend
import com.wireguard.android.backend.GoBackend
import com.wireguard.android.backend.Tunnel
import com.wireguard.config.Config
import java.io.StringReader

/**
 * Connects an *encrypted* tunnel to a free WireGuard VPN using a config the user
 * imports on the VPN screen. Free providers (e.g. ProtonVPN's free tier) hand
 * out a standard WireGuard `.conf` — paste it in and this brings the tunnel up
 * via the official wireguard-android Go backend.
 *
 * Note (honest limitation): stock, non-rooted Android allows only ONE active
 * VpnService at a time, so you cannot literally stack several VPN tunnels. The
 * multi-layer protection here is: WireGuard encryption + a filtering DNS set in
 * the config + the app firewall/behaviour monitors running alongside.
 */
object WireGuardConnection {

    private var backend: Backend? = null
    private var tunnel: ApolosTunnel? = null

    private class ApolosTunnel(private val onState: (Tunnel.State) -> Unit) : Tunnel {
        override fun getName(): String = "apolos"
        override fun onStateChange(newState: Tunnel.State) = onState(newState)
    }

    private const val MODE_WIREGUARD = "wireguard"

    fun connect(context: Context, confText: String) {
        val appCtx = context.applicationContext

        // Parse first (cheap, main-thread-safe). A bad config throws here before
        // any VPN is touched, so on failure we leave whatever is currently active
        // (DNS filter, kill-switch, or an already-running WireGuard tunnel) as-is.
        val config = try {
            Config.parse(StringReader(confText).buffered())
        } catch (e: Exception) {
            SecurityState.addEvent(
                EventKind.VPN, Severity.WARNING,
                appCtx.getString(com.apolos.shield.R.string.wg_error_title),
                e.message ?: "invalid config",
            )
            return
        }

        // Remember what protection is active so we can restore it if the switch
        // fails partway through.
        val previousMode = SecurityState.status.value.vpnMode

        // GoBackend.setState starts its own VpnService and blocks until that
        // service's onCreate completes — and those lifecycle callbacks are posted
        // to the main thread. Running this on the main thread would therefore
        // dead-lock the first connection, so do all of it on a background thread.
        Thread({
            // A local ShieldVpnService tunnel (kill-switch drops everything, DNS
            // mode routes port 53) would otherwise intercept the peer Endpoint
            // hostname resolution, so tear it down first — only one VpnService can
            // be active at a time anyway.
            if (previousMode == ShieldVpnService.MODE_DNS || previousMode == ShieldVpnService.MODE_KILLSWITCH) {
                ShieldVpnService.stop(appCtx)
            }
            try {
                val be = backend ?: GoBackend(appCtx).also { backend = it }
                val t = ApolosTunnel { state ->
                    val up = state == Tunnel.State.UP
                    SecurityState.updateStatus {
                        it.copy(vpnActive = up, vpnMode = if (up) MODE_WIREGUARD else ShieldVpnService.MODE_OFF)
                    }
                }
                be.setState(t, Tunnel.State.UP, config)
                // Only adopt the new handle once the tunnel is actually up; on a
                // failed replacement GoBackend keeps the previous tunnel and the
                // old handle must stay valid for disconnect().
                tunnel = t
                SecurityState.addEvent(
                    EventKind.VPN, Severity.INFO,
                    appCtx.getString(com.apolos.shield.R.string.wg_connected_title),
                    appCtx.getString(com.apolos.shield.R.string.wg_connected_detail),
                )
            } catch (e: Exception) {
                SecurityState.addEvent(
                    EventKind.VPN, Severity.WARNING,
                    appCtx.getString(com.apolos.shield.R.string.wg_error_title),
                    e.message ?: "connect failed",
                )
                // Restore whatever protection we displaced so a failed switch
                // doesn't silently leave the device unprotected.
                when (previousMode) {
                    ShieldVpnService.MODE_DNS, ShieldVpnService.MODE_KILLSWITCH ->
                        ShieldVpnService.start(appCtx, previousMode)
                    MODE_WIREGUARD -> {
                        // GoBackend restored the previously-active tunnel; leave
                        // the dashboard reflecting it as still connected.
                    }
                    else -> SecurityState.updateStatus {
                        it.copy(vpnActive = false, vpnMode = ShieldVpnService.MODE_OFF)
                    }
                }
            }
        }, "apolos-wg-connect").start()
    }

    fun disconnect(context: Context) {
        val be = backend ?: return
        val t = tunnel ?: return
        // setState also blocks on the VpnService lifecycle, so keep it off the
        // main thread as well.
        Thread({
            runCatching { be.setState(t, Tunnel.State.DOWN, null) }
            tunnel = null
            SecurityState.updateStatus {
                it.copy(vpnActive = false, vpnMode = ShieldVpnService.MODE_OFF)
            }
        }, "apolos-wg-disconnect").start()
    }
}
