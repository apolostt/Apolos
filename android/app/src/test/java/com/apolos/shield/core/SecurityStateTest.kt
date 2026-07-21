package com.apolos.shield.core

import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for [SecurityState], the process-wide singleton that every
 * monitor reports into and the UI observes. Since it is a Kotlin `object`
 * (i.e. global JVM state shared across all test methods), each test resets
 * the backing flows via reflection before running so tests remain
 * independent of execution order.
 */
class SecurityStateTest {

    @Before
    fun resetState() {
        resetFlow<ShieldStatus>("_status", ShieldStatus())
        resetFlow<List<SecurityEvent>>("_events", emptyList())
        resetFlow<List<AppRisk>>("_apps", emptyList())
        resetFlow<SecurityEvent?>("_alarm", null)
    }

    @After
    fun cleanup() {
        resetState()
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> resetFlow(fieldName: String, value: T) {
        val field = SecurityState::class.java.getDeclaredField(fieldName)
        field.isAccessible = true
        (field.get(SecurityState) as MutableStateFlow<T>).value = value
    }

    // ---------------------------------------------------------------- updateStatus

    @Test
    fun `updateStatus applies the transform to the current status`() {
        SecurityState.updateStatus { it.copy(vpnActive = true, vpnMode = "dns") }

        val status = SecurityState.status.value
        assertTrue(status.vpnActive)
        assertEquals("dns", status.vpnMode)
    }

    @Test
    fun `updateStatus recomputes threat to WARNING when vpn is inactive`() {
        SecurityState.updateStatus { it.copy(vpnActive = false) }
        assertEquals(Severity.WARNING, SecurityState.status.value.threat)
    }

    @Test
    fun `updateStatus recomputes threat to INFO when vpn active and nothing flagged`() {
        SecurityState.updateStatus { it.copy(vpnActive = true) }
        assertEquals(Severity.INFO, SecurityState.status.value.threat)
    }

    @Test
    fun `updateStatus recomputes threat to CRITICAL when camera is in use`() {
        SecurityState.updateStatus { it.copy(vpnActive = true, cameraInUse = true) }
        assertEquals(Severity.CRITICAL, SecurityState.status.value.threat)
    }

    @Test
    fun `updateStatus recomputes threat to CRITICAL when microphone is in use`() {
        SecurityState.updateStatus { it.copy(vpnActive = true, micInUse = true) }
        assertEquals(Severity.CRITICAL, SecurityState.status.value.threat)
    }

    @Test
    fun `updateStatus recomputes threat to CRITICAL when screen is captured`() {
        SecurityState.updateStatus { it.copy(vpnActive = true, screenCaptured = true) }
        assertEquals(Severity.CRITICAL, SecurityState.status.value.threat)
    }

    // ---------------------------------------------------------------- addEvent

    @Test
    fun `addEvent inserts the new event at the head of the list`() {
        val first = SecurityState.addEvent(EventKind.APP, Severity.INFO, "first", "d1")
        val second = SecurityState.addEvent(EventKind.APP, Severity.INFO, "second", "d2")

        val events = SecurityState.events.value
        assertEquals(second.id, events[0].id)
        assertEquals(first.id, events[1].id)
    }

    @Test
    fun `addEvent assigns strictly increasing unique ids`() {
        val e1 = SecurityState.addEvent(EventKind.SYSTEM, Severity.INFO, "a", "a")
        val e2 = SecurityState.addEvent(EventKind.SYSTEM, Severity.INFO, "b", "b")
        val e3 = SecurityState.addEvent(EventKind.SYSTEM, Severity.INFO, "c", "c")

        assertTrue(e2.id > e1.id)
        assertTrue(e3.id > e2.id)
    }

    @Test
    fun `addEvent stores kind, severity, title and detail verbatim`() {
        val event = SecurityState.addEvent(
            EventKind.TRAFFIC, Severity.WARNING, "Traffic spike", "100 MB uploaded",
        )
        assertEquals(EventKind.TRAFFIC, event.kind)
        assertEquals(Severity.WARNING, event.severity)
        assertEquals("Traffic spike", event.title)
        assertEquals("100 MB uploaded", event.detail)
    }

    @Test
    fun `addEvent with CRITICAL severity raises the alarm`() {
        assertNull(SecurityState.alarm.value)
        val event = SecurityState.addEvent(EventKind.CAMERA, Severity.CRITICAL, "Camera on", "d")
        assertEquals(event.id, SecurityState.alarm.value?.id)
    }

    @Test
    fun `addEvent with non-CRITICAL severity does not raise the alarm`() {
        SecurityState.addEvent(EventKind.CAMERA, Severity.WARNING, "warn", "d")
        assertNull(SecurityState.alarm.value)

        SecurityState.addEvent(EventKind.CAMERA, Severity.INFO, "info", "d")
        assertNull(SecurityState.alarm.value)
    }

    @Test
    fun `addEvent with CRITICAL severity flips threat to CRITICAL even with vpn active`() {
        SecurityState.updateStatus { it.copy(vpnActive = true) }
        assertEquals(Severity.INFO, SecurityState.status.value.threat)

        SecurityState.addEvent(EventKind.MICROPHONE, Severity.CRITICAL, "mic", "d")

        assertEquals(Severity.CRITICAL, SecurityState.status.value.threat)
    }

    @Test
    fun `event list is capped at 200 entries and keeps only the most recent`() {
        val ids = ArrayList<Long>()
        repeat(210) { i ->
            ids.add(SecurityState.addEvent(EventKind.SYSTEM, Severity.INFO, "e$i", "d$i").id)
        }

        val events = SecurityState.events.value
        assertEquals(200, events.size)
        // Newest first: head must be the very last event added.
        assertEquals(ids.last(), events.first().id)
        // The oldest 10 events must have been dropped.
        assertFalse(events.any { it.id == ids.first() })
    }

    // ---------------------------------------------------------------- clearAlarm

    @Test
    fun `clearAlarm resets the alarm to null`() {
        SecurityState.addEvent(EventKind.SCREEN, Severity.CRITICAL, "screen", "d")
        assertNotNull(SecurityState.alarm.value)

        SecurityState.clearAlarm()

        assertNull(SecurityState.alarm.value)
    }

    @Test
    fun `clearAlarm does not remove the event from the event history`() {
        val event = SecurityState.addEvent(EventKind.SCREEN, Severity.CRITICAL, "screen", "d")
        SecurityState.clearAlarm()

        assertTrue(SecurityState.events.value.any { it.id == event.id })
    }

    // ---------------------------------------------------------------- setApps

    @Test
    fun `setApps stores the provided list verbatim`() {
        val apps = listOf(
            AppRisk("com.a", "A", 40, listOf("camera"), sideloaded = false),
            AppRisk("com.b", "B", 80, listOf("camera", "microphone"), sideloaded = true),
        )
        SecurityState.setApps(apps)
        assertEquals(apps, SecurityState.apps.value)
    }

    @Test
    fun `setApps counts only apps scoring 60 or higher as flagged`() {
        val apps = listOf(
            AppRisk("com.low", "Low", 59, emptyList(), sideloaded = false),
            AppRisk("com.boundary", "Boundary", 60, emptyList(), sideloaded = false),
            AppRisk("com.high", "High", 95, emptyList(), sideloaded = true),
        )
        SecurityState.setApps(apps)
        assertEquals(2, SecurityState.status.value.flaggedApps)
    }

    @Test
    fun `setApps with no risky apps and vpn active keeps threat at INFO`() {
        SecurityState.updateStatus { it.copy(vpnActive = true) }
        SecurityState.setApps(listOf(AppRisk("com.a", "A", 10, emptyList(), sideloaded = false)))
        assertEquals(Severity.INFO, SecurityState.status.value.threat)
    }

    @Test
    fun `setApps with a flagged app raises threat to WARNING even with vpn active`() {
        SecurityState.updateStatus { it.copy(vpnActive = true) }
        SecurityState.setApps(listOf(AppRisk("com.bad", "Bad", 90, listOf("camera"), sideloaded = true)))
        assertEquals(Severity.WARNING, SecurityState.status.value.threat)
    }

    @Test
    fun `setApps with an empty list clears the flagged app count`() {
        SecurityState.setApps(listOf(AppRisk("com.bad", "Bad", 90, emptyList(), sideloaded = true)))
        assertEquals(1, SecurityState.status.value.flaggedApps)

        SecurityState.setApps(emptyList())
        assertEquals(0, SecurityState.status.value.flaggedApps)
    }
}