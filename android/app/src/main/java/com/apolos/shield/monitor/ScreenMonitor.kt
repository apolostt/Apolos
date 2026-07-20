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
        override fun onDisplayAdded(displayId: Int) = evaluate(added = displayId)
        override fun onDisplayRemoved(displayId: Int) = evaluate(added = null)
        override fun onDisplayChanged(displayId: Int) = evaluate(added = null)
    }

    fun start() {
        dm.registerDisplayListener(listener, handler)
        evaluate(added = null)
    }

    fun stop() {
        runCatching { dm.unregisterDisplayListener(listener) }
    }

    private fun evaluate(added: Int?) {
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
        // Virtual displays used by screen recorders/cast are typically non-default
        // and often private/presentation.
        return presentation || private || d.state == Display.STATE_ON
    }
}
