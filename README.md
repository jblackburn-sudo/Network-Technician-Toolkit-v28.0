# Network Technician Toolkit

A comprehensive Android network diagnostics and Wi-Fi analysis toolkit designed for field technicians, network professionals, IT support personnel, and troubleshooting environments.

---

## Features

- **Live Connectivity Diagnostics**: Real-time home dashboard monitor reporting internet reachability/validation status, active transport type, local IP, VPN tunnel status, and default gateway round-trip latency.
- **Network Information & Connection Diagnostics**: Detailed inspection separating physical network interfaces (`wlan0`, cellular, Ethernet) from virtual VPN interfaces (`tun0`, `ipsec`, etc.), including IPv4/IPv6 addresses, default gateways, DNS servers, link MTU (reporting `Unavailable` when not exposed rather than `0`), public IP lookup, special-use IPv4 validation (including RFC 5737 `TEST-NET-1`, `TEST-NET-2`, `TEST-NET-3`, and link-local ranges), and diagnostic warnings distinguishing connectivity faults from Android OS visibility limits.
- **Ping Test (Kotlin Coroutines)**: Non-blocking coroutine-based ping engine supporting `Auto`, `ICMP`, and `Socket (TCP)` modes, target presets, configurable packet counts, real-time summary metrics (min/avg/max/mdev, loss %), an interactive custom SVG/JavaScript D3-style latency line chart, packet stream log, and automatic Room history persistence.
- **DNS Lookup & Diagnostics**: Hostname and reverse-IP (`PTR`) lookups across `A`, `AAAA`, `MX`, `TXT`, `NS`, `CNAME`, and `PTR` record types via system resolution and DNS-over-HTTPS (`dns.google` / `cloudflare-dns.com`), alongside active network DNS and Android Private DNS (`DoT`) inspection and failure categorization (`NXDOMAIN`, `SERVFAIL`, timeout, offline).
- **Wi-Fi Scanner & Live Signal Tools**: Nearby access point discovery with 2.4 GHz / 5 GHz / 6 GHz band and channel mapping, channel width and Wi-Fi standard identification, security parsing (`Open`, `WPA2`, `WPA3`, `WPA2/WPA3 Mixed`, `WPS`), band/security filters, sorting, and live signal fluctuation graphing.
- **RSSI & SNR Analysis**: Real-time Wi-Fi signal strength (`dBm`) and signal-to-noise ratio (`dB`) telemetry read from Android system APIs and `/proc/net/wireless` when exposed by the device driver (unavailable hardware noise-floor metrics remain explicitly `Unavailable` and are never fabricated).
- **Signal Mapper**: Room-by-room Wi-Fi coverage survey tool for apartments, houses, offices, or custom multi-floor layouts, featuring a dynamic 2D floor-plan canvas, spatial signal heatmap interpolation, sample pin inspection, coverage/dead-zone analysis, AP placement recommendations, and persistent saved surveys.
- **Traceroute & Interactive Topology Graph**: Hop-by-hop network path tracing with per-hop RTT measurements, unresponsive-hop reporting, Room history persistence, and an interactive custom SVG/JavaScript D3-style hop visualization graph with zoom/fit controls.
- **LAN Scanner**: Concurrent coroutine subnet sweep over active local Wi-Fi or Ethernet subnets, identifying responding hosts, gateway/current-device roles, common open service ports, and response latency.
- **Network Speed Test**: Native OkHttp streaming download, upload, latency, and jitter benchmark using public CDN endpoints, paired with a WebView speed test launcher supporting preset services (`Speedtest.net`, `Cloudflare Speed`, `Fast.com`, `LibreSpeed`) and custom technician URLs.
- **iPerf3 Client & Server**: Built-in iPerf3-compatible protocol client and server supporting configurable ports, test duration, protocol options, parallel streams, interval throughput updates, and diagnostic feedback.
- **Subnet Calculator**: IPv4 CIDR subnet calculator displaying network address, broadcast address, subnet mask, wildcard mask, usable host range, usable/total host counts, binary octets, IP class, and RFC address scope.
- **Network Notes**: Local Room-backed field-technician notes with titles, categories, IP/host tags, pin-to-top support, hardware-backed Android Keystore AES-256-GCM encryption (`CredentialCryptoManager`) for `Credentials` entries, search, edit, and delete actions.
- **Diagnostic History**: Persistent Room history viewer for Ping and Traceroute sessions with search, session inspection, and CSV/JSON/text report exports.
- **Interactive Technician Learning Guide & Glossary**: First-launch onboarding, adaptive 1–5 per-tool proficiency profiling, personalized recommended guides, structured A–H walkthroughs, searchable offline glossary across 50+ networking terms, troubleshooting playbooks, advanced references, and contextual Help dialogs inside every diagnostic tool.
- **Report Export & Privacy Protection**: Independent on-screen `Show SSID` and `Show BSSID` privacy toggles (both OFF by default), Storage Access Framework (`ACTION_CREATE_DOCUMENT`) saving and `FileProvider` sharing in RTF (plus JSON/CSV exports), automatic default SSID/BSSID/MAC redaction, explicit two-step one-time confirmation for unredacted exports (never persisted), and unconditional clipboard redaction.

---

## Technology

- **Language**: Kotlin and Java (`MainActivity.java`, `WebTestActivity.java`, Kotlin engines, ViewModels, and Compose UI)
- **UI Framework**: Jetpack Compose (Material Design 3) and Android Views (legacy home/ping/WebView host activities)
- **Architecture**: MVVM with `AndroidViewModel` / `ViewModel`, `StateFlow`, and Kotlin Coroutines
- **Database & Persistence**: AndroidX Room (`NetworkToolkitDatabase` schema v4 for ping history, traceroute history, and network notes), Android Keystore (`AES/GCM/NoPadding` for credential notes), and `SharedPreferences` (app appearance, Wi-Fi privacy toggles, battery-saver animation policy, speed test service preferences, learning guide progress, and signal map surveys)
- **Networking & Diagnostics**: Android `ConnectivityManager`, `LinkProperties`, `NetworkCapabilities`, `WifiManager`, `java.net` sockets / `InetAddress`, `/proc/net/wireless`, and `OkHttp`
- **Visualization**: Embedded WebView + HTML/SVG/JavaScript D3-style interactive charts (`PingD3LatencyChartView`, `TracerouteD3GraphView`) and Compose `Canvas` (`SignalMapCanvas`, `WifiSignalFluctuationGraph`)
- **Build System**: Gradle (Kotlin DSL) with Version Catalog (`gradle/libs.versions.toml`) and KSP
- **Testing**: JUnit 4, Robolectric, AndroidX Compose UI Test, and Kotlin Coroutines Test

---

## Requirements

- **OS**: Android 8.0 (API 26) or later (`minSdk = 26`, `targetSdk = 36`, `compileSdk = 36`).
- **Permissions** (declared in `app/src/main/AndroidManifest.xml`):
  - `android.permission.INTERNET` — Network probes, DNS-over-HTTPS queries, speed tests, and iPerf3 traffic.
  - `android.permission.ACCESS_NETWORK_STATE` — Inspecting active network transports, IP addresses, routes, MTU, and VPN status.
  - `android.permission.ACCESS_WIFI_STATE` / `android.permission.CHANGE_WIFI_STATE` — Reading Wi-Fi connection telemetry and triggering Wi-Fi scans.
  - `android.permission.ACCESS_FINE_LOCATION` / `android.permission.ACCESS_COARSE_LOCATION` — Required by Android for Wi-Fi scan results and SSID/BSSID visibility on supported OS versions.
  - `android.permission.NEARBY_WIFI_DEVICES` — Nearby Wi-Fi scanning permission on Android 13+.
- **Device & OS Limitations**:
  - Android enforces Wi-Fi scan throttling (typically up to 4 foreground scans per 2 minutes on Android 9+) unless disabled in Developer Options.
  - SSID and BSSID access requires runtime Location / Nearby Wi-Fi permissions and device Location Services to be enabled.
  - Wi-Fi SNR and noise-floor readings depend on hardware and kernel driver exposure (`/proc/net/wireless`); unsupported devices display `Unavailable` rather than estimated values.
  - Some mobile carriers, VPN tunnels, or Android sandboxes restrict raw ICMP echo (`ping` / `traceroute`) packets; the app provides TCP socket fallbacks where applicable.

---

## Build Instructions

This project uses the included Gradle Wrapper (`./gradlew`) and is compatible with Android Studio and AndroidIDE / Code on the Go.

### Build Debug APK

```bash
./gradlew :app:assembleDebug --no-daemon --console=plain
```

Generated debug APK output path:

```text
app/build/outputs/apk/debug/app-debug.apk
```

### Run Unit & Robolectric Tests

```bash
./gradlew :app:testDebugUnitTest --no-daemon --console=plain
```

### Run Android Lint

```bash
./gradlew :app:lintDebug --no-daemon --console=plain
```

### Environment Notes

- Do not create or commit a `local.properties` file containing machine-specific SDK paths if collaborating across environments; Android Studio or your local `ANDROID_HOME` / `ANDROID_SDK_ROOT` environment variable provides the SDK path automatically.
- `app/build.gradle.kts` configures debug signing using `debug.keystore` in the repository root (`storePassword = "android"`, `keyAlias = "androiddebugkey"`). Because `*.keystore` files are excluded by `.gitignore`, fresh clones should generate a local `debug.keystore` at the repository root (for example via `keytool -genkeypair -v -keystore debug.keystore -storepass android -alias androiddebugkey -keypass android -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=Android Debug,O=Android,C=US"`) before running `:app:assembleDebug`.
- Kotlin/Compose source files use Gradle Kotlin DSL scripts and `gradle/libs.versions.toml` with AGP 9.1.1, Kotlin 2.2.10, and JDK 11 bytecode target (`JavaVersion.VERSION_11`).

---

## Installation

1. Build the debug APK using the command above (or download a build artifact).
2. Install onto a connected Android 8.0+ device over ADB:

   ```bash
   adb install -r app/build/outputs/apk/debug/app-debug.apk
   ```

   Alternatively, copy `app/build/outputs/apk/debug/app-debug.apk` to your Android device and open it from your file manager (allowing installation from unknown sources when prompted).

---

## Release Builds

Release builds in `app/build.gradle.kts` read upload signing parameters from environment variables so private credentials are never stored in source control:

- `KEYSTORE_PATH` (defaults to `my-upload-key.jks` in the project root if unset)
- `STORE_PASSWORD`
- `KEY_PASSWORD`

With those environment variables configured locally or in a secure CI runner, build a signed release APK or Android App Bundle (AAB):

```bash
./gradlew :app:assembleRelease --no-daemon --console=plain
./gradlew :app:bundleRelease --no-daemon --console=plain
```

Never commit upload keystores (`*.jks`, `*.keystore`), keystore passwords, or `.env` files to the repository.

---

## Project Status

Network Technician Toolkit is an actively developed Android network diagnostic toolkit (currently **Version 28.0**, `versionCode 28`, application ID `com.aistudio.networktoolkit.arqxcv`, namespace `com.example.networktoolkit`).

The project is currently distributed as source code and local builds and is **not** claimed to be published on Google Play.

---

## Contributing

Contributions, bug reports, and feature suggestions are welcome:

1. Open an issue describing the device model, Android version, network scenario, and steps to reproduce any bug.
2. For code changes, fork the repository and create a focused branch.
3. Preserve existing privacy redaction, hardware-fallback semantics, and Room database compatibility.
4. Run `./gradlew :app:testDebugUnitTest --no-daemon --console=plain` and `./gradlew :app:lintDebug --no-daemon --console=plain` before opening a pull request.

---

## License

Copyright 2026 Joel Blackburn

This project is licensed under the **Apache License, Version 2.0** — see the [`LICENSE`](LICENSE) file for the full license text and terms. Third-party libraries retain their respective open-source licenses and copyright notices.

---

## Privacy

- **Local Storage Only & Backup Exclusion**: Diagnostic history (Ping and Traceroute sessions), technician notes, signal map surveys, and preferences are stored locally on the device using Room (`network_toolkit_database`) and Android `SharedPreferences`. `AndroidManifest.xml` sets `android:allowBackup="false"`, and `backup_rules.xml` / `data_extraction_rules.xml` exclude local databases, preferences, and staged export files from cloud backup and device-to-device transfer.
- **Credential Encryption**: Notes categorized under `Credentials` are encrypted at rest with hardware-backed Android Keystore AES-256-GCM (`CredentialCryptoManager`), and credential bodies are redacted by default during note exports.
- **On-Screen Wi-Fi Privacy Controls**: `WifiPrivacyPreferences` keeps `Show SSID` and `Show BSSID` disabled (`false`) independently by default, masking identifiers live across UI screens as `Hidden Network (Privacy Setting)` and `Hidden MAC (Privacy Setting)` without requiring an app restart.
- **No Analytics or Tracking**: The app contains no advertising SDKs, third-party analytics, or background telemetry collection.
- **User-Initiated Network Traffic**: `AndroidManifest.xml` sets `android:usesCleartextTraffic="false"`. Network requests occur only when running active diagnostic features (for example, Ping/Traceroute/LAN/iPerf3 probes, DNS-over-HTTPS queries in DNS Lookup, public CDN/HTTPS speed tests, or public IP inspection in Network Info).
- **Report Export Redaction & Per-Export Consent**: Enabling on-screen SSID/BSSID visibility never disables export redaction. `TextReportExportManager` redacts discovered SSIDs (`Hidden Network`) and BSSIDs/MACs (`Hidden MAC`) by default and verifies zero leakage before writing or sharing. Exporting unredacted Wi-Fi identifiers requires selecting Option B in the export privacy dialog, reviewing the warning, and explicitly confirming—strictly for that single export without persisting. Clipboard copies are always redacted and flagged with `ClipDescription.EXTRA_IS_SENSITIVE` on Android 13+.

---

## Troubleshooting

- **SSID or BSSID displays `Unavailable` or masked text**:
  1. Ensure **Location** permission (`ACCESS_FINE_LOCATION`) and **Nearby Wi-Fi Devices** (`NEARBY_WIFI_DEVICES` on Android 13+) are granted in Android App Settings.
  2. Confirm system **Location Services** are turned ON (Android restricts `WifiInfo` SSID/BSSID when Location Services are off).
  3. On-screen display also depends on the independent **Show SSID** and **Show BSSID** toggles in **Settings & Appearance** (both OFF by default).
- **Wi-Fi Scanner stops updating on repeated scans**:
  - Android 9+ enforces foreground Wi-Fi scan throttling (typically 4 scans per 2 minutes). Wait for the cooldown window or enable **Wi-Fi scan throttling** bypass in Android Developer Options for field surveys.
- **Ping or Traceroute shows timeouts or falls back to TCP**:
  - Some mobile networks, corporate VPNs, and Android SELinux policies block raw ICMP packets or ICMP TTL-exceeded replies. Switch Ping mode between `Auto`, `ICMP`, and `Socket (TCP)` as needed.
- **Wi-Fi SNR or noise floor shows `Unavailable`**:
  - Noise floor telemetry is read from kernel driver statistics (`/proc/net/wireless`) when exposed by the device; unsupported chipsets/Android versions report `Unavailable` rather than fabricated estimates.
- **Local `:app:assembleDebug` fails with missing `debug.keystore`**:
  - Keystores are excluded from Git via `.gitignore`. Generate a standard local `debug.keystore` at the repository root using the `keytool` command shown in **Environment Notes**.
- **Release builds fail during signing**:
  - Export `KEYSTORE_PATH`, `STORE_PASSWORD`, and `KEY_PASSWORD` in your environment before invoking `:app:assembleRelease` or `:app:bundleRelease`.
