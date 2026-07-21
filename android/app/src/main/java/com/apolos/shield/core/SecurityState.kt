package com.apolos.shield.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.atomic.AtomicLong

/** Severity of a security event, drives the dashboard colour + alarm. */
enum class Severity { INFO, WARNING, CRITICAL }

/** Category of the sensor / subsystem that produced an event. */
enum class EventKind { CAMERA, MICROPHONE, SCREEN, APP, TRAFFIC, VPN, SYSTEM }

data class SecurityEvent(
    val id: Long,
    val kind: EventKind,
    val severity: Severity,
    val title: String,
    val detail: String,
    val timestamp: Long = System.currentTimeMillis(),
)

/** Snapshot of an installed app that the behaviour monitor flagged. */
data class AppRisk(
    val packageName: String,
    val label: String,
    val riskScore: Int,          // 0..100
    val reasons: List<String>,
    val sideloaded: Boolean,
)

/** Live per-subsystem status shown on the dashboard. */
data class ShieldStatus(
    val vpnActive: Boolean = false,
    val vpnMode: String = "off",           // off | killswitch | dns | wireguard
    val monitoringActive: Boolean = false,
    val cameraInUse: Boolean = false,
    val micInUse: Boolean = false,
    val screenCaptured: Boolean = false,
    val blockedDnsCount: Long = 0,
    val flaggedApps: Int = 0,
    val threat: Severity = Severity.INFO,
)

/**
 * Process-wide, thread-safe source of truth. Every monitor writes here and the
 * Compose UI + Notifier observe it. Kept as a singleton object so the foreground
 * service and the Activity share one instance.
 */
object SecurityState {

    private val idGen = AtomicLong(0)

    private val _status = MutableStateFlow(ShieldStatus())
    val status: StateFlow<ShieldStatus> = _status.asStateFlow()

    private val _events = MutableStateFlow<List<SecurityEvent>>(emptyList())
    val events: StateFlow<List<SecurityEvent>> = _events.asStateFlow()

    private val _apps = MutableStateFlow<List<AppRisk>>(emptyList())
    val apps: StateFlow<List<AppRisk>> = _apps.asStateFlow()

    /** Raised to true whenever a CRITICAL event fires; UI shows the alarm banner. */
    private val _alarm = MutableStateFlow<SecurityEvent?>(null)
    val alarm: StateFlow<SecurityEvent?> = _alarm.asStateFlow()

    fun updateStatus(transform: (ShieldStatus) -> ShieldStatus) {
        _status.update(transform)
        recomputeThreat()
    }

    fun addEvent(kind: EventKind, severity: Severity, title: String, detail: String): SecurityEvent {
        val event = SecurityEvent(idGen.incrementAndGet(), kind, severity, title, detail)
        _events.update { (listOf(event) + it).take(MAX_EVENTS) }
        if (severity == Severity.CRITICAL) {
            _alarm.value = event
        }
        recomputeThreat()
        return event
    }

    fun setApps(list: List<AppRisk>) {
        _apps.value = list
        _status.update { it.copy(flaggedApps = list.count { a -> a.riskScore >= 60 }) }
        recomputeThreat()
    }

    fun clearAlarm() { _alarm.value = null }

    private fun recomputeThreat() {
        val recentCritical = _events.value.take(5).any { it.severity == Severity.CRITICAL }
        _status.update { s ->
            val activeCritical = s.cameraInUse || s.micInUse || s.screenCaptured
            val threat = when {
                activeCritical || recentCritical -> Severity.CRITICAL
                s.flaggedApps > 0 || !s.vpnActive -> Severity.WARNING
                else -> Severity.INFO
            }
            if (threat == s.threat) s else s.copy(threat = threat)
        }
    }

    private const val MAX_EVENTS = 200
}
