package com.apolos.shield.vpn

import android.content.Context
import java.io.BufferedReader

/**
 * Domain blocklist for the DNS-filtering tunnel. Ships with a compact seed list
 * of common tracker/ad/malware domains and merges any user rules stored in the
 * app's files dir (one domain per line, "#" comments allowed). Matching is
 * suffix-based so "ads.example.com" is blocked by an "example.com" rule only
 * when that rule is intended as a zone; exact + parent-domain matching is used.
 */
class DnsFilter(context: Context) {

    private val blocked = HashSet<String>()

    init {
        SEED.forEach { blocked.add(it) }
        runCatching {
            context.assets.open("blocklist.txt").bufferedReader().use(::loadFrom)
        }
        val userFile = context.filesDir.resolve("user_blocklist.txt")
        if (userFile.exists()) userFile.bufferedReader().use(::loadFrom)
    }

    private fun loadFrom(reader: BufferedReader) {
        reader.lineSequence().forEach { raw ->
            val line = raw.substringBefore('#').trim().lowercase()
            if (line.isNotEmpty()) blocked.add(line.removePrefix("*."))
        }
    }

    fun isBlocked(host: String?): Boolean {
        if (host.isNullOrEmpty()) return false
        var h = host
        // Walk up the labels: a.b.c.com -> b.c.com -> c.com -> com
        while (true) {
            if (blocked.contains(h)) return true
            val dot = h!!.indexOf('.')
            if (dot < 0) return false
            h = h.substring(dot + 1)
        }
    }

    fun size(): Int = blocked.size

    companion object {
        // Small, well-known seed set; the real weight comes from assets/blocklist.txt.
        private val SEED = setOf(
            "doubleclick.net",
            "googleadservices.com",
            "google-analytics.com",
            "googlesyndication.com",
            "adservice.google.com",
            "graph.facebook.com",
            "app-measurement.com",
            "firebase-settings.crashlytics.com",
            "ads.yahoo.com",
            "adnxs.com",
            "scorecardresearch.com",
            "mopub.com",
            "unityads.unity3d.com",
            "applovin.com",
            "chartboost.com",
            "flurry.com",
        )
    }
}
