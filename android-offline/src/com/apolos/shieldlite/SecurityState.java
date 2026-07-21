package com.apolos.shieldlite;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/** Process-wide, thread-safe security state shared between the service and the UI. */
public final class SecurityState {

    public static final int SEV_INFO = 0;
    public static final int SEV_WARNING = 1;
    public static final int SEV_CRITICAL = 2;

    public static final class Event {
        public final long id;
        public final int severity;
        public final String title;
        public final String detail;
        public final long timestamp;

        Event(long id, int severity, String title, String detail) {
            this.id = id;
            this.severity = severity;
            this.title = title;
            this.detail = detail;
            this.timestamp = System.currentTimeMillis();
        }
    }

    public interface Listener {
        void onChanged();
    }

    private static final AtomicLong ID_GEN = new AtomicLong(0);
    private static final Object LOCK = new Object();
    private static final List<Event> EVENTS = new ArrayList<>();
    private static final List<Listener> LISTENERS = new ArrayList<>();
    private static final int MAX_EVENTS = 200;

    private static boolean monitoringActive = false;
    private static boolean vpnActive = false;
    private static String vpnMode = "off";
    private static boolean cameraInUse = false;
    private static boolean micInUse = false;
    private static long blockedDnsCount = 0;
    private static Event alarm = null;

    private SecurityState() { }

    public static void addListener(Listener l) {
        synchronized (LOCK) { LISTENERS.add(l); }
    }

    public static void removeListener(Listener l) {
        synchronized (LOCK) { LISTENERS.remove(l); }
    }

    private static void notifyListeners() {
        List<Listener> copy;
        synchronized (LOCK) { copy = new ArrayList<>(LISTENERS); }
        for (Listener l : copy) l.onChanged();
    }

    public static Event addEvent(int severity, String title, String detail) {
        Event e = new Event(ID_GEN.incrementAndGet(), severity, title, detail);
        synchronized (LOCK) {
            EVENTS.add(0, e);
            while (EVENTS.size() > MAX_EVENTS) EVENTS.remove(EVENTS.size() - 1);
            if (severity == SEV_CRITICAL) alarm = e;
        }
        notifyListeners();
        return e;
    }

    public static void clearAlarm() {
        synchronized (LOCK) { alarm = null; }
        notifyListeners();
    }

    public static Event getAlarm() {
        synchronized (LOCK) { return alarm; }
    }

    public static List<Event> getEvents() {
        synchronized (LOCK) { return new ArrayList<>(EVENTS); }
    }

    public static void setMonitoringActive(boolean v) {
        synchronized (LOCK) { monitoringActive = v; }
        notifyListeners();
    }

    public static boolean isMonitoringActive() {
        synchronized (LOCK) { return monitoringActive; }
    }

    public static void setVpn(boolean active, String mode) {
        synchronized (LOCK) { vpnActive = active; vpnMode = mode; }
        notifyListeners();
    }

    public static boolean isVpnActive() {
        synchronized (LOCK) { return vpnActive; }
    }

    public static String getVpnMode() {
        synchronized (LOCK) { return vpnMode; }
    }

    public static void setCameraInUse(boolean v) {
        synchronized (LOCK) { cameraInUse = v; }
        notifyListeners();
    }

    public static boolean isCameraInUse() {
        synchronized (LOCK) { return cameraInUse; }
    }

    public static void setMicInUse(boolean v) {
        synchronized (LOCK) { micInUse = v; }
        notifyListeners();
    }

    public static boolean isMicInUse() {
        synchronized (LOCK) { return micInUse; }
    }

    public static void incrementBlockedDns() {
        synchronized (LOCK) { blockedDnsCount++; }
        notifyListeners();
    }

    public static long getBlockedDnsCount() {
        synchronized (LOCK) { return blockedDnsCount; }
    }

    /** INFO / WARNING / CRITICAL summary the dashboard colours itself by. */
    public static int threatLevel() {
        synchronized (LOCK) {
            if (cameraInUse || micInUse) return SEV_CRITICAL;
            if (!vpnActive) return SEV_WARNING;
            return SEV_INFO;
        }
    }
}
