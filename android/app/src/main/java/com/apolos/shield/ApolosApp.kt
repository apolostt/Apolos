package com.apolos.shield

import android.app.Application
import com.apolos.shield.alarm.Notifier

class ApolosApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Notifier.ensureChannels(this)
    }
}
