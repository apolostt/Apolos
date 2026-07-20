package com.apolos.shield.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Restarts monitoring after a reboot so protection is continuous. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action == Intent.ACTION_BOOT_COMPLETED) {
            MonitoringService.start(context)
        }
    }
}
