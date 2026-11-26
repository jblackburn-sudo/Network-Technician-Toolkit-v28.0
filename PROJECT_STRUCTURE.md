# Project Structure — Network Technician Toolkit

Overview of the repository layout and primary source modules.

---

## Root Directory

| Path | Purpose |
| --- | --- |
| `build.gradle.kts` | Top-level Gradle plugin configuration (AGP, Kotlin Compose, KSP). |
| `settings.gradle.kts` | Gradle project name (`Network Toolkit`) and module inclusion (`:app`). |
| `gradle.properties` | JVM heap, parallel build, caching, and Kotlin compiler settings. |
| `gradle/libs.versions.toml` | Centralized Gradle Version Catalog for SDKs, libraries, and plugins. |
| `gradlew`, `gradlew.bat`, `gradle/wrapper/` | Gradle Wrapper scripts and wrapper JAR/properties for reproducible builds. |
| `.gitignore` | Git exclusion rules for build outputs, caches, keystores, local `.env*` files (including `.env.example`), and private workspace metadata (`metadata.json`, `.aistudio/`). |
| `README.md`, `RELEASE_CHECKLIST.md`, `RELEASE_NOTES_TEMPLATE.md`, `PROJECT_STRUCTURE.md` | Repository documentation and release guides. |

---

## App Module (`app/`)

| Path | Purpose |
| --- | --- |
| `app/build.gradle.kts` | Module build configuration (`applicationId` `com.aistudio.networktoolkit.arqxcv`, `namespace` `com.example.networktoolkit`, SDK versions `26/36`, version `28.0` / code `28`, signing configuration, dependencies). |
| `app/proguard-rules.pro` | ProGuard/R8 configuration rules for release builds. |
| `app/src/main/AndroidManifest.xml` | Declared permissions, activities, and `FileProvider` authority configuration. |

---

## Source Packages (`app/src/main/java/com/example/`)

### Core App & Diagnostic Screens (`com.example.networktoolkit`)

- **Home & Legacy Hosts**: `MainActivity.java` (home dashboard, live connectivity card, Ping screen, Traceroute/LAN fallbacks, theme/battery-saver settings), `WebTestActivity.java` (WebView host for web speed test services).
- **Connectivity & Connection Report**: `NetworkConnectivityMonitor.kt`, `NetworkDetails.kt`, `NetworkInfoActivity.kt`, `NetworkInfoScreen.kt`, `NetworkInfoViewModel.kt`, `diagnostics/IpAddressValidator.kt`.
- **Ping & Charts**: `CoroutinePingEngine.kt`, `PingD3LatencyChartView.kt`, `PingHistoryActivity.kt`, `PingHistoryScreen.kt`, `PingHistoryViewModel.kt`.
- **Traceroute**: `TracerouteActivity.kt`, `TracerouteEngine.kt`, `TracerouteScreen.kt`, `TracerouteViewModel.kt`, `TracerouteD3GraphView.kt`.
- **DNS Diagnostics**: `DnsLookupActivity.kt`, `DnsLookupEngine.kt`, `DnsLookupScreen.kt`, `DnsLookupViewModel.kt`, `NetworkDnsInspector.kt`.
- **LAN & Subnet Utilities**: `LanScannerActivity.kt`, `LanScannerEngine.kt`, `LanScannerScreen.kt`, `LanScannerViewModel.kt`, `SubnetCalculatorActivity.kt`, `SubnetCalculatorEngine.kt`, `SubnetCalculatorScreen.kt`, `SubnetCalculatorViewModel.kt`.
- **Wi-Fi Signal Telemetry & Battery Policy**: `WifiTelemetryReader.kt`, `RealTimeWifiSignalProvider.kt`, `SignalStrengthClassifier.kt`, `SignalStrengthComponents.kt`, `WifiSignalFluctuationGraph.kt`, `AppBatterySaverManager.kt`.
- **Field Notes**: `NetworkNotesActivity.kt`, `NetworkNotesScreen.kt`, `NetworkNotesViewModel.kt`.

### Specialized Feature Packages

- `data/`: Room database (`NetworkToolkitDatabase.kt`), entities and DAOs (`PingResult*`, `TracerouteResult*`, `NetworkNote*`), repositories, CSV/JSON/text exporters (`DiagnosticReportExporter.kt`, `PingCsvExporter.kt`), and privacy-redacted SAF/share export manager (`TextReportExportManager.kt`, `TextReportExportUi.kt`).
- `iperf3/`: iPerf3 client/server protocol engine, diagnostics, Compose UI, and ViewModel (`IPerf3Activity.kt`, `IPerf3Engine.kt`, `IPerf3Diagnostics.kt`, `IPerf3Screen.kt`, `IPerf3ViewModel.kt`).
- `learning/`: Interactive Technician Learning Guide, onboarding persistence, 1–5 proficiency assessment, A–H tool walkthroughs, 50+ term offline glossary, contextual help dialogs, troubleshooting playbooks, and advanced references (`LearningGuideActivity.kt`, `LearningGuideScreen.kt`, `LearningGuideTabsContent.kt`, `LearningGuideViewModel.kt`, `LearningGuideCatalog.kt`, `LearningGlossaryCatalog.kt`, `LearningGuideModels.kt`, `LearningGuidePreferences.kt`, `ContextualHelpDialog.kt`).
- `signalmap/`: Room-by-room Wi-Fi survey & heatmap mapper (`SignalMapperActivity.kt`, `SignalMapEngine.kt`, `SignalMapCanvas.kt`, `SignalMapModels.kt`, `SignalMapScreen.kt`, `SignalMapCustomSetupComponents.kt`, `SignalMapSurveyRepository.kt`, `SignalMapViewModel.kt`).
- `speedtest/`: Native OkHttp speed benchmark and web service preferences (`SpeedTestActivity.kt`, `SpeedTestEngine.kt`, `SpeedTestScreen.kt`, `SpeedTestViewModel.kt`, `SpeedTestServicePreferences.kt`).
- `wifiscan/`: Nearby Wi-Fi access point scanner (`WifiScannerActivity.kt`, `WifiScannerEngine.kt`, `WifiNetworkInfo.kt`, `WifiScannerScreen.kt`, `WifiScannerViewModel.kt`).
- `ui/theme/`: Material Design 3 color palette, typography, and theme mode resolution (`Color.kt`, `Theme.kt`, `Type.kt`).

---

## Resources & Tests

- `app/src/main/res/`: Adaptive launcher icons (`mipmap-*`, `drawable/`), string/color/theme resources (`values/`), and `FileProvider`/backup XML configs (`xml/`).
- `app/src/test/java/com/example/`: JUnit 4 and Robolectric unit/UI test suites (`ExampleUnitTest.kt`, `ExampleRobolectricTest.kt`, and `networktoolkit/` feature suites covering Diagnostics, DNS/Private DNS, iPerf3, Network Notes Encryption, Privacy Controls & Export, Signal Map, Technician Learning Guide, Text Report Export, Traceroute D3 Graph, and Wi-Fi RSSI/SNR/SSID/BSSID).
- `app/src/androidTest/java/com/example/`: Android instrumentation test stub (`ExampleInstrumentedTest.kt`).
