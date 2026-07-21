package com.apolos.shieldlite;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.IBinder;
import java.util.concurrent.atomic.AtomicBoolean;

/** Always-on foreground service holding the real-time camera/mic watcher. */
public class MonitoringService extends Service {

    private final AtomicBoolean started = new AtomicBoolean(false);
    private CameraMicMonitor cameraMic;

    @Override
    public void onCreate() {
        super.onCreate();
        cameraMic = new CameraMicMonitor(this);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startForeground(Notifier.FOREGROUND_ID,
                Notifier.foregroundNotification(this, getString(R.string.monitoring_active)));
        SecurityState.setMonitoringActive(true);

        // onStartCommand can be re-delivered while already running; only wire
        // up listeners once per service instance.
        if (!started.getAndSet(true)) {
            cameraMic.start();
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        cameraMic.stop();
        started.set(false);
        SecurityState.setMonitoringActive(false);
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    static void start(Context ctx) {
        ctx.startForegroundService(new Intent(ctx, MonitoringService.class));
    }

    static void stop(Context ctx) {
        ctx.stopService(new Intent(ctx, MonitoringService.class));
    }
}
