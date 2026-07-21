# Apolos Shield Lite (offline manual build)

A minimal, dependency-free Java rewrite of a subset of Apolos Shield, built
here without Gradle/AGP/AndroidX/Jetpack Compose — because the Android Gradle
Plugin and AndroidX/Compose are only distributed via `maven.google.com`, which
redirects to `dl.google.com`, and that host is blocked by this sandbox's
network policy. This variant is compiled and packaged directly with the raw
SDK tools instead:

```
javac (source/target 17, plain android.* APIs only)
  -> d8       (dex)
  -> aapt2    (resource compile + link, manifest, APK packaging)
  -> zipalign
  -> apksigner (v1 + v2 + v3 + v4 signing)
```

No `android.jar` from `dl.google.com` was needed either — the classpath used
here is Robolectric's `android-all` artifact (mirrors the real framework API,
published on Maven Central).

## Feature scope

Covers: real-time camera/microphone activation alarms, a local DNS-filtering
VPN, a kill-switch VPN mode, and a foreground monitoring service restarted on
boot.

Does **not** include (present only in the full app under `android/`): Jetpack
Compose UI, screen-capture detection, per-app risk scoring, traffic
monitoring, or WireGuard import. Those depend on AndroidX/Compose and are
built via the GitHub Actions workflow instead, where `dl.google.com` is
reachable.

## Rebuilding

`build/` and `keystore/` are intentionally untracked (the keystore holds a
private key and must never be committed). Regenerate them locally:

```bash
export ANDROID_HOME=/path/to/android-sdk   # needs platform-34/android.jar + build-tools/34.0.0
BT="$ANDROID_HOME/build-tools/34.0.0"

"$BT/aapt2" compile --dir res -o build/res-compiled/res.zip
"$BT/aapt2" link -o build/apk/base.apk -I "$ANDROID_HOME/platforms/android-34/android.jar" \
  --manifest AndroidManifest.xml --java build/gen -R build/res-compiled/res.zip \
  --auto-add-overlay --min-sdk-version 26 --target-sdk-version 34

javac --release 17 -classpath "$ANDROID_HOME/platforms/android-34/android.jar" \
  -d build/classes $(find src build/gen -name '*.java')

"$BT/d8" --release --min-api 26 --output build/dex $(find build/classes -name '*.class')

cp build/apk/base.apk build/apk/unsigned.apk
(cd build/dex && zip -q -X ../apk/unsigned.apk classes.dex)
zip -q -X -r build/apk/unsigned.apk assets

"$BT/zipalign" -f -p 4 build/apk/unsigned.apk build/apk/aligned.apk

keytool -genkeypair -keystore keystore/apolos-lite.jks -alias apolos-lite \
  -keyalg RSA -keysize 4096 -validity 10000 \
  -dname "CN=Apolos Shield Lite, OU=Security, O=Apolos, C=CZ"

"$BT/apksigner" sign --ks keystore/apolos-lite.jks --ks-key-alias apolos-lite \
  --v1-signing-enabled true --v2-signing-enabled true \
  --v3-signing-enabled true --v4-signing-enabled true \
  --out build/apk/ApolosShieldLite-signed.apk build/apk/aligned.apk
```
