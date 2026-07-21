package com.apolos.shieldlite;

import android.content.Context;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.HashSet;
import java.util.Set;

/** Domain blocklist for the DNS-filtering tunnel: seed list + bundled assets + user file. */
final class DnsFilter {

    private static final String[] SEED = {
            "doubleclick.net", "googleadservices.com", "google-analytics.com",
            "googlesyndication.com", "adservice.google.com", "graph.facebook.com",
            "app-measurement.com", "firebase-settings.crashlytics.com", "ads.yahoo.com",
            "adnxs.com", "scorecardresearch.com", "mopub.com", "unityads.unity3d.com",
            "applovin.com", "chartboost.com", "flurry.com",
    };

    private final Set<String> blocked = new HashSet<>();

    DnsFilter(Context ctx) {
        for (String s : SEED) blocked.add(s);
        try (BufferedReader r = new BufferedReader(
                new InputStreamReader(ctx.getAssets().open("blocklist.txt")))) {
            loadFrom(r);
        } catch (IOException ignored) {
            // no bundled asset — seed list still applies
        }
        File userFile = new File(ctx.getFilesDir(), "user_blocklist.txt");
        if (userFile.exists()) {
            try (BufferedReader r = new BufferedReader(new FileReader(userFile))) {
                loadFrom(r);
            } catch (IOException ignored) { }
        }
    }

    private void loadFrom(BufferedReader r) throws IOException {
        String line;
        while ((line = r.readLine()) != null) {
            int hash = line.indexOf('#');
            if (hash >= 0) line = line.substring(0, hash);
            line = line.trim().toLowerCase();
            if (!line.isEmpty()) {
                if (line.startsWith("*.")) line = line.substring(2);
                blocked.add(line);
            }
        }
    }

    boolean isBlocked(String host) {
        if (host == null || host.isEmpty()) return false;
        String h = host;
        while (true) {
            if (blocked.contains(h)) return true;
            int dot = h.indexOf('.');
            if (dot < 0) return false;
            h = h.substring(dot + 1);
        }
    }

    int size() { return blocked.size(); }
}
