package com.apolos.shieldlite;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.media.AudioAttributes;
import android.media.RingtoneManager;
import android.os.Build;
import java.util.concurrent.atomic.AtomicInteger;

/** Owns notification channels and turns security events into heads-up alerts. */
public final class Notifier {

    public static final String CHANNEL_ALERT = "apolos_alert";
    public static final String CHANNEL_STATUS = "apolos_status";
    public static final int FOREGROUND_ID = 1001;
    public static final int VPN_FOREGROUND_ID = 1002;
    private static final AtomicInteger alertId = new AtomicInteger(2000);

    private Notifier() { }

    public static void ensureChannels(Context ctx) {
        NotificationManager nm = ctx.getSystemService(NotificationManager.class);

        NotificationChannel alert = new NotificationChannel(
                CHANNEL_ALERT, "Security alerts", NotificationManager.IMPORTANCE_HIGH);
        alert.setDescription("High-priority alarms for camera, microphone and screen access.");
        alert.enableVibration(true);
        alert.setVibrationPattern(new long[]{0, 400, 200, 400});
        alert.enableLights(true);
        alert.setBypassDnd(true);
        alert.setSound(
                RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM),
                new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build());

        NotificationChannel status = new NotificationChannel(
                CHANNEL_STATUS, "Protection status", NotificationManager.IMPORTANCE_LOW);
        status.setDescription("Ongoing monitoring and VPN status.");

        nm.createNotificationChannel(alert);
        nm.createNotificationChannel(status);
    }

    private static PendingIntent contentIntent(Context ctx) {
        Intent intent = new Intent(ctx, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        return PendingIntent.getActivity(ctx, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    public static Notification foregroundNotification(Context ctx, String text) {
        return new Notification.Builder(ctx, CHANNEL_STATUS)
                .setSmallIcon(android.R.drawable.ic_lock_lock)
                .setContentTitle(ctx.getString(R.string.app_name))
                .setContentText(text)
                .setOngoing(true)
                .setContentIntent(contentIntent(ctx))
                .setCategory(Notification.CATEGORY_SERVICE)
                .build();
    }

    public static void alert(Context ctx, SecurityState.Event event) {
        String channel = event.severity == SecurityState.SEV_CRITICAL ? CHANNEL_ALERT : CHANNEL_STATUS;
        int priority = event.severity == SecurityState.SEV_CRITICAL
                ? Notification.PRIORITY_MAX : Notification.PRIORITY_DEFAULT;

        Notification.Builder b = new Notification.Builder(ctx, channel)
                .setSmallIcon(android.R.drawable.ic_lock_lock)
                .setContentTitle(event.title)
                .setContentText(event.detail)
                .setStyle(new Notification.BigTextStyle().bigText(event.detail))
                .setPriority(priority)
                .setCategory(Notification.CATEGORY_ALARM)
                .setAutoCancel(true)
                .setContentIntent(contentIntent(ctx));
        if (event.severity == SecurityState.SEV_CRITICAL) {
            b.setFullScreenIntent(contentIntent(ctx), true);
        }

        try {
            ctx.getSystemService(NotificationManager.class).notify(alertId.getAndIncrement(), b.build());
        } catch (SecurityException ignored) {
            // POST_NOTIFICATIONS not granted — dashboard alarm banner still fires.
        }
    }
}
