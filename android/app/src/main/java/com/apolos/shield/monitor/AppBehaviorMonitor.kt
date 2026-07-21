package com.apolos.shield.monitor

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import com.apolos.shield.core.AppRisk
import com.apolos.shield.core.EventKind
import com.apolos.shield.core.SecurityState
import com.apolos.shield.core.Severity

/**
 * Scans installed apps and scores each on how "surveillance-capable" it is:
 * the combination of camera + microphone + location + SMS/contacts + accessibility
 * is exactly the fingerprint of stalkerware. Sideloaded apps (not from a trusted
 * store) get an extra penalty.
 */
class AppBehaviorMonitor(private val context: Context) {

    private data class Weighted(val perm: String, val weight: Int, val label: String)

    private val watched = listOf(
        Weighted(android.Manifest.permission.CAMERA, 22, "camera"),
        Weighted(android.Manifest.permission.RECORD_AUDIO, 22, "microphone"),
        Weighted(android.Manifest.permission.ACCESS_FINE_LOCATION, 14, "precise location"),
        Weighted(android.Manifest.permission.READ_SMS, 14, "read SMS"),
        Weighted(android.Manifest.permission.READ_CONTACTS, 8, "contacts"),
        Weighted(android.Manifest.permission.READ_CALL_LOG, 12, "call log"),
        Weighted(android.Manifest.permission.SYSTEM_ALERT_WINDOW, 10, "draw over other apps"),
        Weighted(android.Manifest.permission.READ_PHONE_STATE, 6, "phone identity"),
    )

    private val trustedInstallers = setOf(
        "com.android.vending",       // Google Play
        "com.google.android.packageinstaller",
        "com.android.packageinstaller",
        "com.sec.android.app.samsungapps",
        "com.amazon.venezia",
        "org.fdroid.fdroid",
    )

    fun scan() {
        val pm = context.packageManager
        val installed = pm.getInstalledApplications(PackageManager.GET_META_DATA)
        val results = ArrayList<AppRisk>()

        for (info in installed) {
            if (info.packageName == context.packageName) continue
            val isSystem = (info.flags and ApplicationInfo.FLAG_SYSTEM) != 0

            val granted = grantedPermissions(pm, info.packageName)
            val reasons = ArrayList<String>()
            var score = 0
            for (w in watched) {
                if (granted.contains(w.perm)) {
                    score += w.weight
                    reasons.add(w.label)
                }
            }

            val sideloaded = !isSystem && !isFromTrustedStore(pm, info.packageName)
            if (sideloaded) {
                score += 18
                reasons.add("sideloaded / unknown source")
            }
            // Camera + mic together is the strongest single signal.
            if (granted.contains(android.Manifest.permission.CAMERA) &&
                granted.contains(android.Manifest.permission.RECORD_AUDIO)
            ) {
                score += 10
            }

            if (score >= 30) {
                results.add(
                    AppRisk(
                        packageName = info.packageName,
                        label = pm.getApplicationLabel(info).toString(),
                        riskScore = score.coerceAtMost(100),
                        reasons = reasons,
                        sideloaded = sideloaded,
                    )
                )
            }
        }

        results.sortByDescending { it.riskScore }
        SecurityState.setApps(results)

        val high = results.filter { it.riskScore >= 70 }
        if (high.isNotEmpty()) {
            SecurityState.addEvent(
                EventKind.APP, Severity.WARNING,
                context.getString(com.apolos.shield.R.string.warn_apps_title, high.size),
                high.take(3).joinToString { "${it.label} (${it.riskScore})" },
            )
        }
    }

    private fun grantedPermissions(pm: PackageManager, pkg: String): Set<String> {
        return try {
            val pi = pm.getPackageInfo(pkg, PackageManager.GET_PERMISSIONS)
            val requested = pi.requestedPermissions ?: return emptySet()
            val flags = pi.requestedPermissionsFlags
            buildSet {
                requested.forEachIndexed { i, p ->
                    val isGranted = flags != null &&
                        (flags[i] and android.content.pm.PackageInfo.REQUESTED_PERMISSION_GRANTED) != 0
                    if (isGranted) add(p)
                }
            }
        } catch (_: Exception) { emptySet() }
    }

    @Suppress("DEPRECATION")
    private fun isFromTrustedStore(pm: PackageManager, pkg: String): Boolean = try {
        val installer = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            pm.getInstallSourceInfo(pkg).installingPackageName
        } else {
            pm.getInstallerPackageName(pkg)
        }
        installer != null && trustedInstallers.contains(installer)
    } catch (_: Exception) { false }
}
