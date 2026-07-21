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

    fun connect(context: Context, confText: String): Boolean {
        return try {
            val config = Config.parse(StringReader(confText).buffered())
            val be = backend ?: GoBackend(context.applicationContext).also { backend = it }
            val t = ApolosTunnel { state ->
                val up = state == Tunnel.State.UP
                SecurityState.updateStatus {
                    it.copy(vpnActive = up, vpnMode = if (up) "wireguard" else ShieldVpnService.MODE_OFF)
                }
            }.also { tunnel = it }
            be.setState(t, Tunnel.State.UP, config)
            SecurityState.addEvent(
                EventKind.VPN, Severity.INFO,
                context.getString(com.apolos.shield.R.string.wg_connected_title),
                context.getString(com.apolos.shield.R.string.wg_connected_detail),
            )
            true
        } catch (e: Exception) {
            // Parsing or bringing up the tunnel failed — make sure the dashboard
            // doesn't keep showing an active/stale VPN state from this attempt.
            SecurityState.updateStatus {
                it.copy(vpnActive = false, vpnMode = ShieldVpnService.MODE_OFF)
            }
            SecurityState.addEvent(
                EventKind.VPN, Severity.WARNING,
                context.getString(com.apolos.shield.R.string.wg_error_title),
                e.message ?: "invalid config",
            )
            false
        }
    }

    fun disconnect(context: Context) {
        val be = backend ?: return
        val t = tunnel ?: return
        runCatching { be.setState(t, Tunnel.State.DOWN, null) }
        SecurityState.updateStatus {
            it.copy(vpnActive = false, vpnMode = ShieldVpnService.MODE_OFF)
        }
    }
}
