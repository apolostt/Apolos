package com.apolos.shield.monitor

import android.content.Context
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.media.AudioRecordingConfiguration
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import com.apolos.shield.alarm.Notifier
import com.apolos.shield.core.EventKind
import com.apolos.shield.core.SecurityState
import com.apolos.shield.core.Severity
import java.util.Collections

/**
 * Detects, in real time, when *any* app activates the camera or the microphone
 * and raises a CRITICAL alarm the moment it happens.
 *
 *  - Camera: [CameraManager.AvailabilityCallback]. A camera reported
 *    "unavailable" means another process has opened it (this app never does).
 *  - Microphone: [AudioManager.AudioRecordingCallback]. A non-empty active
 *    recording configuration list means the mic is live.
 */
class CameraMicMonitor(private val context: Context) {

    private val cameraManager =
        context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private val audioManager =
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private var thread: HandlerThread? = null
    private var handler: Handler? = null

    private val unavailableCameras = Collections.synchronizedSet(mutableSetOf<String>())

    private val cameraCallback = object : CameraManager.AvailabilityCallback() {
        override fun onCameraUnavailable(cameraId: String) {
            val firstUse = unavailableCameras.isEmpty()
            unavailableCameras.add(cameraId)
            if (firstUse) onCameraStateChanged(inUse = true, cameraId = cameraId)
        }

        override fun onCameraAvailable(cameraId: String) {
            unavailableCameras.remove(cameraId)
            if (unavailableCameras.isEmpty()) onCameraStateChanged(inUse = false, cameraId = cameraId)
        }
    }

    private val audioCallback = object : AudioManager.AudioRecordingCallback() {
        override fun onRecordingConfigChanged(configs: MutableList<AudioRecordingConfiguration>?) {
            onMicStateChanged(active = !configs.isNullOrEmpty(), count = configs?.size ?: 0)
        }
    }

    fun start() {
        thread = HandlerThread("apolos-cam-mic").also { it.start() }
        handler = Handler(thread!!.looper)

        // Prime current state so a camera already in use at start is caught.
        try {
            cameraManager.registerAvailabilityCallback(cameraCallback, handler)
        } catch (_: Exception) { /* device without camera */ }

        try {
            audioManager.registerAudioRecordingCallback(audioCallback, handler)
            onMicStateChanged(
                active = audioManager.activeRecordingConfigurations.isNotEmpty(),
                count = audioManager.activeRecordingConfigurations.size,
            )
        } catch (_: Exception) { }
    }

    fun stop() {
        runCatching { cameraManager.unregisterAvailabilityCallback(cameraCallback) }
        runCatching { audioManager.unregisterAudioRecordingCallback(audioCallback) }
        thread?.quitSafely()
        thread = null
        handler = null
    }

    private fun onCameraStateChanged(inUse: Boolean, cameraId: String) {
        SecurityState.updateStatus { it.copy(cameraInUse = inUse) }
        if (inUse) {
            val e = SecurityState.addEvent(
                EventKind.CAMERA, Severity.CRITICAL,
                context.getString(com.apolos.shield.R.string.alert_camera_title),
                context.getString(com.apolos.shield.R.string.alert_camera_detail, cameraId),
            )
            Notifier.alert(context, e)
        } else {
            SecurityState.addEvent(
                EventKind.CAMERA, Severity.INFO,
                context.getString(com.apolos.shield.R.string.info_camera_released_title),
                context.getString(com.apolos.shield.R.string.info_camera_released_detail),
            )
        }
    }

    private fun onMicStateChanged(active: Boolean, count: Int) {
        val was = SecurityState.status.value.micInUse
        SecurityState.updateStatus { it.copy(micInUse = active) }
        if (active && !was) {
            val e = SecurityState.addEvent(
                EventKind.MICROPHONE, Severity.CRITICAL,
                context.getString(com.apolos.shield.R.string.alert_mic_title),
                context.getString(com.apolos.shield.R.string.alert_mic_detail, count),
            )
            Notifier.alert(context, e)
        } else if (!active && was) {
            SecurityState.addEvent(
                EventKind.MICROPHONE, Severity.INFO,
                context.getString(com.apolos.shield.R.string.info_mic_released_title),
                context.getString(com.apolos.shield.R.string.info_mic_released_detail),
            )
        }
    }
}
