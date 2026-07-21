package com.apolos.shieldlite;

import android.content.Context;
import android.hardware.camera2.CameraManager;
import android.media.AudioManager;
import android.media.AudioRecordingConfiguration;
import android.os.Handler;
import android.os.HandlerThread;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.HashSet;

/** Detects, in real time, when any app activates the camera or the microphone. */
final class CameraMicMonitor {

    private final Context context;
    private final CameraManager cameraManager;
    private final AudioManager audioManager;
    private HandlerThread thread;
    private Handler handler;
    private final Set<String> unavailableCameras = Collections.synchronizedSet(new HashSet<>());

    private final CamCb cameraCallback = new CamCb(this);
    private final AudioCb audioCallback = new AudioCb(this);

    /** Static nested (not anonymous) to avoid a synthetic outer-instance
     *  constructor parameter that trips a bug in this environment's D8. */
    private static final class CamCb extends CameraManager.AvailabilityCallback {
        private final CameraMicMonitor owner;
        CamCb(CameraMicMonitor owner) { this.owner = owner; }

        @Override
        public void onCameraUnavailable(String cameraId) {
            boolean firstUse = owner.unavailableCameras.isEmpty();
            owner.unavailableCameras.add(cameraId);
            if (firstUse) owner.onCameraStateChanged(true, cameraId);
        }

        @Override
        public void onCameraAvailable(String cameraId) {
            owner.unavailableCameras.remove(cameraId);
            if (owner.unavailableCameras.isEmpty()) owner.onCameraStateChanged(false, cameraId);
        }
    }

    private static final class AudioCb extends AudioManager.AudioRecordingCallback {
        private final CameraMicMonitor owner;
        AudioCb(CameraMicMonitor owner) { this.owner = owner; }

        @Override
        public void onRecordingConfigChanged(List<AudioRecordingConfiguration> configs) {
            owner.onMicStateChanged(configs != null && !configs.isEmpty(), configs == null ? 0 : configs.size());
        }
    }

    CameraMicMonitor(Context context) {
        this.context = context;
        this.cameraManager = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
        this.audioManager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
    }

    void start() {
        stop(); // idempotent: tear down any previous registration first
        unavailableCameras.clear();
        thread = new HandlerThread("apolos-cam-mic");
        thread.start();
        handler = new Handler(thread.getLooper());

        try {
            cameraManager.registerAvailabilityCallback(cameraCallback, handler);
        } catch (Exception ignored) { }

        try {
            audioManager.registerAudioRecordingCallback(audioCallback, handler);
            handler.post(() -> onMicStateChanged(
                    !audioManager.getActiveRecordingConfigurations().isEmpty(),
                    audioManager.getActiveRecordingConfigurations().size()));
        } catch (Exception ignored) { }
    }

    void stop() {
        try { cameraManager.unregisterAvailabilityCallback(cameraCallback); } catch (Exception ignored) { }
        try { audioManager.unregisterAudioRecordingCallback(audioCallback); } catch (Exception ignored) { }
        if (thread != null) thread.quitSafely();
        thread = null;
        handler = null;
    }

    private void onCameraStateChanged(boolean inUse, String cameraId) {
        SecurityState.setCameraInUse(inUse);
        if (inUse) {
            SecurityState.Event e = SecurityState.addEvent(
                    SecurityState.SEV_CRITICAL, "Camera activated",
                    "An app just started using the camera (id " + cameraId + ").");
            Notifier.alert(context, e);
        } else {
            SecurityState.addEvent(SecurityState.SEV_INFO, "Camera released", "The camera is no longer in use.");
        }
    }

    private void onMicStateChanged(boolean active, int count) {
        boolean was = SecurityState.isMicInUse();
        SecurityState.setMicInUse(active);
        if (active && !was) {
            SecurityState.Event e = SecurityState.addEvent(
                    SecurityState.SEV_CRITICAL, "Microphone activated",
                    count + " recording session(s) are using the microphone.");
            Notifier.alert(context, e);
        } else if (!active && was) {
            SecurityState.addEvent(SecurityState.SEV_INFO, "Microphone released", "The microphone is no longer in use.");
        }
    }
}
