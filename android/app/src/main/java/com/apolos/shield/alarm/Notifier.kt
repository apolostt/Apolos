package com.apolos.shield.alarm

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import androidx.core.app.NotificationCompat
import com.apolos.shield.MainActivity
import com.apolos.shield.R
import com.apolos.shield.core.SecurityEvent
import com.apolos.shield.core.Severity
import java.util.concurrent.atomic.AtomicInteger

/**
 * Owns the notification channels and turns [SecurityEvent]s into heads-up
 * alerts. CRITICAL events use a high-importance channel with sound + vibration
 * so a live camera/mic/screen access is impossible to miss.
 */
object Notifier {

    const val CHANNEL_ALERT = "apolos_alert"
    const val CHANNEL_STATUS = "apolos_status"
    const val FOREGROUND_ID = 1001
    const val VPN_FOREGROUND_ID = 1002
    private val alertId = AtomicInteger(2000)

    fun ensureChannels(ctx: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = ctx.getSystemService(NotificationManager::class.java)

        val alert = NotificationChannel(
            CHANNEL_ALERT,
            ctx.getString(R.string.channel_alert),
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = ctx.getString(R.string.channel_alert_desc)
            enableVibration(true)
            vibrationPattern = longArrayOf(0, 400, 200, 400)
            enableLights(true)
            setBypassDnd(true)
            setSound(
                RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM),
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build(),
            )
        }
        val status = NotificationChannel(
            CHANNEL_STATUS,
            ctx.getString(R.string.channel_status),
            NotificationManager.IMPORTANCE_LOW,
        ).apply { description = ctx.getString(R.string.channel_status_desc) }

        nm.createNotificationChannel(alert)
        nm.createNotificationChannel(status)
    }

    private fun contentIntent(ctx: Context): PendingIntent {
        val intent = Intent(ctx, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        return PendingIntent.getActivity(
            ctx, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    /** Persistent notification the foreground service runs under. */
    fun foregroundNotification(ctx: Context, text: String): Notification =
        NotificationCompat.Builder(ctx, CHANNEL_STATUS)
            .setSmallIcon(R.drawable.ic_shield)
            .setContentTitle(ctx.getString(R.string.app_name))
            .setContentText(text)
            .setOngoing(true)
            .setContentIntent(contentIntent(ctx))
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()

    fun alert(ctx: Context, event: SecurityEvent) {
        val channel = if (event.severity == Severity.CRITICAL) CHANNEL_ALERT else CHANNEL_STATUS
        val priority = if (event.severity == Severity.CRITICAL)
            NotificationCompat.PRIORITY_MAX else NotificationCompat.PRIORITY_DEFAULT

        val n = NotificationCompat.Builder(ctx, channel)
            .setSmallIcon(R.drawable.ic_shield)
            .setContentTitle(event.title)
            .setContentText(event.detail)
            .setStyle(NotificationCompat.BigTextStyle().bigText(event.detail))
            .setPriority(priority)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setAutoCancel(true)
            .setContentIntent(contentIntent(ctx))
            .apply { if (event.severity == Severity.CRITICAL) setFullScreenIntent(contentIntent(ctx), true) }
            .build()

        try {
            ctx.getSystemService(NotificationManager::class.java).notify(alertId.getAndIncrement(), n)
        } catch (_: SecurityException) {
            // POST_NOTIFICATIONS not granted — dashboard alarm banner still fires.
        }
    }
}
