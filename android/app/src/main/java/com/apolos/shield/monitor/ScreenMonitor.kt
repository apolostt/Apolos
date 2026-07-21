package com.apolos.shield.monitor

import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.view.Display
import com.apolos.shield.alarm.Notifier
import com.apolos.shield.core.EventKind
import com.apolos.shield.core.SecurityState
import com.apolos.shield.core.Severity

/**
 * Watches for the screen being mirrored, cast or captured to another display.
 *
 * On stock Android there is no public API to enumerate which app holds a
 * MediaProjection, but a screen-record / cast session almost always surfaces a
 * second, non-default [Display] (a virtual or presentation display). We treat a
 * new such display as a possible screen-capture and raise the alarm.
 * (On Android 15+ this can be complemented by the window screen-record callback.)
 */
class ScreenMonitor(private val context: Context) {

    private val dm = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
    private val handler = Handler(Looper.getMainLooper())

    private val listener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = evaluate()
        override fun onDisplayRemoved(displayId: Int) = evaluate()
        override fun onDisplayChanged(displayId: Int) = evaluate()
    }

    fun start() {
        dm.registerDisplayListener(listener, handler)
        evaluate()
    }

    fun stop() {
        runCatching { dm.unregisterDisplayListener(listener) }
    }

    private fun evaluate() {
        val extra = dm.displays.filter { isCaptureDisplay(it) }
        val capturing = extra.isNotEmpty()
        val was = SecurityState.status.value.screenCaptured
        SecurityState.updateStatus { it.copy(screenCaptured = capturing) }

        if (capturing && !was) {
            val name = extra.first().name
            val e = SecurityState.addEvent(
                EventKind.SCREEN, Severity.CRITICAL,
                context.getString(com.apolos.shield.R.string.alert_screen_title),
                context.getString(com.apolos.shield.R.string.alert_screen_detail, name),
            )
            Notifier.alert(context, e)
        } else if (!capturing && was) {
            SecurityState.addEvent(
                EventKind.SCREEN, Severity.INFO,
                context.getString(com.apolos.shield.R.string.info_screen_released_title),
                context.getString(com.apolos.shield.R.string.info_screen_released_detail),
            )
        }
    }

    private fun isCaptureDisplay(d: Display): Boolean {
        if (d.displayId == Display.DEFAULT_DISPLAY) return false
        val flags = d.flags
        val presentation = (flags and Display.FLAG_PRESENTATION) != 0
        val private = (flags and Display.FLAG_PRIVATE) != 0
        // Display.getType()/TYPE_VIRTUAL is a hidden/@SystemApi member (not part
        // of the public SDK, so it can't be called from an app), hence relying
        // only on the public presentation/private flags here. This is narrower
        // than also checking STATE_ON, which would flag any ordinary external
        // monitor/HDMI/Miracast display as a "capture".
        return presentation || private
    }
}
