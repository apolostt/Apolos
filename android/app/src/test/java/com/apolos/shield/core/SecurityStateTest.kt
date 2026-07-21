package com.apolos.shield.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for [SecurityState]. Because this is a process-wide singleton
 * `object`, every test explicitly resets the pieces of state it depends on via
 * the public API rather than relying on default/leftover values from other
 * tests in the same JVM run.
 */
class SecurityStateTest {

    @Before
    fun resetState() {
        SecurityState.clearAlarm()
        SecurityState.setApps(emptyList())
        SecurityState.updateStatus { ShieldStatus() }
        // Flush any CRITICAL events left over from a previous test out of the
        // "last 5 events" window that recomputeThreat() inspects.
        repeat(5) { SecurityState.addEvent(EventKind.SYSTEM, Severity.INFO, "reset", "reset") }
    }

    // ---------------------------------------------------------------
    // addEvent
    // ---------------------------------------------------------------

    @Test
    fun `addEvent prepends the newest event to the events list`() {
        val first = SecurityState.addEvent(EventKind.SYSTEM, Severity.INFO, "first", "d1")
        val second = SecurityState.addEvent(EventKind.SYSTEM, Severity.INFO, "second", "d2")

        val events = SecurityState.events.value
        assertEquals(second, events[0])
        assertEquals(first, events[1])
    }

    @Test
    fun `addEvent assigns strictly increasing ids`() {
        val e1 = SecurityState.addEvent(EventKind.CAMERA, Severity.INFO, "a", "a")
        val e2 = SecurityState.addEvent(EventKind.CAMERA, Severity.INFO, "b", "b")
        assertEquals(e1.id + 1, e2.id)
    }

    @Test
    fun `addEvent populates all fields on the returned event`() {
        val event = SecurityState.addEvent(EventKind.TRAFFIC, Severity.WARNING, "title", "detail")
        assertEquals(EventKind.TRAFFIC, event.kind)
        assertEquals(Severity.WARNING, event.severity)
        assertEquals("title", event.title)
        assertEquals("detail", event.detail)
        assertTrue(event.timestamp > 0)
    }

    @Test
    fun `addEvent with CRITICAL severity raises the alarm`() {
        val event = SecurityState.addEvent(EventKind.MICROPHONE, Severity.CRITICAL, "mic on", "detail")
        assertEquals(event, SecurityState.alarm.value)
    }

    @Test
    fun `addEvent with non-CRITICAL severity does not raise the alarm`() {
        SecurityState.addEvent(EventKind.APP, Severity.WARNING, "warn", "detail")
        assertNull(SecurityState.alarm.value)

        SecurityState.addEvent(EventKind.APP, Severity.INFO, "info", "detail")
        assertNull(SecurityState.alarm.value)
    }

    // ---------------------------------------------------------------
    // clearAlarm
    // ---------------------------------------------------------------

    @Test
    fun `clearAlarm resets the alarm to null`() {
        SecurityState.addEvent(EventKind.SCREEN, Severity.CRITICAL, "screen", "detail")
        assertTrue(SecurityState.alarm.value != null)

        SecurityState.clearAlarm()
        assertNull(SecurityState.alarm.value)
    }

    // ---------------------------------------------------------------
    // setApps
    // ---------------------------------------------------------------

    @Test
    fun `setApps stores the provided list verbatim`() {
        val apps = listOf(
            AppRisk("pkg.a", "A", 75, listOf("camera"), sideloaded = false),
            AppRisk("pkg.b", "B", 40, listOf("contacts"), sideloaded = true),
        )
        SecurityState.setApps(apps)
        assertEquals(apps, SecurityState.apps.value)
    }

    @Test
    fun `setApps counts apps with risk score 60 or above as flagged`() {
        val apps = listOf(
            AppRisk("pkg.high", "High", 75, emptyList(), false),
            AppRisk("pkg.boundary", "Boundary", 60, emptyList(), false),
            AppRisk("pkg.low", "Low", 59, emptyList(), false),
        )
        SecurityState.setApps(apps)
        assertEquals(2, SecurityState.status.value.flaggedApps)
    }

    @Test
    fun `setApps with an empty list clears the flagged count`() {
        SecurityState.setApps(listOf(AppRisk("pkg.x", "X", 90, emptyList(), false)))
        assertEquals(1, SecurityState.status.value.flaggedApps)

        SecurityState.setApps(emptyList())
        assertEquals(0, SecurityState.status.value.flaggedApps)
    }

    // ---------------------------------------------------------------
    // updateStatus
    // ---------------------------------------------------------------

    @Test
    fun `updateStatus applies the transform to the current status`() {
        SecurityState.updateStatus { it.copy(vpnActive = true, vpnMode = "dns") }
        val status = SecurityState.status.value
        assertTrue(status.vpnActive)
        assertEquals("dns", status.vpnMode)
    }

    // ---------------------------------------------------------------
    // recomputeThreat (triggered by updateStatus / addEvent / setApps)
    // ---------------------------------------------------------------

    @Test
    fun `threat is CRITICAL while the camera is in use`() {
        SecurityState.updateStatus { it.copy(vpnActive = true, cameraInUse = true) }
        assertEquals(Severity.CRITICAL, SecurityState.status.value.threat)
    }

    @Test
    fun `threat is CRITICAL while the microphone is in use`() {
        SecurityState.updateStatus { it.copy(vpnActive = true, micInUse = true) }
        assertEquals(Severity.CRITICAL, SecurityState.status.value.threat)
    }

    @Test
    fun `threat is CRITICAL while the screen is being captured`() {
        SecurityState.updateStatus { it.copy(vpnActive = true, screenCaptured = true) }
        assertEquals(Severity.CRITICAL, SecurityState.status.value.threat)
    }

    @Test
    fun `threat is CRITICAL shortly after a CRITICAL event even without an active sensor`() {
        SecurityState.updateStatus { it.copy(vpnActive = true) }
        SecurityState.addEvent(EventKind.CAMERA, Severity.CRITICAL, "camera on", "detail")
        assertEquals(Severity.CRITICAL, SecurityState.status.value.threat)
    }

    @Test
    fun `threat is WARNING when the vpn is inactive and nothing else is critical`() {
        SecurityState.updateStatus { it.copy(vpnActive = false) }
        assertEquals(Severity.WARNING, SecurityState.status.value.threat)
    }

    @Test
    fun `threat is WARNING when apps are flagged even with the vpn active`() {
        SecurityState.updateStatus { it.copy(vpnActive = true) }
        SecurityState.setApps(listOf(AppRisk("pkg.risky", "Risky", 80, emptyList(), false)))
        assertEquals(Severity.WARNING, SecurityState.status.value.threat)
    }

    @Test
    fun `threat is INFO when the vpn is active and nothing is flagged or critical`() {
        SecurityState.updateStatus { it.copy(vpnActive = true) }
        assertEquals(Severity.INFO, SecurityState.status.value.threat)
    }

    @Test
    fun `threat returns to WARNING once a critical sensor is released and vpn is off`() {
        SecurityState.updateStatus { it.copy(vpnActive = false, cameraInUse = true) }
        assertEquals(Severity.CRITICAL, SecurityState.status.value.threat)

        SecurityState.updateStatus { it.copy(cameraInUse = false) }
        assertEquals(Severity.WARNING, SecurityState.status.value.threat)
    }
}