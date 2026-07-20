package com.apolos.shield.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import com.apolos.shield.R
import com.apolos.shield.alarm.Notifier
import com.apolos.shield.monitor.AppBehaviorMonitor
import com.apolos.shield.monitor.CameraMicMonitor
import com.apolos.shield.monitor.ScreenMonitor
import com.apolos.shield.monitor.TrafficMonitor
import com.apolos.shield.core.SecurityState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Always-on foreground service. Holds the real-time camera/mic watcher and runs
 * the app-behaviour and traffic scans on a schedule. This is what keeps the
 * shield alive when the UI is closed.
 */
class MonitoringService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var cameraMic: CameraMicMonitor
    private lateinit var screen: ScreenMonitor
    private lateinit var appMonitor: AppBehaviorMonitor
    private lateinit var traffic: TrafficMonitor

    override fun onCreate() {
        super.onCreate()
        cameraMic = CameraMicMonitor(this)
        screen = ScreenMonitor(this)
        appMonitor = AppBehaviorMonitor(this)
        traffic = TrafficMonitor(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(
            Notifier.FOREGROUND_ID,
            Notifier.foregroundNotification(this, getString(R.string.monitoring_active)),
        )
        SecurityState.updateStatus { it.copy(monitoringActive = true) }

        cameraMic.start()
        screen.start()

        scope.launch {
            // Initial scan quickly, then on a slow cadence to save battery.
            runCatching { appMonitor.scan() }
            while (isActive) {
                runCatching { traffic.sample() }
                delay(5 * 60_000L)      // traffic every 5 min
            }
        }
        scope.launch {
            while (isActive) {
                delay(30 * 60_000L)     // rescan apps every 30 min
                runCatching { appMonitor.scan() }
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        cameraMic.stop()
        screen.stop()
        scope.cancel()
        SecurityState.updateStatus { it.copy(monitoringActive = false) }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        fun start(context: Context) {
            context.startForegroundService(Intent(context, MonitoringService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, MonitoringService::class.java))
        }
    }
}
