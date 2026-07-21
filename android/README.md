# Apolos Shield 🛡️

Bezpečnostní a soukromí hlídač pro Android (min. Android 8.0, cíl Android 14).
Sleduje telefon v reálném čase, filtruje síťový provoz přes ochrannou VPN a
okamžitě spustí **alarm na hlavní stránce**, jakmile něco sáhne na kameru,
mikrofon nebo obrazovku.

> **Poctivá poznámka k sestavení:** hotové `.apk` nešlo vygenerovat přímo v
> prostředí, kde projekt vznikal, protože tam byla zablokovaná Google/Android
> repozitáře (nešel stáhnout Android SDK). Projekt je proto kompletní a
> sestavitelný — APK vznikne buď v **Android Studiu**, přes **`./gradlew`**,
> nebo **automaticky v GitHub Actions** (workflow `android-build.yml` APK
> sestaví a nahraje jako artefakt ke stažení).

## Co umí

| Funkce | Jak to funguje |
|--------|----------------|
| 📷 **Detekce kamery** | `CameraManager.AvailabilityCallback` — pozná, když libovolná aplikace otevře kameru, a hned spustí CRITICAL alarm. |
| 🎙️ **Detekce mikrofonu** | `AudioManager.AudioRecordingCallback` — aktivní nahrávání = alarm. |
| 🖥️ **Detekce obrazovky** | `DisplayManager` — nový druhý/virtuální displej (typicky nahrávání nebo cast) = alarm. |
| 🧭 **Chování aplikací** | Sken nainstalovaných aplikací, rizikové skóre podle kombinace oprávnění (kamera+mikrofon+poloha+SMS…) a sideloadu. |
| 📊 **Tok dat** | `NetworkStatsManager` — největší „upovídané" aplikace za hodinu; vysoký upload rizikové aplikace = podezření na únik dat. |
| 🔒 **Ochranná VPN** | Lokální tunel bez cizího serveru: **DNS filtr** (blokuje trackery/malware doménám v celém systému) nebo **kill-switch** (okamžité odstřižení sítě). |
| 🌐 **Free VPN (WireGuard)** | Import konfigurace zdarma poskytovaného WireGuardu (např. free tier ProtonVPN) = šifrovaný tunel ven. |
| 🚨 **Alarm** | Banner na hlavní stránce + heads-up notifikace s alarmovým zvukem a vibracemi (bypass „Nerušit"). |
| 🔁 **Trvalý běh** | Foreground služba + spuštění po restartu. |

## „Několikanásobné" zabezpečení a limity VPN

Android bez rootu dovolí **jen jednu aktivní VPN** současně — nelze doslova
skládat několik tunelů za sebou. Vícevrstvá ochrana je zde řešena takto:

1. **WireGuard tunel** (šifrování + změna IP přes free VPN), **NEBO**
2. **DNS filtr** (systémové blokování trackerů/reklam/malware domén), plus
3. **Kill-switch** jako nouzové odstřižení, plus
4. **Firewall/behaviorální monitoring** aplikací a datového toku běžící souběžně.

Tip: do WireGuard konfigurace nastav `DNS = ` na filtrující resolver — získáš
šifrovaný tunel i filtrování domén najednou.

## Sestavení APK

### A) GitHub Actions (nejjednodušší, APK ke stažení)
Push do větve spustí workflow **Build Apolos Shield APK**. V záložce *Actions*
si po doběhnutí stáhneš artefakty `apolos-shield-release` a `apolos-shield-debug`.

### B) Lokálně
```bash
cd android
./tools/generate-keystore.sh     # vytvoří podpisový klíč + app/keystore.properties
./gradlew assembleRelease         # -> app/build/outputs/apk/release/app-release.apk
# nebo bez klíče:
./gradlew assembleDebug           # -> app/build/outputs/apk/debug/app-debug.apk
```
Potřebuješ nainstalovaný **Android SDK** (platform 34, build-tools) a **JDK 17**.
V Android Studiu stačí projekt `android/` otevřít a dát *Build > Generate Signed APK*.

## Podpis v1 + v2 + v3 + v4

`app/build.gradle.kts` má v `signingConfigs.release` zapnuté všechny schéma:
`enableV1Signing`, `enableV2Signing`, `enableV3Signing` (rotace klíčů) a
`enableV4Signing` (inkrementální instalace) — to je nejnovější a nejnáročnější
podpisový standard pro moderní Android.

Ověření po sestavení:
```bash
$ANDROID_HOME/build-tools/34.0.0/apksigner verify --verbose --print-certs \
    app/build/outputs/apk/release/app-release.apk
```

## Oprávnění, která si musíš v telefonu povolit
- **Oznámení** (alarm) — tlačítko *Grant notifications*.
- **Přístup k využití (Usage access)** — pro statistiky provozu, tlačítko *Grant usage access*.
- **VPN souhlas** — vyskočí při prvním zapnutí VPN.
- Aplikace si **záměrně nebere** oprávnění ke kameře ani mikrofonu — jen
  *detekuje* jejich používání jinými aplikacemi, sama nikdy nenahrává.

## Etické použití
Nástroj je určený k ochraně **vlastního** zařízení. Sledování cizího telefonu
bez souhlasu je nezákonné.

## Struktura
```
android/
├── app/src/main/java/com/apolos/shield/
│   ├── MainActivity.kt            # dashboard + alarm banner (Compose)
│   ├── core/SecurityState.kt      # sdílený stav (StateFlow)
│   ├── alarm/Notifier.kt          # notifikační kanály + alarm
│   ├── monitor/                   # Camera/Mic, Screen, App, Traffic monitory
│   ├── vpn/                       # ShieldVpnService, DnsFilter, PacketUtils, WireGuard
│   └── service/                   # foreground služba + boot receiver
├── app/src/main/assets/blocklist.txt
└── tools/generate-keystore.sh
```
