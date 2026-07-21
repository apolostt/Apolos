package com.apolos.shieldlite;

import android.app.Activity;
import android.app.Application;
import android.content.Intent;
import android.net.VpnService;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class MainActivity extends Activity implements SecurityState.Listener {

    private static final int REQ_VPN = 100;
    private static final int REQ_NOTIF = 101;
    private String pendingVpnMode;

    private TextView threatBanner;
    private TextView sensorStatus;
    private TextView eventLog;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final SimpleDateFormat timeFmt = new SimpleDateFormat("HH:mm:ss", Locale.getDefault());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Notifier.ensureChannels(this);
        setContentView(R.layout.activity_main);

        threatBanner = findViewById(R.id.threatBanner);
        sensorStatus = findViewById(R.id.sensorStatus);
        eventLog = findViewById(R.id.eventLog);

        findViewById(R.id.btnStartMonitoring).setOnClickListener(v -> {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIF);
            }
            MonitoringService.start(this);
        });
        findViewById(R.id.btnStopMonitoring).setOnClickListener(v -> MonitoringService.stop(this));
        findViewById(R.id.btnVpnDns).setOnClickListener(v -> launchVpn(ShieldVpnService.MODE_DNS));
        findViewById(R.id.btnVpnKill).setOnClickListener(v -> launchVpn(ShieldVpnService.MODE_KILLSWITCH));
        findViewById(R.id.btnVpnOff).setOnClickListener(v -> ShieldVpnService.stop(this));

        refreshUi();
    }

    private void launchVpn(String mode) {
        Intent prepare = VpnService.prepare(this);
        if (prepare != null) {
            pendingVpnMode = mode;
            startActivityForResult(prepare, REQ_VPN);
        } else {
            ShieldVpnService.start(this, mode);
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_VPN && resultCode == Activity.RESULT_OK && pendingVpnMode != null) {
            ShieldVpnService.start(this, pendingVpnMode);
        }
        pendingVpnMode = null;
    }

    @Override
    protected void onResume() {
        super.onResume();
        SecurityState.addListener(this);
        refreshUi();
    }

    @Override
    protected void onPause() {
        SecurityState.removeListener(this);
        super.onPause();
    }

    @Override
    public void onChanged() {
        mainHandler.post(this::refreshUi);
    }

    private void refreshUi() {
        int threat = SecurityState.threatLevel();
        String label;
        int color;
        switch (threat) {
            case SecurityState.SEV_CRITICAL: label = "CRITICAL"; color = 0xFFE53935; break;
            case SecurityState.SEV_WARNING: label = "Attention"; color = 0xFFFFB300; break;
            default: label = "Protected"; color = 0xFF1DB954;
        }
        threatBanner.setText(label);
        threatBanner.setBackgroundColor(color);

        sensorStatus.setText("Camera: " + (SecurityState.isCameraInUse() ? "ON" : "off")
                + "   Mic: " + (SecurityState.isMicInUse() ? "ON" : "off")
                + "   VPN: " + (SecurityState.isVpnActive() ? SecurityState.getVpnMode() : "off")
                + "   Blocked: " + SecurityState.getBlockedDnsCount());

        List<SecurityState.Event> events = SecurityState.getEvents();
        List<String> lines = new ArrayList<>();
        for (SecurityState.Event e : events) {
            lines.add(timeFmt.format(new java.util.Date(e.timestamp)) + "  " + e.title + " — " + e.detail);
        }
        eventLog.setText(TextUtils.join("\n", lines));
    }
}
