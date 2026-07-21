package com.apolos.shield.monitor

import android.app.usage.NetworkStats
import android.app.usage.NetworkStatsManager
import android.content.Context
import android.net.ConnectivityManager
import com.apolos.shield.core.EventKind
import com.apolos.shield.core.SecurityState
import com.apolos.shield.core.Severity

/**
 * Per-app network accounting via [NetworkStatsManager] (needs the "Usage access"
 * grant). Highlights the biggest talkers and escalates when an app that the
 * behaviour monitor already flagged as high-risk is also exfiltrating a lot of
 * data — the classic spyware pattern.
 */
class TrafficMonitor(private val context: Context) {

    data class Talker(val uid: Int, val label: String, val rx: Long, val tx: Long) {
        val total get() = rx + tx
    }

    fun sample() {
        val nsm = context.getSystemService(Context.NETWORK_STATS_SERVICE) as? NetworkStatsManager
            ?: return
        val end = System.currentTimeMillis()
        val start = end - 60L * 60L * 1000L // last hour

        val perUid = HashMap<Int, LongArray>() // uid -> [rx, tx]
        for (type in intArrayOf(ConnectivityManager.TYPE_WIFI, ConnectivityManager.TYPE_MOBILE)) {
            aggregate(nsm, type, start, end, perUid)
        }
        if (perUid.isEmpty()) return

        val pm = context.packageManager
        val talkers = perUid.map { (uid, v) ->
            val label = pm.getNameForUid(uid) ?: "uid:$uid"
            Talker(uid, label, v[0], v[1])
        }.sortedByDescending { it.total }

        val top = talkers.take(5)
        SecurityState.addEvent(
            EventKind.TRAFFIC, Severity.INFO,
            context.getString(com.apolos.shield.R.string.traffic_top_title),
            top.joinToString { "${shortName(it.label)} ${human(it.total)}" },
        )

        // Cross-reference with flagged apps → possible data exfiltration.
        val flagged = SecurityState.apps.value.filter { it.riskScore >= 70 }.map { it.packageName }.toSet()
        for (t in top) {
            val pkg = pm.getPackagesForUid(t.uid)?.firstOrNull() ?: continue
            if (pkg in flagged && t.tx > 5L * 1024 * 1024) { // >5 MB uploaded
                SecurityState.addEvent(
                    EventKind.TRAFFIC, Severity.CRITICAL,
                    context.getString(com.apolos.shield.R.string.alert_exfil_title),
                    context.getString(
                        com.apolos.shield.R.string.alert_exfil_detail,
                        shortName(t.label), human(t.tx),
                    ),
                )
            }
        }
    }

    private fun aggregate(
        nsm: NetworkStatsManager,
        type: Int,
        start: Long,
        end: Long,
        out: HashMap<Int, LongArray>,
    ) {
        var stats: NetworkStats? = null
        try {
            stats = nsm.querySummary(type, null, start, end)
            val bucket = NetworkStats.Bucket()
            while (stats!!.hasNextBucket()) {
                stats.getNextBucket(bucket)
                val v = out.getOrPut(bucket.uid) { longArrayOf(0, 0) }
                v[0] += bucket.rxBytes
                v[1] += bucket.txBytes
            }
        } catch (_: SecurityException) {
            // Usage-access not granted yet — surfaced in the UI as a required grant.
        } catch (_: Exception) {
        } finally {
            stats?.close()
        }
    }

    private fun shortName(name: String): String =
        name.substringAfterLast('.').ifEmpty { name }

    private fun human(bytes: Long): String = when {
        bytes >= 1L shl 30 -> "%.1f GB".format(bytes / (1L shl 30).toDouble())
        bytes >= 1L shl 20 -> "%.1f MB".format(bytes / (1L shl 20).toDouble())
        bytes >= 1L shl 10 -> "%.0f kB".format(bytes / (1L shl 10).toDouble())
        else -> "$bytes B"
    }
}
